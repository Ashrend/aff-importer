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
    val selectedPack: Pack? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val packToDelete: Pack? = null,
    val deleteSuccess: Boolean = false,
    val deletedPackName: String? = null
)
