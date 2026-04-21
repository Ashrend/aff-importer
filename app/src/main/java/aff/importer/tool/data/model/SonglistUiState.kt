package aff.importer.tool.data.model

/**
 * Songlist 界面状态
 */
data class SonglistUiState(
    val isLoading: Boolean = false,
    val songs: List<Song> = emptyList(),
    val allSongs: List<Song> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
    // 选中编辑的歌曲
    val selectedSong: Song? = null,
    // 删除确认
    val showDeleteConfirm: Boolean = false,
    val songToDelete: Song? = null,
    // 保存状态
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    // 删除状态
    val deleteSuccess: Boolean = false,
    val deletedSongName: String? = null
)
