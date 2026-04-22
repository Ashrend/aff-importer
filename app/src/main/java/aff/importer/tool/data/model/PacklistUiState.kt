package aff.importer.tool.data.model

/**
 * Packlist 界面状态
 */
data class PacklistUiState(
    val isLoading: Boolean = false,
    val packs: List<Pack> = emptyList(),
    val allPacks: List<Pack> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
    // 选中编辑的曲包
    val selectedPack: Pack? = null,
    // 保存状态
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false
)
