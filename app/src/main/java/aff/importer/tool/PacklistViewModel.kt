package aff.importer.tool

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.PacklistAddResult
import aff.importer.tool.data.PacklistRepository
import aff.importer.tool.data.model.DuplicatePackEntry
import aff.importer.tool.data.model.DuplicatePackGroup
import aff.importer.tool.data.model.Pack
import aff.importer.tool.data.model.PacklistUiState
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
 * Packlist 管理界面的 ViewModel（精简版）
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PacklistViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PacklistViewModel"
        private const val MAX_CONCURRENT_LOADS = 4
    }

    private val packlistRepository = PacklistRepository(application)

    private val _uiState = MutableStateFlow(PacklistUiState())
    val uiState: StateFlow<PacklistUiState> = _uiState.asStateFlow()

    private var currentDirectoryUri: Uri? = null
    private var hasLoaded = false

    // 横幅 URI 缓存（线程安全）
    private val bannerUris = ConcurrentHashMap<String, Uri?>()

    // 正在进行的加载任务（线程安全）
    private val activeLoads = ConcurrentHashMap<String, Job>()
    private var backgroundLoadJob: Job? = null
    private var currentListLoadJob: Job? = null

    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_LOADS)

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

        currentDirectoryUri = directoryUri

        // 清理所有旧任务与缓存
        currentListLoadJob?.cancel()
        backgroundLoadJob?.cancel()
        backgroundLoadJob = null
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        bannerUris.clear()

        _uiState.update { it.copy(isLoading = true, error = null) }

        currentListLoadJob = viewModelScope.launch {
            try {
                val packs = packlistRepository.getAllPacks(directoryUri)
                val duplicateGroups = detectDuplicateGroups(packs)

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

                hasLoaded = true
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
     * 获取曲包横幅 URI
     */
    fun getBannerUri(pack: Pack): Uri? {
        return bannerUris[pack.id]
    }

    /**
     * 更新可见区域 - 由 UI 层在滚动时调用
     */
    fun updateVisibleItems(
        visibleItemIds: List<String>,
        preloadItemIds: List<String> = emptyList()
    ) {
        val directoryUri = currentDirectoryUri ?: return
        val allPacks = _uiState.value.packs

        val packMap = allPacks.associateBy { it.id }

        // 1. 取消不在可见或预加载范围内的任务
        val keepIds = (visibleItemIds + preloadItemIds).toSet()
        activeLoads.keys.toList().forEach { id ->
            if (id !in keepIds) {
                activeLoads.remove(id)?.cancel()
            }
        }

        // 2. 优先加载可见项
        visibleItemIds.forEach { id ->
            val pack = packMap[id] ?: return@forEach

            if (bannerUris.containsKey(pack.id)) return@forEach

            activeLoads[pack.id]?.cancel()

            val job = viewModelScope.launch(loadDispatcher) {
                try {
                    val uri = packlistRepository.getPackBannerUri(directoryUri, pack.id, pack.type)
                    bannerUris[pack.id] = uri

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy() }
                    }
                } catch (e: CancellationException) {
                    // 正常取消，忽略
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load banner for ${pack.id}", e)
                } finally {
                    activeLoads.remove(pack.id)
                }
            }
            activeLoads[pack.id] = job
        }

        // 3. 预加载屏幕外附近的项（低优先级）
        preloadItemIds.forEach { id ->
            if (id in visibleItemIds) return@forEach

            val pack = packMap[id] ?: return@forEach

            if (bannerUris.containsKey(pack.id) || activeLoads.containsKey(pack.id)) return@forEach

            val job = viewModelScope.launch(loadDispatcher) {
                delay(100)

                try {
                    if (!isActive) return@launch

                    val uri = packlistRepository.getPackBannerUri(directoryUri, pack.id, pack.type)
                    bannerUris[pack.id] = uri

                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy() }
                    }
                } catch (e: CancellationException) {
                    // 正常取消
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to preload banner for ${pack.id}", e)
                } finally {
                    activeLoads.remove(pack.id)
                }
            }
            activeLoads[pack.id] = job
        }
    }

    /**
     * 启动后台加载服务
     */
    fun startBackgroundLoading() {
        val directoryUri = currentDirectoryUri ?: return
        val allPacks = _uiState.value.packs

        if (backgroundLoadJob?.isActive == true) return

        backgroundLoadJob = viewModelScope.launch(loadDispatcher) {
            allPacks.forEach { pack ->
                if (bannerUris.containsKey(pack.id) || activeLoads.containsKey(pack.id)) {
                    return@forEach
                }

                try {
                    val uri = packlistRepository.getPackBannerUri(directoryUri, pack.id, pack.type)
                    bannerUris[pack.id] = uri

                    if (bannerUris.size % 10 == 0) {
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy() }
                        }
                    }

                    delay(20)
                } catch (e: Exception) {
                    Log.e(TAG, "Background banner load failed for ${pack.id}", e)
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy() }
            }
        }
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
                bannerUris.remove(packToDelete.id)

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

    /**
     * 强制刷新所有横幅
     */
    fun clearBannerCache() {
        bannerUris.clear()
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        backgroundLoadJob?.cancel()
        backgroundLoadJob = null
        _uiState.update { it.copy() }
    }
}
