package aff.importer.tool.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import aff.importer.tool.data.model.ArtworkCacheLocation
import aff.importer.tool.data.model.ArtworkKind
import aff.importer.tool.data.model.ArtworkScanState
import aff.importer.tool.data.model.ArtworkScanTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 索引里的一个 SAF 子文档。
 *
 * @param treeUri 所属的树 Uri（用户授权的目标目录）
 * @param documentId 该子文档在树中的 document id
 * @param name 显示名；只有文件条目才有（目录条目为 null），用于日志里的扩展名检查
 * @param parentLabel 父级标签，用于日志（如 pack、pack/1080）；没有时为 null
 */
private data class IndexedDocument(
    val treeUri: Uri,
    val documentId: String,
    val name: String? = null,
    val parentLabel: String? = null
) {
    /** 子文档自身的 Uri：content://<authority>/tree/<treeId>/document/<documentId> */
    val uri: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /** 子文档的子文档列表 Uri（列举这一层只需要一次查询） */
    val childrenUri: Uri
        get() = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
}

/**
 * 一次查询拿到的子文档信息。
 *
 * @param documentId 子文档 id
 * @param name 显示名，可能为 null（提供方没给）
 * @param mimeType MIME 类型，可能为 null（提供方没给）
 */
private data class ChildDocument(
    val documentId: String,
    val name: String?,
    val mimeType: String?
)

/**
 * 单个条目的图片解析结果
 *
 * @param kind 图片种类（曲绘 / 曲包封面）
 * @param id 条目 id：曲目为实际文件夹 id（getActualFolderId），曲包为 pack id
 * @param uri 图片 Uri：应用内缓存文件为 file://，SAF 源文件为 content://，没有源文件时为 null
 */
data class ArtworkResult(val kind: ArtworkKind, val id: String, val uri: Uri?)

/**
 * 一个已认领的扫描任务：条目 id + 扫描代次 + 钉住的缓存位置 + 索引快照。
 *
 * 索引类型是本文件的私有类型，所以这里用私有别名收一下。
 */
private typealias ScanTask = ArtworkScanTask<Map<String, IndexedDocument>>

