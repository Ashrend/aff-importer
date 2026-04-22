package aff.importer.tool

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.SonglistRepository
import aff.importer.tool.data.model.Song
import aff.importer.tool.data.model.SonglistUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Songlist 管理界面的 ViewModel（精简版）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SonglistViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SonglistViewModel"
        private const val MAX_CONCURRENT_LOADS = 4
    }

    private val songlistRepository = SonglistRepository(application)

    private val _uiState = MutableStateFlow(SonglistUiState())
    val uiState: StateFlow<SonglistUiState> = _uiState.asStateFlow()

    private var currentDirectoryUri: Uri? = null
    private var hasLoaded = false

    // 曲绘 URI 缓存（线程安全）
    private val jacketUris = ConcurrentHashMap<String, Uri?>()

    // 正在进行的加载任务（线程安全）
    private val activeLoads = ConcurrentHashMap<String, Job>()
    private var backgroundLoadJob: Job? = null
    private var currentListLoadJob: Job? = null

    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOADS)

    /**
     * 强制刷新歌曲列表
     */
    fun refreshSongs(directoryUri: Uri?) {
        hasLoaded = false
        loadSongs(directoryUri)
    }

    /**
     * 加载歌曲列表（带缓存）
     */
    fun loadSongs(directoryUri: Uri?) {
        if (directoryUri == null) {
            _uiState.update { it.copy(error = "请先选择目录", isLoading = false) }
            return
        }

        if (hasLoaded && currentDirectoryUri == directoryUri && _uiState.value.allSongs.isNotEmpty()) {
            return
        }

        currentDirectoryUri = directoryUri

        // 清理所有旧任务与缓存
        currentListLoadJob?.cancel()
        backgroundLoadJob?.cancel()
        backgroundLoadJob = null
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        jacketUris.clear()

        _uiState.update { it.copy(isLoading = true, error = null) }

        currentListLoadJob = viewModelScope.launch {
            try {
                val songs = songlistRepository.getAllSongs(directoryUri)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allSongs = songs,
                        songs = songs,
                        error = if (songs.isEmpty()) "目录中没有找到曲目" else null
                    )
                }

                hasLoaded = true
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载失败: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * 获取曲绘 URI
     */
    fun getJacketUri(song: Song): Uri? {
        return jacketUris[song.getActualFolderId()]
    }

    /**
     * 更新搜索关键词
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            val filteredSongs = if (query.isBlank()) {
                state.allSongs
            } else {
                state.allSongs.filter { song ->
                    song.id.contains(query, ignoreCase = true) ||
                    song.getDisplayTitle().contains(query, ignoreCase = true) ||
                    song.getDisplayArtist().contains(query, ignoreCase = true)
                }
            }
            state.copy(
                searchQuery = query,
                songs = filteredSongs
            )
        }
    }

    /**
     * 选择歌曲（打开编辑）
     */
    fun selectSong(song: Song?) {
        _uiState.update { it.copy(selectedSong = song) }
    }

    /**
     * 更新歌曲元数据
     */
    fun updateSong(updatedSong: Song) {
        val directoryUri = currentDirectoryUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val originalSong = _uiState.value.allSongs.find { it.id == updatedSong.id }
            val remoteDlChanged = originalSong != null && originalSong.remoteDl != updatedSong.remoteDl

            if (remoteDlChanged) {
                val oldFolderId = originalSong!!.getActualFolderId()
                val newFolderId = updatedSong.getActualFolderId()

                Log.d(TAG, "Remote DL changed, renaming folder from $oldFolderId to $newFolderId")

                val renameSuccess = songlistRepository.renameSongFolder(directoryUri, oldFolderId, newFolderId)
                if (!renameSuccess) {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = "重命名文件夹失败"
                        )
                    }
                    return@launch
                }

                jacketUris.remove(oldFolderId)
            }

            val success = songlistRepository.updateSong(directoryUri, updatedSong)

            if (success) {
                _uiState.update { state ->
                    val updatedAllSongs = state.allSongs.map {
                        if (it.id == updatedSong.id) updatedSong else it
                    }
                    val updatedSongs = if (state.searchQuery.isBlank()) {
                        updatedAllSongs
                    } else {
                        updatedAllSongs.filter { song ->
                            song.id.contains(state.searchQuery, ignoreCase = true) ||
                            song.getDisplayTitle().contains(state.searchQuery, ignoreCase = true) ||
                            song.getDisplayArtist().contains(state.searchQuery, ignoreCase = true)
                        }
                    }

                    state.copy(
                        isSaving = false,
                        saveSuccess = true,
                        allSongs = updatedAllSongs,
                        songs = updatedSongs,
                        selectedSong = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = "保存失败"
                    )
                }
            }
        }
    }

    /**
     * 显示删除确认对话框
     */
    fun showDeleteConfirm(song: Song) {
        _uiState.update {
            it.copy(
                showDeleteConfirm = true,
                songToDelete = song
            )
        }
    }

    /**
     * 取消删除
     */
    fun dismissDeleteConfirm() {
        _uiState.update {
            it.copy(
                showDeleteConfirm = false,
                songToDelete = null
            )
        }
    }

    /**
     * 确认删除
     */
    fun confirmDelete() {
        val songToDelete = _uiState.value.songToDelete ?: return
        val directoryUri = currentDirectoryUri ?: return

        _uiState.update { it.copy(showDeleteConfirm = false) }

        viewModelScope.launch {
            val songId = songToDelete.id
            val folderId = songToDelete.getActualFolderId()

            val success = songlistRepository.deleteSong(directoryUri, songId, folderId)

            if (success) {
                jacketUris.remove(folderId)

                _uiState.update { state ->
                    val updatedAllSongs = state.allSongs.filter { it.id != songToDelete.id }
                    val updatedSongs = if (state.searchQuery.isBlank()) {
                        updatedAllSongs
                    } else {
                        updatedAllSongs.filter { song ->
                            song.id.contains(state.searchQuery, ignoreCase = true) ||
                            song.getDisplayTitle().contains(state.searchQuery, ignoreCase = true) ||
                            song.getDisplayArtist().contains(state.searchQuery, ignoreCase = true)
                        }
                    }

                    state.copy(
                        allSongs = updatedAllSongs,
                        songs = updatedSongs,
                        songToDelete = null,
                        deleteSuccess = true,
                        deletedSongName = songToDelete.getDisplayTitle()
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        error = "删除失败: ${songToDelete.id}",
                        songToDelete = null
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }

    fun clearDeleteSuccess() {
        _uiState.update { it.copy(deleteSuccess = false, deletedSongName = null) }
    }

    /**
     * 更新可见区域 - 由 UI 层在滚动时调用
     */
    fun updateVisibleItems(
        visibleItemIds: List<String>,
        preloadItemIds: List<String> = emptyList()
    ) {
        val directoryUri = currentDirectoryUri ?: return
        val allSongs = _uiState.value.songs

        val songMap = allSongs.associateBy { it.id }

        // 1. 取消不在可见或预加载范围内的任务
        val keepIds = (visibleItemIds + preloadItemIds).toSet()
        activeLoads.keys.toList().forEach { id ->
            if (id !in keepIds) {
                activeLoads.remove(id)?.cancel()
            }
        }

        // 2. 优先加载可见项
        visibleItemIds.forEach { id ->
            val song = songMap[id] ?: return@forEach
            val folderId = song.getActualFolderId()

            if (jacketUris.containsKey(folderId)) return@forEach

            activeLoads[folderId]?.cancel()

            val job = viewModelScope.launch(loadDispatcher) {
                try {
                    val uri = songlistRepository.getSongJacketUri(directoryUri, folderId)
                    jacketUris[folderId] = uri

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy() }
                    }
                } catch (e: CancellationException) {
                    // 正常取消，忽略
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load jacket for $folderId", e)
                } finally {
                    activeLoads.remove(folderId)
                }
            }
            activeLoads[folderId] = job
        }

        // 3. 预加载屏幕外附近的项（低优先级）
        preloadItemIds.forEach { id ->
            if (id in visibleItemIds) return@forEach

            val song = songMap[id] ?: return@forEach
            val folderId = song.getActualFolderId()

            if (jacketUris.containsKey(folderId) || activeLoads.containsKey(folderId)) return@forEach

            val job = viewModelScope.launch(loadDispatcher) {
                delay(100)

                try {
                    if (!isActive) return@launch

                    val uri = songlistRepository.getSongJacketUri(directoryUri, folderId)
                    jacketUris[folderId] = uri

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy() }
                    }
                } catch (e: CancellationException) {
                    // 正常取消
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to preload jacket for $folderId", e)
                } finally {
                    activeLoads.remove(folderId)
                }
            }
            activeLoads[folderId] = job
        }
    }

    /**
     * 启动后台加载服务
     */
    fun startBackgroundLoading() {
        val directoryUri = currentDirectoryUri ?: return
        val allSongs = _uiState.value.songs

        if (backgroundLoadJob?.isActive == true) return

        backgroundLoadJob = viewModelScope.launch(loadDispatcher) {
            allSongs.forEach { song ->
                val folderId = song.getActualFolderId()

                if (jacketUris.containsKey(folderId) || activeLoads.containsKey(folderId)) {
                    return@forEach
                }

                try {
                    val uri = songlistRepository.getSongJacketUri(directoryUri, folderId)
                    jacketUris[folderId] = uri

                    if (jacketUris.size % 10 == 0) {
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy() }
                        }
                    }

                    delay(20)
                } catch (e: Exception) {
                    Log.e(TAG, "Background load failed for $folderId", e)
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy() }
            }
        }
    }

    /**
     * 强制刷新所有曲绘
     */
    fun clearJacketCache() {
        jacketUris.clear()
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        backgroundLoadJob?.cancel()
        backgroundLoadJob = null
        _uiState.update { it.copy() }
    }
}
