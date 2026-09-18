package aff.importer.tool.data.model

/**
 * 重复的曲包条目（携带其在 packlist 中的位置索引）
 */
data class DuplicatePackEntry(
    val index: Int,
    val pack: Pack
)

/**
 * 同一 id 的重复条目组
 */
data class DuplicatePackGroup(
    val id: String,
    val entries: List<DuplicatePackEntry>
)

/**
 * Packlist 界面状态
 */
data class PacklistUiState(
    val isLoading: Boolean = false,
    val packs: List<Pack> = emptyList(),
    val allPacks: List<Pack> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
    val selectedPack: Pack? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val packToDelete: Pack? = null,
    val deleteSuccess: Boolean = false,
    val deletedPackName: String? = null,
    val showCreateDialog: Boolean = false,
    // 重复条目检测
    val duplicateGroups: List<DuplicatePackGroup> = emptyList(),
    val showDuplicateDialog: Boolean = false
)