/**
 * 图片扫描编排。
 *
 * 职责：
 * 1. 把 SAF 目录结构整理成「id → 文件」的索引，避免每个条目都去遍历根目录；
 * 2. 用优先级队列 + 常驻 worker 并发解析条目，可见项可以插队先出图；
 * 3. 解析结果落到应用内缓存（[ArtworkCache]），再以 file:// 交给 Coil 读取；
 * 4. 用 Channel 发布结果，由调用方串行消费，避免丢结果。
 *
 * SAF 访问方式：全部走 [android.content.ContentResolver] 的投影查询，
 * 一次查询同时拿到 document id、显示名与 MIME 类型。
 * 不用 [androidx.documentfile.provider.DocumentFile] 是因为它的 getName()/isDirectory()
 * 各自都会再发一次 ContentResolver 查询（旧实现因此是 2N+1 次）。
 *
 * 线程约定：
 * - 队列、索引、当前目录、扫描代次、进度计数等共享状态，全部由 [mutex] 保护；
 * - [ArtworkPriorityQueue] 与 [ArtworkScanBinding] 自身不加锁，锁由本类负责；
 * - SAF 查询是阻塞 IO，只允许出现在 worker 线程与 [buildIndex] 里，且 [buildIndex] 在锁外调用。
 *
 * 缓存位置必须「钉住」：
 * 索引与缓存位置一起发布（[ArtworkScanBinding.publish]），worker 认领任务时一起取走
 * （[ArtworkScanBinding.claim]）。之后不管环境里的「当前目录」怎么变，
 * 这个任务都只对着自己带走的位置读写缓存 —— 否则切换目录时，
 * 正在解析的旧任务会把上一个目录的曲绘写进下一个目录的缓存目录，且是永久性的。
 *
 * @param context 应用上下文（内部只使用 applicationContext）
 * @param scope worker 与请求协程所属的作用域，ViewModel 销毁时随之取消
 * @param kind 图片种类，决定索引形状与目标尺寸
 * @param onWarning 告警回调：把诊断信息（如扩展名与实际格式不符）送到应用的日志显示。
 *  由 ViewModel 接到对应仓库的 emitLog 上；不接也不影响图片显示。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtworkRepository(
    context: Context,
    scope: CoroutineScope,
    val kind: ArtworkKind,
    private val onWarning: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "ArtworkRepository"

        /** 并发解析的 worker 数量 */
        private const val MAX_WORKERS = 4

        /** 队列为空时 worker 的等待间隔（毫秒） */
        private const val IDLE_WAIT_MS = 50L

        /** 进度发布的最小间隔（毫秒），避免进度条高频刷新 */
        private const val PROGRESS_INTERVAL_MS = 200L

        /** 曲绘目标尺寸（正方形） */
        private const val SONG_JACKET_SIZE = 256

        /** 曲包封面目标尺寸（横幅，不是正方形） */
        private const val PACK_BANNER_WIDTH = 512
        private const val PACK_BANNER_HEIGHT = 256

        /** 曲包封面所在目录名 */
        private const val PACK_FOLDER_NAME = "pack"

        /** append 类型曲包分隔图所在目录名（pack/1080/） */
        private const val PACK_DIVIDER_FOLDER_NAME = "1080"

        /**
         * 子文档查询的投影：一次拿到 id、显示名与 MIME 类型。
         * 不做这一步的话，每个子文档都要单独查一次属性。
         */
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
    }

    private val appContext: Context = context.applicationContext

    /** worker 与请求协程所属的作用域 */
    private val jobScope: CoroutineScope = scope

    /**
     * 应用内缓存文件层。
     * 它自己按「目录标识 + 代次」组织文件（[ArtworkCachePaths.directoryKey]），
     * 与本类的扫描代次是两回事。
     */
    private val cache = ArtworkCache(appContext, kind.namespace)

    /** 待解析队列；自身不加锁，所有读写都在 [mutex] 内 */
    private val queue = ArtworkPriorityQueue()

    /**
     * 当前发布的「索引 + 缓存位置」。
     *
     * 两者必须整体发布、整体认领：索引决定「这个 id 对应哪个 SAF 文件」，
     * 缓存位置决定「这个文件写到哪个缓存目录」，错配就会串图。
     * 自身不加锁，所有读写都在 [mutex] 内。
     */
    private val binding = ArtworkScanBinding<Map<String, IndexedDocument>>()

    /** 保护 queue / binding / currentDirectoryUri / generation / total / completed 的唯一一把锁 */
    private val mutex = Mutex()

    /**
     * 扫描代次：目录切换或强制刷新时自增。
     * worker 解析完成后要再比对一次，代次变了就丢弃结果，
     * 避免把旧目录 / 旧缓存代次的 Uri 发到新状态上。
     */
    private var generation = 0

    /** 当前扫描的目录 */
    private var currentDirectoryUri: Uri? = null

    /** 本批次的条目总数 */
    private var total = 0

    /** 本批次已发布的结果数 */
    private var completed = 0

    /** 上次发布进度的时刻（毫秒），用于节流 */
    private var lastProgressAt = 0L

    /** 是否需要在扫描完成后清理旧缓存代次目录（强制刷新 / 目录切换时置位，清理后清除；只清理当前目录标识下的） */
    private var purgePending = false

    /** 待清理的目录标识：清理时只动这个目录标识下的其它代次目录 */
    private var pendingPurgeDirKey: String? = null

    /** 结果通道：UNLIMITED 保证结果不丢，由调用方串行消费 */
    private val _results = Channel<ArtworkResult>(Channel.UNLIMITED)

    /** 解析结果流 */
    val results: Flow<ArtworkResult> = _results.receiveAsFlow()

    private val _scanState = MutableStateFlow<ArtworkScanState>(ArtworkScanState.Idle)

    /** 扫描进度，独立于列表状态，不触发列表重组 */
    val scanState: StateFlow<ArtworkScanState> = _scanState.asStateFlow()

    /** worker 调度器：IO + 并发上限 */
    private val workerDispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(MAX_WORKERS)

    init {
        // 常驻 worker 协程：队列有任务就解析，空则短暂等待，不会自行退出。
        // 启动 MAX_WORKERS 个，limitedParallelism 的并发上限才有意义。
        repeat(MAX_WORKERS) {
            jobScope.launch(workerDispatcher) { workerLoop() }
        }
    }

    /**
     * 请求扫描一批条目。
     *
     * @param directoryUri 目标目录
     * @param ids 待解析的条目 id：曲目为实际文件夹 id，曲包为 pack id
     * @param sourceHints 条目的附加信息（id → 值）：曲包传曲包类型，用来决定封面来源。
     *  曲目没有这类信息，传空表即可。
     */
    fun requestScan(
        directoryUri: Uri,
        ids: List<String>,
        sourceHints: Map<String, String> = emptyMap()
    ) {
        // SAF 查询是阻塞 IO，不能占用调用方线程（ViewModel 的 scope 跑在主线程）
        jobScope.launch(Dispatchers.IO) {
            performScan(directoryUri, ids, sourceHints, purgeAfterScan = false)
        }
    }

    /**
     * 强制刷新：换缓存代次 → 全量重扫 → 扫描完成后清理旧代次目录。
     *
     * 代次是「同一个目录内」的刷新机制，所以必须先把目录标识落到缓存上，
     * 再推进代次 —— 否则刷新的是另一个目录时，推进的会是当前目录的代次。
     *
     * @param directoryUri 目标目录
     * @param ids 待解析的条目 id
     * @param sourceHints 条目的附加信息（id → 值），含义同 [requestScan]
     */
    fun requestRefresh(
        directoryUri: Uri,
        ids: List<String>,
        sourceHints: Map<String, String> = emptyMap()
    ) {
        jobScope.launch(Dispatchers.IO) {
            val dirKey = ArtworkCachePaths.directoryKey(directoryUri.toString())

            // 先绑定目录标识：换代次推进的是「这个目录标识」的代次
            cache.useDirectory(dirKey)

            // 换代次 → 落到新的缓存目录 → Coil 的缓存键随之改变，必然重新加载。
            //
            // 换代次与「扫描发布缓存位置」在同一个临界区里完成：
            // 这样扫描发布出来的位置一定是最新的代次，
            // 不会出现「刷新已经换代次了，扫描却还往旧代次里写」。
            mutex.withLock {
                cache.advanceGeneration(dirKey)
                // 作废进行中的旧结果：旧缓存代次的 Uri 不能覆盖新代次的结果
                generation++

                // 清空待解析队列：换代次与「扫描发布缓存位置」之间存在一个窗口，
                // 这期间被 worker 认领的旧任务会拿到「旧的缓存位置 + 新的扫描代次」，
                // 解析完的代次校验就会通过，于是把图写进即将被清理的旧代次目录，
                // 发出去的 file:// Uri 指向一个会被删掉的目录（卡片变空白）。
                // 刷新本来就会把所有 id 重新入队，所以清空队列不会漏掉任何条目。
                queue.clear()

                // 本批次重新计数：队列清空后到新批次发布之间，
                // 若沿用上一批的计数会被误判成「已完成」
                completed = 0
            }

            performScan(directoryUri, ids, sourceHints, purgeAfterScan = true)
        }
    }

    /**
     * 把仍在队列中的 id 前插，让当前可见的条目先出图。
     * 不取消任何已排队的任务。
     *
     * 前插是 O(N) 的过滤 + 重建（最多两千多项），滚动时每次可见区间变化都会调用，
     * 因此放到 Default 调度器上跑，绝不能占着主线程。
     */
    fun requestPrioritize(ids: List<String>) {
        if (ids.isEmpty()) return
        jobScope.launch(Dispatchers.Default) {
            mutex.withLock { queue.prioritize(ids) }
        }
    }

    /**
     * 删除某个条目的缓存文件。
     *
     * 非 suspend：ViewModel 在主线程调用。
     * [ArtworkCache.delete] 是同步文件 IO，因此发射到 IO 线程执行，调用后立即返回。
     *
     * 删除的位置取「当前发布的那一批」：条目属于当前列表，而列表对应当前这次扫描。
     * 还没发布过任何批次时退回到环境里的当前目录。
     *
     * 不需要从队列里移除该 id：被删除的曲目文件夹已不存在，解析结果自然为 null，
     * 而发布时对应的状态条目已被 ViewModel 移除，写入是空操作。
     */
    fun remove(id: String) {
        jobScope.launch(Dispatchers.IO) {
            val location = mutex.withLock { binding.location() } ?: cache.currentLocation()
            location?.let { cache.delete(it, id) }
        }
    }

    /**
     * 执行一次扫描：重建索引、重置进度、入队。
     *
     * 目录隔离由缓存路径里的目录标识负责（cacheDir/artwork/<namespace>/<dirKey>/gen-<N>/…），
     * 所以切换目录**不需要**换代次：目录标识一变，缓存路径就变，两个目录的缓存互不相干。
     * 代次只在同一个目录内用于强制刷新。
     *
     * @param purgeAfterScan 本批次完成后是否清理旧缓存代次目录
     * @param sourceHints 条目的附加信息（id → 值），曲包传曲包类型
     */
    private suspend fun performScan(
        directoryUri: Uri,
        ids: List<String>,
        sourceHints: Map<String, String>,
        purgeAfterScan: Boolean
    ) {
        // 1) 先把目录标识落到缓存上（锁外）：
        //    缓存路径带目录维度，不同目标目录天然隔离；换到已用过的目录则直接复用它的热缓存。
        val dirKey = ArtworkCachePaths.directoryKey(directoryUri.toString())
        cache.useDirectory(dirKey)

        // 2) 目录切换检测（锁内）：作废进行中的旧结果，并清空旧队列
        mutex.withLock {
            if (directoryUri != currentDirectoryUri) {
                currentDirectoryUri = directoryUri
                generation++
                queue.clear()

                // 只清理当前目录标识下的其它代次目录；
                // 别的目录标识属于别的目标目录，要留着（切回旧目录时直接复用）
                purgePending = true
                pendingPurgeDirKey = dirKey
            }
        }

        // 3) 索引在锁外构建：SAF 查询是阻塞 IO，放在锁里会让所有 worker
        //    和主线程发来的优先级请求一起排队
        val newIndex = buildIndex(directoryUri, sourceHints)

        // 4) 在锁内发布索引、缓存位置并入队
        mutex.withLock {
            // 索引每次都重建（不只是目录变化时）：
            // remote_dl 改名会新增一个文件夹，目录没变但旧索引里没有这个 folderId，
            // 重建只需 1~2 次 SAF 查询，代价可忽略，换来「任何时候都能解析到当前实际存在的条目」。
            //
            // 索引与缓存位置一起发布：worker 认领任务时读到的是配套的一对，
            // 不会出现「用 A 目录的索引解析、写进 B 目录的缓存」。
            //
            // 位置在这里现取（目录标识已在步骤 1 绑定过，这里只是查一下该用哪一代）：
            // 换代次与发布互斥，现取到的就是最新代次。
            binding.publish(newIndex, cache.locationOf(dirKey))

            // 进度按批次计算，不是累计
            total = ids.size
            completed = 0
            lastProgressAt = 0L
            if (purgeAfterScan) {
                // 标记：本次扫描完成后清理旧缓存代次目录
                purgePending = true
                pendingPurgeDirKey = dirKey
            }
            if (ids.isEmpty()) {
                // 空批次：没有条目可解析，直接进入终态。
                // 否则进度会永远停在「正在加载 0/0」——因为完成判定要求 total > 0。
                _scanState.value = ArtworkScanState.Completed(0)
            } else {
                _scanState.value = ArtworkScanState.Scanning(0, total)
            }

            queue.enqueue(ids)
        }

        // 空批次不会走到 worker 的完成判定（那里要求 total > 0），
        // 所以挂起的旧代次清理要在这里触发，否则刷新后的旧目录会一直占着空间。
        if (ids.isEmpty()) {
            runPendingPurge()
        }
    }

    /**
     * 构建当前目录的图片索引。
     *
     * SONG_JACKET：key = 文件夹名（= 曲目的实际文件夹 id），value = 该曲目文件夹。
     * PACK_BANNER：key = pack id，value = 选中的封面文件。
     *
     * 每次构建只发 1 次（曲绘）或 3 次（曲包）SAF 查询。
     *
     * @param sourceHints 条目的附加信息（id → 值）：曲包传曲包类型，决定封面来源
     */
    private suspend fun buildIndex(
        directoryUri: Uri,
        sourceHints: Map<String, String>
    ): Map<String, IndexedDocument> {
        return try {
            when (kind) {
                ArtworkKind.SONG_JACKET -> buildSongFolderIndex(directoryUri)
                ArtworkKind.PACK_BANNER -> buildPackBannerIndex(directoryUri, sourceHints)
            }
        } catch (e: CancellationException) {
            // 协程被取消不是错误，直接向上抛，别把扫描吞成「索引为空」
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "构建图片索引失败: $directoryUri", e)
            emptyMap()
        }
    }

    /**
     * 曲绘索引：一次列举根目录，得到「文件夹名 → 文件夹」。
     * 旧实现每个条目都要重新列举整个根目录，是 O(N²) 的 SAF 查询。
     */
    private suspend fun buildSongFolderIndex(directoryUri: Uri): Map<String, IndexedDocument> {
        val children = queryChildren(rootChildrenUri(directoryUri))

        val map = LinkedHashMap<String, IndexedDocument>()
        for (child in children) {
            // 只保留目录；MIME 类型不是目录的一律跳过
            if (child.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) continue
            // 名字为空的条目没法当索引键，跳过
            val name = child.name ?: continue
            map[name] = IndexedDocument(directoryUri, child.documentId)
        }
        return map
    }

    /**
     * 曲包封面索引：所有封面都在 pack 目录里，列举一次即可解析出全部 pack id。
     * 旧实现每个曲包都重新查找 pack 目录，是 O(P²) 的 SAF 查询。
     *
     * 候选来源有三种：
     * - `pack/1080/divider_&lt;id&gt;.&lt;ext&gt;`：append 类型曲包专用（分隔图）；
     * - `pack/1080_select_&lt;id&gt;.&lt;ext&gt;` 与 `pack/select_&lt;id&gt;.&lt;ext&gt;`：标准曲包。
     *
     * append 类型曲包只要 `pack/1080/` 目录存在，就只认分隔图：
     * 找不到分隔图就没有封面，不退回标准候选（免得显示成别的曲包的图）。
     *
     * 选谁由曲包类型决定，类型是「每个 pack id 一份」的，
     * 所以要由调用方通过 sourceHints 带进来。
     */
    private suspend fun buildPackBannerIndex(
        directoryUri: Uri,
        packTypes: Map<String, String>
    ): Map<String, IndexedDocument> {
        // 第一次查询：根目录里找 pack 目录
        val packFolder = queryChildren(rootChildrenUri(directoryUri)).firstOrNull {
            it.name == PACK_FOLDER_NAME && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: return emptyMap()

        // 第二次查询：pack 目录下的文件（标准候选）与 pack/1080/ 子目录
        val packChildren = queryChildren(IndexedDocument(directoryUri, packFolder.documentId).childrenUri)

        // pack/1080/ 目录：append 类型曲包的分隔图在这里
        val dividerFolder = packChildren.firstOrNull {
            it.name == PACK_DIVIDER_FOLDER_NAME && it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        }
        // 第三次查询：pack/1080/ 目录下的分隔图
        val dividerChildren = dividerFolder?.let {
            queryChildren(IndexedDocument(directoryUri, it.documentId).childrenUri)
        } ?: emptyList()

        // 候选文件：曲包 id → (候选文件名 → 文件)
        val standardFiles = groupPackCandidates(directoryUri, packChildren, PACK_FOLDER_NAME) { candidate ->
            candidate.kind != ArtworkFileRules.PackBannerKind.DIVIDER
        }
        val dividerFiles = groupPackCandidates(directoryUri, dividerChildren, "$PACK_FOLDER_NAME/$PACK_DIVIDER_FOLDER_NAME") { candidate ->
            candidate.kind == ArtworkFileRules.PackBannerKind.DIVIDER
        }

        val map = LinkedHashMap<String, IndexedDocument>()
        val packIds = LinkedHashSet<String>()
        packIds.addAll(standardFiles.keys)
        packIds.addAll(dividerFiles.keys)
        for (packId in packIds) {
            val selection = ArtworkFileRules.selectPackBanner(
                packId = packId,
                packType = packTypes[packId],
                hasDividerFolder = dividerFolder != null,
                standardNames = standardFiles[packId]?.keys ?: emptySet(),
                dividerNames = dividerFiles[packId]?.keys ?: emptySet()
            ) ?: continue
            val file = when (selection.source) {
                ArtworkFileRules.PackBannerSource.STANDARD ->
                    standardFiles[packId]?.get(selection.fileName)
                ArtworkFileRules.PackBannerSource.DIVIDER ->
                    dividerFiles[packId]?.get(selection.fileName)
            } ?: continue
            map[packId] = file
        }
        return map
    }

    /**
     * 把一层子文档里的曲包封面候选按「曲包 id → (候选文件名 → 文件)」归类。
     *
     * @param treeUri 所属的树 Uri
     * @param children 这一层的子文档
     * @param parentLabel 父级标签，用于日志（如 pack、pack/1080）
     * @param accept 只接受满足条件的候选（目录一律排除）
     */
    private fun groupPackCandidates(
        treeUri: Uri,
        children: List<ChildDocument>,
        parentLabel: String,
        accept: (ArtworkFileRules.PackBannerCandidate) -> Boolean
    ): Map<String, Map<String, IndexedDocument>> {
        val result = LinkedHashMap<String, MutableMap<String, IndexedDocument>>()
        for (child in children) {
            // 子目录不可能是封面
            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
            val name = child.name ?: continue
            val candidate = ArtworkFileRules.parsePackBannerCandidate(name) ?: continue
            if (!accept(candidate)) continue
            result.getOrPut(candidate.packId) { LinkedHashMap() }[name] =
                IndexedDocument(treeUri, child.documentId, name = name, parentLabel = parentLabel)
        }
        return result
    }

    /**
     * worker 主循环：取任务 → 解析 → 校验代次 → 发布结果。
     */
    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            // 取任务、代次、索引与缓存位置必须在同一个临界区完成：
            // 若分几次加锁，中间发生目录切换就会读到新的索引与位置，
            // 旧任务就可能把上一个目录的曲绘写进下一个目录的缓存目录。
            val claimed = mutex.withLock {
                val id = queue.poll() ?: return@withLock null
                val task = binding.claim(id, generation)
                if (task == null) {
                    // 理论上不会发生：入队与位置发布在同一个临界区里完成。
                    // 真发生了也没有安全的落盘目标（没有位置），只能放弃这个条目。
                    completed++
                }
                task
            }
            if (claimed == null) {
                // 队列空：可能本批次已经跑完，顺带更新完成状态
                markCompletedIfFinished()
                delay(IDLE_WAIT_MS)
                continue
            }

            // SAF 解析：worker 跑在 IO 线程上
            // 任务自带缓存位置：解析期间即使目录切换了，写入目标也不会变
            val uri = resolveOne(claimed)

            // 解析期间目录可能已切换（或已强制刷新）：代次变了就丢弃结果，
            // 绝不能把旧目录 / 旧缓存代次的 Uri 发出去
            val stillValid = mutex.withLock { generation == claimed.generation }
            if (!stillValid) {
                continue
            }

            // 结果进 Channel，由调用方串行消费（UNLIMITED，不会丢）
            _results.send(ArtworkResult(kind, claimed.id, uri))

            mutex.withLock { completed++ }
            publishProgress()
        }
    }

    /**
     * 解析单个条目的图片。
     *
     * 缓存的读与写都用任务自带的 location：
     * 环境里的「当前目录」随时可能因为用户切换目录而改变，不能拿来算落盘目标。
     *
     * @return 图片 Uri；没有源文件时为 null
     */
    private suspend fun resolveOne(task: ScanTask): Uri? {
        return try {
            val cachedUri = cachedUriOf(task.location, task.id)
            if (cachedUri != null) {
                // 缓存命中：直接给应用内缓存文件的 Uri，完全跳过 SAF 查询
                cachedUri
            } else {
                when (kind) {
                    ArtworkKind.SONG_JACKET -> resolveSongJacket(task.id, task.location, task.index)
                    ArtworkKind.PACK_BANNER -> resolvePackBanner(task.id, task.location, task.index)
                }
            }
        } catch (e: CancellationException) {
            // 协程被取消（ViewModel 销毁等）不是错误，直接向上抛，让 worker 正常结束
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解析图片失败: $kind/${task.id}", e)
            null
        }
    }

    /**
     * 缓存命中时返回应用内缓存文件的 Uri；未命中返回 null。
     *
     * @param location 任务钉住的位置：只看这个位置下的缓存文件
     */
    private fun cachedUriOf(location: ArtworkCacheLocation, id: String): Uri? {
        val file = cache.fileFor(location, id)
        return if (file.isFile) Uri.fromFile(file) else null
    }

    /**
     * 解析曲绘：索引命中 → 列出曲目文件夹 → 选曲绘文件 → 落盘到应用内缓存。
     */
    private suspend fun resolveSongJacket(
        id: String,
        location: ArtworkCacheLocation,
        indexSnapshot: Map<String, IndexedDocument>
    ): Uri? {
        // 没有这个文件夹 → 该曲目不存在（可能已被删除或改名）
        val folder = indexSnapshot[id] ?: return null

        // 一次查询拿到这个曲目文件夹下的全部文件（子目录不算图片）
        val children = queryChildren(folder.childrenUri)
            .filter { it.mimeType != DocumentsContract.Document.MIME_TYPE_DIR }
        val jacket = ArtworkFileRules.selectSongJacket(children.mapNotNull { it.name }) ?: return null
        val file = children.firstOrNull { it.name == jacket.fileName } ?: return null
        val fileUri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, file.documentId)

        // 格式不符只告警，不影响选中的文件（图照样显示）
        checkImageFormat(fileUri, jacket.fileName, id)

        // 只有 256px 的 JPEG 能按字节复制；其余（含 PNG 缩略图）都要解码后转码成 JPEG。
        // 落盘目标是任务自己的位置，绝不会写到别的目录标识下
        val cached = if (jacket.isThumbnail) {
            cache.copyToCache(location, id, fileUri)
        } else {
            cache.transcodeToCache(location, id, fileUri, SONG_JACKET_SIZE, SONG_JACKET_SIZE)
        }
        // 落盘失败时回退到 SAF 的 content:// Uri：图片仍能显示，只是读取慢一些
        return cached?.let { Uri.fromFile(it) } ?: fileUri
    }

    /**
     * 解析曲包封面：索引命中 → 转码到应用内缓存。
     *
     * 索引里存的已经是选中的源文件（append 类型曲包是 pack/1080/ 下的分隔图，
     * 其余是 pack/ 下的标准候选），这里只负责落盘。
     */
    private suspend fun resolvePackBanner(
        id: String,
        location: ArtworkCacheLocation,
        indexSnapshot: Map<String, IndexedDocument>
    ): Uri? {
        val file = indexSnapshot[id] ?: return null
        val fileUri = file.uri

        // 格式不符只告警，不影响封面显示
        file.name?.let { checkImageFormat(fileUri, it, file.parentLabel) }

        // 封面是横幅，按 512x256 转码；落盘目标是任务自己的位置
        val cached = cache.transcodeToCache(location, id, fileUri, PACK_BANNER_WIDTH, PACK_BANNER_HEIGHT)
        // 落盘失败时回退到 SAF 的 content:// Uri
        return cached?.let { Uri.fromFile(it) } ?: fileUri
    }

    /**
     * 检查图片文件的扩展名与实际格式是否一致，不一致就发一条告警。
     *
     * 只读文件头（[BitmapFactory.Options.inJustDecodeBounds]），不解码像素。
     *
     * **只诊断**：绝不改变选中的文件，也绝不会让图片消失 ——
     * 格式不符照样解析、照样显示，只在日志里留个警告。
     */
    private suspend fun checkImageFormat(uri: Uri, fileName: String, parentLabel: String?) {
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                // 解不出文件头就没有实际格式可比，不告警
                val actualMime = bounds.outMimeType ?: return@withContext

                val ext = ArtworkFileRules.extensionOf(fileName)
                val expectedMime = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    // 其它扩展名没有约定，不告警
                    else -> return@withContext
                }
                if (actualMime == expectedMime) return@withContext

                val path = if (parentLabel != null) "$parentLabel/$fileName" else fileName
                val message = "发现扩展名与实际格式不符: $path (扩展名=$ext, 实际=$actualMime)"
                Log.w(TAG, message)
                onWarning(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 检查失败不影响显示：它只是诊断，不是准入条件
                Log.w(TAG, "检查图片格式失败: $fileName", e)
            }
        }
    }

    /**
     * 树根目录下的子文档列表 Uri。
     */
    private fun rootChildrenUri(treeUri: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

    /**
     * 列举一层子文档：一次 ContentResolver 查询，同时拿到 id、显示名与 MIME 类型。
     *
     * 旧实现用 DocumentFile：listFiles() 只查 document_id，
     * 之后每个子文档的 getName() 与 isDirectory() 各自再发一次查询，2N+1 次。
     * 这里一次投影查询就把属性全部拿到。
     */
    private fun queryChildren(childrenUri: Uri): List<ChildDocument> {
        val children = ArrayList<ChildDocument>()
        try {
            appContext.contentResolver.query(
                childrenUri,
                CHILD_PROJECTION,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                // 没有 id 就没法定位文档，整批放弃
                if (idIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idIndex) ?: continue
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    children.add(ChildDocument(documentId, name, mimeType))
                }
            }
        } catch (e: CancellationException) {
            // 协程被取消不是错误，直接向上抛
            throw e
        } catch (e: Exception) {
            // 单层列举失败就当作这一层没有内容，不影响其它层
            Log.e(TAG, "列举子文档失败: $childrenUri", e)
        }
        return children
    }

    /**
     * 发布扫描进度：节流，最多每 200ms 更新一次。
     */
    private suspend fun publishProgress() {
        val now = System.currentTimeMillis()
        mutex.withLock {
            if (now - lastProgressAt < PROGRESS_INTERVAL_MS) return@withLock
            lastProgressAt = now
            // 批次交叠时 completed 可能超过 total，夹一下；这只是进度条的数字
            _scanState.value = ArtworkScanState.Scanning(min(completed, total), total)
        }
    }

    /**
     * 队列排空且本批次已全部发布 → 状态置为 Completed。
     * 若本批次是强制刷新触发的，还要在此时清理旧缓存代次目录。
     */
    private suspend fun markCompletedIfFinished() {
        val finished = mutex.withLock {
            // total 为 0 说明还没有任何一次扫描开始过（刚构造出来时的初始状态），
            // 这时候报 Completed(0) 会让进度条显示成「已完成」的样子
            if (total <= 0 || queue.size != 0 || completed < total) return@withLock false
            _scanState.value = ArtworkScanState.Completed(total)
            true
        }
        if (finished) {
            runPendingPurge()
        }
    }

    /**
     * 执行挂起的旧缓存代次清理（若有）。
     *
     * 锁内只负责取走待清理的目录标识，真正的递归删除在锁外做：
     * 文件 IO 是阻塞的，放在锁里会把所有 worker 一起堵住。
     */
    private suspend fun runPendingPurge() {
        var purgeDirKey: String? = null
        mutex.withLock {
            if (!purgePending) return@withLock
            purgePending = false
            purgeDirKey = pendingPurgeDirKey
            pendingPurgeDirKey = null
        }
        purgeDirKey?.let {
            // 同步文件 IO，worker 已在 IO 线程上；放在锁外避免阻塞其它 worker
            // 只清理这个目录标识下的其它代次目录，不动别的目录标识
            cache.purgeOtherGenerations(it)
        }
    }
}
