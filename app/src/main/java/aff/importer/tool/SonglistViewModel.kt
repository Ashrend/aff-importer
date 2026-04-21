package aff.importer.tool

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.SonglistRepository
import aff.importer.tool.data.model.Song
import aff.importer.tool.data.model.SonglistUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 曲绘加载优先级
 */
enum class JacketPriority {
    VISIBLE,      // 当前可见，最高优先级
    PRELOAD,      // 预加载（屏幕外附近）
    BACKGROUND    // 后台加载
}

/**
 * 曲绘加载请求
 */
data class JacketLoadRequest(
    val song: Song,
    val priority: JacketPriority,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Songlist 管理界面的 ViewModel
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SonglistViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "SonglistViewModel"
        private const val MAX_CONCURRENT_LOADS = 4  // 最大并发加载数
        private const val PRELOAD_RANGE = 10        // 预加载范围（项数）
    }
    
    private val songlistRepository = SonglistRepository(application)
    
    private val _uiState = MutableStateFlow(SonglistUiState())
    val uiState: StateFlow<SonglistUiState> = _uiState.asStateFlow()
    
    private var currentDirectoryUri: Uri? = null
    
    // 曲绘 URI 缓存
    private val jacketUris = mutableMapOf<String, Uri?>()
    
    // 标记是否已加载过
    private var hasLoaded = false
    
    // 曲绘加载队列和控制器
    private val loadQueue = Channel<JacketLoadRequest>(Channel.UNLIMITED)
    private val activeLoads = mutableMapOf<String, Job>()
    private var loadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOADS)
    
    /**
     * 强制刷新歌曲列表（用于导入后更新）
     */
    fun refreshSongs(directoryUri: Uri?) {
        hasLoaded = false
        loadSongs(directoryUri)
    }
    
    /**
     * 加载歌曲列表（带缓存，避免重复加载）
     */
    fun loadSongs(directoryUri: Uri?) {
        if (directoryUri == null) {
            _uiState.update { it.copy(error = "请先选择目录", isLoading = false) }
            return
        }
        
        // 如果已经加载过且目录未变，直接返回缓存数据
        if (hasLoaded && currentDirectoryUri == directoryUri && _uiState.value.allSongs.isNotEmpty()) {
            return
        }
        
        currentDirectoryUri = directoryUri
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val songs = songlistRepository.getAllSongs(directoryUri)
                
                jacketUris.clear()
                
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allSongs = songs,
                        songs = songs,
                        error = if (songs.isEmpty()) "目录中没有找到曲目" else null
                    )
                }
                
                hasLoaded = true
                
                // 不立即预加载，等待 UI 层通过 updateVisibleItems 触发
                // 这样只有可见区域会优先加载
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
     * 懒加载曲绘 URI（按需加载）
     */
    fun loadJacketUriLazy(song: Song) {
        val directoryUri = currentDirectoryUri ?: return
        val folderId = song.getActualFolderId()
        if (jacketUris.containsKey(folderId)) return // 已缓存
        
        viewModelScope.launch {
            val uri = songlistRepository.getSongJacketUri(directoryUri, folderId)
            jacketUris[folderId] = uri
            // 通知 UI 更新（可选，如果需要实时更新）
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
            // 检查 remote_dl 是否发生变化
            val originalSong = _uiState.value.allSongs.find { it.id == updatedSong.id }
            val remoteDlChanged = originalSong != null && originalSong.remoteDl != updatedSong.remoteDl
            
            // 如果 remote_dl 发生变化，先重命名文件夹
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
                
                // 更新曲绘缓存的键
                jacketUris.remove(oldFolderId)
            }
            
            // 更新 songlist
            val success = songlistRepository.updateSong(directoryUri, updatedSong)
            
            if (success) {
                // 更新本地列表
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
            // 使用原始 songId 从 songlist 中移除，使用 folderId 删除实际文件夹
            val songId = songToDelete.id
            val folderId = songToDelete.getActualFolderId()
            
            val success = songlistRepository.deleteSong(directoryUri, songId, folderId)
            
            if (success) {
                // 从缓存中移除曲绘
                jacketUris.remove(folderId)
                
                // 从本地列表移除
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
    
    /**
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    /**
     * 清除保存成功状态
     */
    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
    
    /**
     * 清除删除成功状态
     */
    fun clearDeleteSuccess() {
        _uiState.update { it.copy(deleteSuccess = false, deletedSongName = null) }
    }
    
    /**
     * 更新可见区域 - 由 UI 层在滚动时调用
     * @param visibleItemIds 当前可见项的 ID 列表
     * @param preloadItemIds 预加载范围内项的 ID 列表（屏幕外附近）
     */
    fun updateVisibleItems(
        visibleItemIds: List<String>,
        preloadItemIds: List<String> = emptyList()
    ) {
        val directoryUri = currentDirectoryUri ?: return
        val allSongs = _uiState.value.songs
        
        // 构建 ID 到 Song 的映射
        val songMap = allSongs.associateBy { it.id }
        
        // 1. 取消不在可见或预加载范围内的任务
        val keepIds = (visibleItemIds + preloadItemIds).toSet()
        activeLoads.keys.toList().forEach { id ->
            if (id !in keepIds) {
                activeLoads.remove(id)?.cancel()
                Log.d(TAG, "Cancelled load for $id (out of range)")
            }
        }
        
        // 2. 优先加载可见项
        visibleItemIds.forEach { id ->
            val song = songMap[id] ?: return@forEach
            val folderId = song.getActualFolderId()
            
            if (jacketUris.containsKey(folderId)) return@forEach
            
            // 如果已经在加载中，取消后重新以高优先级启动
            activeLoads[folderId]?.cancel()
            
            val job = viewModelScope.launch(loadDispatcher) {
                try {
                    val uri = songlistRepository.getSongJacketUri(directoryUri, folderId)
                    jacketUris[folderId] = uri
                    
                    // 主线程更新 UI
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
            if (id in visibleItemIds) return@forEach  // 已在上面处理
            
            val song = songMap[id] ?: return@forEach
            val folderId = song.getActualFolderId()
            
            if (jacketUris.containsKey(folderId)) return@forEach
            if (activeLoads.containsKey(folderId)) return@forEach  // 已在加载中
            
            val job = viewModelScope.launch(loadDispatcher) {
                // 延迟一下，确保可见项先加载
                delay(100)
                
                try {
                    // 检查是否已取消
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
     * 启动后台加载服务 - 加载所有未加载的曲绘
     * 在用户停止滚动后调用
     */
    fun startBackgroundLoading() {
        val directoryUri = currentDirectoryUri ?: return
        val allSongs = _uiState.value.songs
        
        viewModelScope.launch(loadDispatcher) {
            allSongs.forEach { song ->
                val folderId = song.getActualFolderId()
                
                // 跳过已缓存或正在加载的
                if (jacketUris.containsKey(folderId) || activeLoads.containsKey(folderId)) {
                    return@forEach
                }
                
                try {
                    val uri = songlistRepository.getSongJacketUri(directoryUri, folderId)
                    jacketUris[folderId] = uri
                    
                    // 每加载10个通知一次 UI 更新
                    if (jacketUris.size % 10 == 0) {
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy() }
                        }
                    }
                    
                    // 短暂延迟，避免占用过多资源
                    delay(20)
                } catch (e: Exception) {
                    Log.e(TAG, "Background load failed for $folderId", e)
                }
            }
            
            // 最终刷新
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy() }
            }
        }
    }
    
    /**
     * 强制刷新所有曲绘（用于目录变更或手动刷新）
     */
    fun clearJacketCache() {
        jacketUris.clear()
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        _uiState.update { it.copy() }
    }
}
