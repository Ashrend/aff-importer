package aff.importer.tool

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.ArtworkRepository
import aff.importer.tool.data.ArtworkStateStore
import aff.importer.tool.data.PacklistAddResult
import aff.importer.tool.data.PacklistRepository
import aff.importer.tool.data.model.ArtworkKind
import aff.importer.tool.data.model.ArtworkScanState
import aff.importer.tool.data.model.DuplicatePackEntry
import aff.importer.tool.data.model.DuplicatePackGroup
import aff.importer.tool.data.model.Pack
import aff.importer.tool.data.model.PacklistUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Packlist 管理界面的 ViewModel（精简版）
 */
class PacklistViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PacklistViewModel"
    }

    private val packlistRepository = PacklistRepository(application)

    private val _uiState = MutableStateFlow(PacklistUiState())
    val uiState: StateFlow<PacklistUiState> = _uiState.asStateFlow()

    private var currentDirectoryUri: Uri? = null
    private var hasLoaded = false
    private var currentListLoadJob: Job? = null

    /**
     * 封面扫描编排：负责目录索引、优先级队列与应用内缓存落盘。
     * 它内部自带 IO 线程池，ViewModel 只负责发请求，不阻塞主线程。
     */
    private val artworkRepository = ArtworkRepository(application, viewModelScope, ArtworkKind.PACK_BANNER)

    /**
     * 每个曲包一个 Compose 状态：写入某个 id 只会让读取它的那个卡片重组。
     * 状态实例一旦建立就不能替换，否则 UI 观察不到后续更新。
     */
    private val artworkStates = ArtworkStateStore<Uri>()

    /**
     * 封面扫描进度，独立于列表状态，进度变化不会让整个网格重组。
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
     * 加载曲包列表（带缓存）
     */
    fun loadPacks(directoryUri: Uri?) {
        if (directoryUri == null) {
            _uiState.update { it.copy(error = "请先选择目录", isLoading = false) }
            return
        }

        if (hasLoaded && currentDirectoryUri == directoryUri && _uiState.value.allPacks.isNotEmpty()) {
            return
        }

        // 目标目录变了没有：变了就要清掉上一个目录残留的封面值
        val directoryChanged = currentDirectoryUri != directoryUri
        currentDirectoryUri = directoryUri

        if (directoryChanged) {
            // 切换目标目录：旧 Uri 指向的是上一个目录的缓存文件，
            // 继续显示就是错的（新目录里可能是另一张图）。
            // 清值而不是换实例 —— UI 观察的是状态实例，换实例后新图就再也显示不出来。
            artworkStates.clearValues()
        }

        // 只取消上一次未完成的列表加载；封面状态槽要保留，替换实例会让 UI 收不到更新
        currentListLoadJob?.cancel()

        _uiState.update { it.copy(isLoading = true, error = null) }

        currentListLoadJob = viewModelScope.launch {
            try {
                val packs = packlistRepository.getAllPacks(directoryUri)
                val duplicateGroups = detectDuplicateGroups(packs)

                // 封面状态槽必须在列表发布给 UI 之前准备好：
                // UI 读到不存在的状态就没有可观察的对象，之后图片永远不会出现
                val packIds = packs.map { it.id }
                artworkStates.retainOnly(packIds)   // 丢弃旧目录的条目（id 相同的保留实例，值已在切换目录时清空）
                artworkStates.prepare(packIds)      // 为新条目建立状态槽

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allPacks = packs,
                        packs = packs,
                        error = if (packs.isEmpty()) "目录中没有找到 packlist 文件或曲包列表为空" else null,
                        duplicateGroups = duplicateGroups,
                        showDuplicateDialog = duplicateGroups.isNotEmpty()
                    )
                }

                // 列表已经发布给 UI，再请求扫描封面
                artworkRepository.requestScan(directoryUri, packIds)

                hasLoaded = true
            } catch (e: CancellationException) {
                // 协程被取消（ViewModel 销毁、切换目录重新加载）不是加载失败
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load packs", e)
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
     * 强制刷新曲包列表
     */
    fun refreshPacks(directoryUri: Uri?) {
        hasLoaded = false
        loadPacks(directoryUri)
    }

    /**
     * 获取某个曲包的封面 URI。
     *
     * 必须在 composable 调用栈内读取，Compose 才会记录这次读取，
     * 之后写入新值时该卡片才会重组。
     */
    fun artworkUriFor(packId: String): Uri? = artworkStates.valueFor(packId)

    /**
     * 把当前可见的曲包插到扫描队列前面，让可见项先出图
     */
    fun prioritizeArtworks(ids: List<String>) {
        if (ids.isEmpty()) return
        artworkRepository.requestPrioritize(ids)
    }

    /**
     * 强制刷新封面
     *
     * 注意：这里不能调用 artworkStates.clear()，
     * clear() 会丢弃状态实例，而 UI 观察的是实例而不是值，
     * 重建实例后刷新出来的图片就再也显示不出来。
     * 刷新时旧的 Uri 先继续显示，新扫描结果逐个覆盖即可。
     */
    fun refreshArtworks() {
        val directoryUri = currentDirectoryUri ?: return
        val packIds = _uiState.value.allPacks.map { it.id }
        artworkStates.prepare(packIds)
        artworkRepository.requestRefresh(directoryUri, packIds)
    }

    /**
     * 更新搜索关键词
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            val filteredPacks = if (query.isBlank()) {
                state.allPacks
            } else {
                state.allPacks.filter { pack ->
                    pack.id.contains(query, ignoreCase = true) ||
                    pack.getDisplayName().contains(query, ignoreCase = true)
                }
            }
            state.copy(
                searchQuery = query,
                packs = filteredPacks
            )
        }
    }

    /**
     * 选择曲包（打开编辑）
     */
    fun selectPack(pack: Pack?) {
        _uiState.update { it.copy(selectedPack = pack) }
    }

    /**
     * 更新曲包元数据
     */
    fun updatePack(updatedPack: Pack) {
        val directoryUri = currentDirectoryUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val success = packlistRepository.updatePack(directoryUri, updatedPack)

            if (success) {
                _uiState.update { state ->
                    val updatedAllPacks = state.allPacks.map {
                        if (it.id == updatedPack.id) updatedPack else it
                    }
                    val updatedPacks = if (state.searchQuery.isBlank()) {
                        updatedAllPacks
                    } else {
                        updatedAllPacks.filter { pack ->
                            pack.id.contains(state.searchQuery, ignoreCase = true) ||
                            pack.getDisplayName().contains(state.searchQuery, ignoreCase = true)
                        }
                    }

                    state.copy(
                        isSaving = false,
                        saveSuccess = true,
                        allPacks = updatedAllPacks,
                        packs = updatedPacks,
                        selectedPack = null
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
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 显示删除确认对话框
     */
    fun showDeleteConfirm(pack: Pack) {
        _uiState.update {
            it.copy(showDeleteConfirm = true, packToDelete = pack)
        }
    }

    /**
     * 取消删除
     */
    fun dismissDeleteConfirm() {
        _uiState.update {
            it.copy(showDeleteConfirm = false, packToDelete = null)
        }
    }

    /**
     * 确认删除
     */
    fun confirmDelete() {
        val packToDelete = _uiState.value.packToDelete ?: return
        val directoryUri = currentDirectoryUri ?: return

        _uiState.update { it.copy(showDeleteConfirm = false) }

        viewModelScope.launch {
            val success = packlistRepository.deletePack(directoryUri, packToDelete.id)

            if (success) {
                // 曲包已删除，丢弃它的封面状态与缓存文件
                artworkStates.remove(packToDelete.id)
                artworkRepository.remove(packToDelete.id)

                _uiState.update { state ->
                    val updatedAllPacks = state.allPacks.filter { it.id != packToDelete.id }
                    val updatedPacks = if (state.searchQuery.isBlank()) {
                        updatedAllPacks
                    } else {
                        updatedAllPacks.filter { pack ->
                            pack.id.contains(state.searchQuery, ignoreCase = true) ||
                            pack.getDisplayName().contains(state.searchQuery, ignoreCase = true)
                        }
                    }

                    state.copy(
                        allPacks = updatedAllPacks,
                        packs = updatedPacks,
                        packToDelete = null,
                        deleteSuccess = true,
                        deletedPackName = packToDelete.getDisplayName()
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        error = "删除失败: ${packToDelete.id}",
                        packToDelete = null
                    )
                }
            }
        }
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
        _uiState.update { it.copy(deleteSuccess = false, deletedPackName = null) }
    }

    /**
     * 显示新建曲包对话框
     */
    fun showCreatePack() {
        _uiState.update { it.copy(showCreateDialog = true, saveSuccess = false) }
    }

    /**
     * 取消新建曲包
     */
    fun dismissCreatePack() {
        _uiState.update { it.copy(showCreateDialog = false) }
    }

    /**
     * 创建新曲包（id 已存在时阻止创建并提示）
     */
    fun createPack(newPack: Pack) {
        val directoryUri = currentDirectoryUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            when (packlistRepository.addPack(directoryUri, newPack)) {
                PacklistAddResult.Added -> {
                    hasLoaded = false
                    loadPacks(directoryUri)
                    _uiState.update { it.copy(isSaving = false, showCreateDialog = false) }
                }
                PacklistAddResult.DuplicateId -> {
                    // 保持对话框打开，提示用户更换 ID
                    _uiState.update {
                        it.copy(isSaving = false, error = "曲包 ID 已存在，请更换 ID 后重试")
                    }
                }
                PacklistAddResult.Failed -> {
                    _uiState.update { it.copy(isSaving = false, error = "创建失败") }
                }
            }
        }
    }

    /**
     * 检测相同 id 的重复条目组
     */
    private fun detectDuplicateGroups(packs: List<Pack>): List<DuplicatePackGroup> {
        return packs.withIndex()
            .groupBy { it.value.id }
            .filter { it.value.size > 1 }
            .map { (id, indexed) ->
                DuplicatePackGroup(
                    id = id,
                    entries = indexed.map { DuplicatePackEntry(index = it.index, pack = it.value) }
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
     * 删除指定位置的重复条目（仅移除 JSON 条目），完成后刷新列表
     */
    fun deleteDuplicateEntry(index: Int) {
        val directoryUri = currentDirectoryUri ?: return
        _uiState.update { it.copy(showDuplicateDialog = false) }

        viewModelScope.launch {
            val success = packlistRepository.deletePackEntryAt(directoryUri, index)
            if (success) {
                hasLoaded = false
                loadPacks(directoryUri)
            } else {
                _uiState.update { it.copy(error = "删除重复条目失败") }
            }
        }
    }

}
