package aff.importer.tool.data.model

/**
 * 重复的曲目条目（携带其在 songlist 中的位置索引）
 */
data class DuplicateSongEntry(
    val index: Int,
    val song: Song
)

/**
 * 同一 id 的重复条目组
 */
data class DuplicateSongGroup(
    val id: String,
    val entries: List<DuplicateSongEntry>
)

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
    val deletedSongName: String? = null,
    // 重复条目检测
    val duplicateGroups: List<DuplicateSongGroup> = emptyList(),
    val showDuplicateDialog: Boolean = false
)
