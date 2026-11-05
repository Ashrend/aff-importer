package aff.importer.tool

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.ArtworkRepository
import aff.importer.tool.data.ArtworkStateStore
import aff.importer.tool.data.SonglistRepository
import aff.importer.tool.data.model.DuplicateSongEntry
import aff.importer.tool.data.model.DuplicateSongGroup
import aff.importer.tool.data.model.ArtworkKind
import aff.importer.tool.data.model.ArtworkScanState
import aff.importer.tool.data.model.Song
import aff.importer.tool.data.model.SonglistUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Songlist 管理界面的 ViewModel
 */
class SonglistViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SonglistViewModel"
    }

    private val songlistRepository = SonglistRepository(application)

    private val _uiState = MutableStateFlow(SonglistUiState())
    val uiState: StateFlow<SonglistUiState> = _uiState.asStateFlow()

    private var currentDirectoryUri: Uri? = null
    private var hasLoaded = false
    private var currentListLoadJob: Job? = null

    /**
     * 曲绘扫描编排：负责目录索引、优先级队列与应用内缓存落盘。
     * 它内部自带 IO 线程池，ViewModel 只负责发请求，不阻塞主线程。
     */
    private val artworkRepository = ArtworkRepository(application, viewModelScope, ArtworkKind.SONG_JACKET)

    /**
     * 每个曲目一个 Compose 状态：写入某个 id 只会让读取它的那个卡片重组。
     * 状态实例一旦建立就不能替换，否则 UI 观察不到后续更新。
     */
    private val artworkStates = ArtworkStateStore<Uri>()

    /**
     * 曲绘扫描进度，独立于列表状态，进度变化不会让整个网格重组。
     */
    val artworkScanState: StateFlow<ArtworkScanState> = artworkRepository.scanState

    init {
        // 串行消费扫描结果：viewModelScope 跑在主线程，写入天然串行
        viewModelScope.launch {
            artworkRepository.results.collect { result ->
                artworkStates.set(result.id, result.uri)
            }
        }
    }

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

        // 目标目录变了没有：变了就要清掉上一个目录残留的曲绘值
        val directoryChanged = currentDirectoryUri != directoryUri
        currentDirectoryUri = directoryUri

        if (directoryChanged) {
            // 切换目标目录：旧 Uri 指向的是上一个目录的缓存文件，
            // 继续显示就是错的（新目录里可能是另一张图）。
            // 清值而不是换实例 —— UI 观察的是状态实例，换实例后新图就再也显示不出来。
            artworkStates.clearValues()
        }

        // 只取消上一次未完成的列表加载；曲绘状态槽要保留，替换实例会让 UI 收不到更新
        currentListLoadJob?.cancel()

        _uiState.update { it.copy(isLoading = true, error = null) }

        currentListLoadJob = viewModelScope.launch {
            try {
                val songs = songlistRepository.getAllSongs(directoryUri)
                val duplicateGroups = detectDuplicateGroups(songs)

                // 曲绘状态槽必须在列表发布给 UI 之前准备好：
                // UI 读到不存在的状态就没有可观察的对象，之后图片永远不会出现
                val folderIds = songs.map { it.getActualFolderId() }
                artworkStates.retainOnly(folderIds)   // 丢弃旧目录的条目（id 相同的保留实例，值已在切换目录时清空）
                artworkStates.prepare(folderIds)      // 为新条目建立状态槽

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allSongs = songs,
                        songs = songs,
                        error = if (songs.isEmpty()) "目录中没有找到曲目" else null,
                        duplicateGroups = duplicateGroups,
                        showDuplicateDialog = duplicateGroups.isNotEmpty()
                    )
                }

                // 列表已经发布给 UI，再请求扫描曲绘
                artworkRepository.requestScan(directoryUri, folderIds)

                hasLoaded = true
            } catch (e: CancellationException) {
                // 协程被取消（ViewModel 销毁、切换目录重新加载）不是加载失败
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load songs", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载失败，请检查目录和文件格式"
                    )
                }
            }
        }
    }

    /**
     * 获取某个曲目文件夹的曲绘 URI。
     *
     * 必须在 composable 调用栈内读取，Compose 才会记录这次读取，
     * 之后写入新值时该卡片才会重组。
     */
    fun artworkUriFor(folderId: String): Uri? = artworkStates.valueFor(folderId)

    /**
     * 把当前可见的曲目插到扫描队列前面，让可见项先出图
     */
    fun prioritizeArtworks(ids: List<String>) {
        if (ids.isEmpty()) return
        artworkRepository.requestPrioritize(ids)
    }

    /**
     * 强制刷新曲绘
     *
     * 注意：这里不能调用 artworkStates.clear()，
     * clear() 会丢弃状态实例，而 UI 观察的是实例而不是值，
     * 重建实例后刷新出来的图片就再也显示不出来。
     * 刷新时旧的 Uri 先继续显示，新扫描结果逐个覆盖即可。
     */
    fun refreshArtworks() {
        val directoryUri = currentDirectoryUri ?: return
        val folderIds = _uiState.value.allSongs.map { it.getActualFolderId() }
        artworkStates.prepare(folderIds)
        artworkRepository.requestRefresh(directoryUri, folderIds)
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
     *
     * 只有 remote_dl 变化（曲绘文件夹改名）时才需要重扫曲绘：
     * 其余元数据（标题 / BPM / 难度等）不影响曲绘，重扫会白白重建索引并重跑一遍队列。
     *
     * 曲绘状态槽的建立先于列表发布，保证 UI 发布后就能观察到扫描结果。
     */
    fun updateSong(updatedSong: Song) {
        val directoryUri = currentDirectoryUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val originalSong = _uiState.value.allSongs.find { it.id == updatedSong.id }
            val remoteDlChanged = originalSong != null && originalSong.remoteDl != updatedSong.remoteDl

            // 只有 remote_dl 变了才需要重新扫曲绘：文件夹 id 变了，旧的缓存文件对不上
            var rescanFolderIds: List<String>? = null

            if (remoteDlChanged) {
                val oldFolderId = originalSong.getActualFolderId()
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

                // 旧文件夹已经不存在，丢弃它的状态与缓存
                artworkStates.remove(oldFolderId)
                artworkRepository.remove(oldFolderId)

                // 状态槽必须先于列表发布建立，UI 才能观察到结果
                rescanFolderIds = listOf(newFolderId)
                artworkStates.prepare(rescanFolderIds)
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

                // 文件夹改名了才重扫；改标题 / BPM / 难度都不需要重新扫曲绘
                rescanFolderIds?.let {
                    artworkRepository.requestScan(directoryUri, it)
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
                // 曲目已删除，丢弃它的曲绘状态与缓存文件
                artworkStates.remove(folderId)
                artworkRepository.remove(folderId)

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
     * 检测相同 id 的重复条目组
     */
    private fun detectDuplicateGroups(songs: List<Song>): List<DuplicateSongGroup> {
        return songs.withIndex()
            .groupBy { it.value.id }
            .filter { it.value.size > 1 }
            .map { (id, indexed) ->
                DuplicateSongGroup(
                    id = id,
                    entries = indexed.map { DuplicateSongEntry(index = it.index, song = it.value) }
                )
            }
    }

    /**
     * 忽略重复条目提示
     */
    fun dismissDuplicateDialog() {
        _uiState.update { it.copy(showDuplicateDialog = false) }
    }

    /**
     * 删除指定位置的重复条目（仅移除 JSON 条目，不删除乐曲文件夹），完成后刷新列表
     */
    fun deleteDuplicateEntry(index: Int) {
        val directoryUri = currentDirectoryUri ?: return
        _uiState.update { it.copy(showDuplicateDialog = false) }

        viewModelScope.launch {
            val success = songlistRepository.deleteSongEntryAt(directoryUri, index)
            if (success) {
                hasLoaded = false
                loadSongs(directoryUri)
            } else {
                _uiState.update { it.copy(error = "删除重复条目失败") }
            }
        }
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
                    if (uri != null) {
                        jacketUris[folderId] = uri
                    }

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy() }
                    }
                } catch (_: CancellationException) {
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
                    if (uri != null) {
                        jacketUris[folderId] = uri
                    }

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy() }
                    }
                } catch (_: CancellationException) {
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
                    if (uri != null) {
                        jacketUris[folderId] = uri
                    }

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

}
