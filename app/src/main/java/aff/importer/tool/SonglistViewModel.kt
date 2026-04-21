package aff.importer.tool

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.SonglistRepository
import aff.importer.tool.data.model.Song
import aff.importer.tool.data.model.SonglistUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Songlist 管理界面的 ViewModel
 */
class SonglistViewModel(application: Application) : AndroidViewModel(application) {
    
    private val songlistRepository = SonglistRepository(application)
    
    private val _uiState = MutableStateFlow(SonglistUiState())
    val uiState: StateFlow<SonglistUiState> = _uiState.asStateFlow()
    
    private var currentDirectoryUri: Uri? = null
    
    // 缓存曲绘 URI
    private val jacketUris = mutableMapOf<String, Uri?>()
    
    // 标记是否已加载过
    private var hasLoaded = false
    
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
                
                // 后台预加载曲绘 URI（不阻塞 UI）
                preloadJacketUris(songs, directoryUri)
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
    fun loadJacketUriLazy(songId: String) {
        val directoryUri = currentDirectoryUri ?: return
        if (jacketUris.containsKey(songId)) return // 已缓存
        
        viewModelScope.launch {
            val uri = songlistRepository.getSongJacketUri(directoryUri, songId)
            jacketUris[songId] = uri
            // 通知 UI 更新（可选，如果需要实时更新）
        }
    }
    
    /**
     * 获取曲绘 URI
     */
    fun getJacketUri(songId: String): Uri? {
        return jacketUris[songId]
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
            val success = songlistRepository.deleteSong(directoryUri, songToDelete.id)
            
            if (success) {
                // 从缓存中移除曲绘
                jacketUris.remove(songToDelete.id)
                
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
     * 后台预加载曲绘 URI（分批处理，避免阻塞）
     */
    private fun preloadJacketUris(songs: List<Song>, directoryUri: Uri) {
        viewModelScope.launch {
            // 分批处理，每批 10 个曲目
            val batchSize = 10
            val batches = songs.chunked(batchSize)
            
            batches.forEachIndexed { batchIndex, batch ->
                // 加载这一批的曲绘
                batch.forEach { song ->
                    if (!jacketUris.containsKey(song.id)) {
                        val uri = songlistRepository.getSongJacketUri(directoryUri, song.id)
                        jacketUris[song.id] = uri
                    }
                }
                
                // 每加载完一批，通知 UI 刷新（让已加载的曲绘显示出来）
                _uiState.update { it.copy() } // 触发一次状态更新
                
                // 批次之间稍作延迟，避免卡顿 UI
                if (batchIndex < batches.size - 1) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }
}
