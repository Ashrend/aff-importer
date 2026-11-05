package aff.importer.tool.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import aff.importer.tool.data.model.ArtworkKind
import aff.importer.tool.data.model.ArtworkScanState
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
import kotlin.math.min

/**
 * 单个条目的图片解析结果
 *
 * @param kind 图片种类（曲绘 / 曲包封面）
 * @param id 条目 id：曲目为实际文件夹 id（getActualFolderId），曲包为 pack id
 * @param uri 图片 Uri：应用内缓存文件为 file://，SAF 源文件为 content://，没有源文件时为 null
 */
data class ArtworkResult(val kind: ArtworkKind, val id: String, val uri: Uri?)

/**
 * 图片扫描编排。
 *
 * 职责：
 * 1. 把 SAF 目录结构整理成「id → 文件」的索引，避免每个条目都去遍历根目录；
 * 2. 用优先级队列 + 常驻 worker 并发解析条目，可见项可以插队先出图；
 * 3. 解析结果落到应用内缓存（[ArtworkCache]），再以 file:// 交给 Coil 读取；
 * 4. 用 Channel 发布结果，由调用方串行消费，避免丢结果。
 *
 * 线程约定：
 * - 队列、索引、当前目录、扫描代次、进度计数等共享状态，全部由 [mutex] 保护；
 * - [ArtworkPriorityQueue] 自身不加锁，锁由本类负责；
 * - SAF 查询是阻塞 IO，只允许出现在 worker 线程与 [buildIndex] 里。
 *
 * @param context 应用上下文（内部只使用 applicationContext）
 * @param scope worker 与请求协程所属的作用域，ViewModel 销毁时随之取消
 * @param kind 图片种类，决定索引形状与目标尺寸
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtworkRepository(
    context: Context,
    scope: CoroutineScope,
    val kind: ArtworkKind
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
    }

    private val appContext: Context = context.applicationContext

    /** worker 与请求协程所属的作用域 */
    private val jobScope: CoroutineScope = scope

    /** 应用内缓存文件层（它自己的代次与本类的扫描代次是两回事） */
    private val cache = ArtworkCache(appContext, kind.namespace)

    /** 待解析队列；自身不加锁，所有读写都在 [mutex] 内 */
    private val queue = ArtworkPriorityQueue()

    /** 保护 queue / index / currentDirectoryUri / generation / total / completed 的唯一一把锁 */
    private val mutex = Mutex()

    /**
     * 扫描代次：目录切换或强制刷新时自增。
     * worker 解析完成后要再比对一次，代次变了就丢弃结果，
     * 避免把旧目录 / 旧缓存代次的 Uri 发到新状态上。
     */
    private var generation = 0

    /** 当前目录的图片索引，整体替换，读时取不可变快照 */
    private var index: Map<String, DocumentFile> = emptyMap()

    /** 当前扫描的目录 */
    private var currentDirectoryUri: Uri? = null

    /** 本批次的条目总数 */
    private var total = 0

    /** 本批次已发布的结果数 */
    private var completed = 0

    /** 上次发布进度的时刻（毫秒），用于节流 */
    private var lastProgressAt = 0L

    /** 是否需要在扫描完成后清理旧缓存代次目录（强制刷新时置位，清理后清除） */
    private var purgePending = false

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
     */
    fun requestScan(directoryUri: Uri, ids: List<String>) {
        // SAF 查询是阻塞 IO，不能占用调用方线程（ViewModel 的 scope 跑在主线程）
        jobScope.launch(Dispatchers.IO) { performScan(directoryUri, ids, purgeAfterScan = false) }
    }

    /**
     * 强制刷新：换缓存代次 → 全量重扫 → 扫描完成后清理旧代次目录。
     *
     * @param directoryUri 目标目录
     * @param ids 待解析的条目 id
     */
    fun requestRefresh(directoryUri: Uri, ids: List<String>) {
        jobScope.launch(Dispatchers.IO) {
            // 换代次 → 落到新的缓存目录 → Coil 的缓存键随之改变，必然重新加载
            cache.advanceGeneration()

            // 作废进行中的旧结果：旧缓存代次的 Uri 不能覆盖新代次的结果
            mutex.withLock { generation++ }

            performScan(directoryUri, ids, purgeAfterScan = true)
        }
    }

    /**
     * 把仍在队列中的 id 前插，让当前可见的条目先出图。
     * 不取消任何已排队的任务。
     */
    fun requestPrioritize(ids: List<String>) {
        if (ids.isEmpty()) return
        jobScope.launch {
            mutex.withLock { queue.prioritize(ids) }
        }
    }

    /**
     * 删除某个条目的缓存文件。
     *
     * 非 suspend：ViewModel 在主线程调用。
     * [ArtworkCache.delete] 是同步文件 IO，因此发射到 IO 线程执行，调用后立即返回。
     *
     * 不需要从队列里移除该 id：被删除的曲目文件夹已不存在，解析结果自然为 null，
     * 而发布时对应的状态条目已被 ViewModel 移除，写入是空操作。
     */
    fun remove(id: String) {
        jobScope.launch(Dispatchers.IO) {
            cache.delete(id)
        }
    }

    /**
     * 执行一次扫描：重建索引、重置进度、入队。
     *
     * @param purgeAfterScan 本批次完成后是否清理旧缓存代次目录
     */
    private suspend fun performScan(directoryUri: Uri, ids: List<String>, purgeAfterScan: Boolean) {
        mutex.withLock {
            // 目录切换：作废旧目录的进行中结果，并清空旧队列
            if (directoryUri != currentDirectoryUri) {
                currentDirectoryUri = directoryUri
                generation++
                queue.clear()
            }

            // 索引每次都重建（不只是目录变化时）：
            // remote_dl 改名会新增一个文件夹，目录没变但旧索引里没有这个 folderId，
            // 重建只需 1~2 次 SAF 查询，代价可忽略，换来「任何时候都能解析到当前实际存在的条目」。
            index = buildIndex(directoryUri)

            // 进度按批次计算，不是累计
            total = ids.size
            completed = 0
            lastProgressAt = 0L
            if (purgeAfterScan) {
                // 标记：本次扫描完成后清理旧缓存代次目录
                purgePending = true
            }
            _scanState.value = ArtworkScanState.Scanning(0, total)

            queue.enqueue(ids)
        }
    }

    /**
     * 构建当前目录的图片索引。
     *
     * SONG_JACKET：key = 文件夹名（= 曲目的实际文件夹 id），value = 该曲目文件夹，1 次 SAF 查询。
     * PACK_BANNER：key = pack id，value = 封面文件，2 次 SAF 查询。
     */
    private suspend fun buildIndex(directoryUri: Uri): Map<String, DocumentFile> {
        return try {
            when (kind) {
                ArtworkKind.SONG_JACKET -> buildSongFolderIndex(directoryUri)
                ArtworkKind.PACK_BANNER -> buildPackBannerIndex(directoryUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "构建图片索引失败: $directoryUri", e)
            emptyMap()
        }
    }

    /**
     * 曲绘索引：一次列举根目录，得到「文件夹名 → 文件夹」。
     * 旧实现每个条目都要重新列举整个根目录，是 O(N²) 的 SAF 查询。
     */
    private suspend fun buildSongFolderIndex(directoryUri: Uri): Map<String, DocumentFile> {
        val root = DocumentFile.fromTreeUri(appContext, directoryUri) ?: return emptyMap()
        val children = root.listFiles() ?: return emptyMap()

        val map = LinkedHashMap<String, DocumentFile>()
        for (child in children) {
            if (!child.isDirectory) continue
            val name = child.name ?: continue
            map[name] = child
        }
        return map
    }

    /**
     * 曲包封面索引：所有封面都在 pack 目录里，列举一次即可解析出全部 pack id。
     * 旧实现每个曲包都重新查找 pack 目录，是 O(P²) 的 SAF 查询。
     */
    private suspend fun buildPackBannerIndex(directoryUri: Uri): Map<String, DocumentFile> {
        val root = DocumentFile.fromTreeUri(appContext, directoryUri) ?: return emptyMap()
        val packFolder = root.findFile(PACK_FOLDER_NAME) ?: return emptyMap()
        val children = packFolder.listFiles() ?: return emptyMap()

        val map = LinkedHashMap<String, DocumentFile>()
        for (child in children) {
            val name = child.name ?: continue
            val packId = ArtworkFileRules.parsePackBannerId(name) ?: continue
            map[packId] = child
        }
        return map
    }

    /**
     * worker 主循环：取任务 → 解析 → 校验代次 → 发布结果。
     */
    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            // 取任务与记录代次必须在同一个临界区完成：
            // 若分两次加锁，中间发生目录切换就会读到新代次，旧任务的结果就拦不住了
            val claimed = mutex.withLock { queue.poll()?.let { id -> id to generation } }
            if (claimed == null) {
                // 队列空：可能本批次已经跑完，顺带更新完成状态
                markCompletedIfFinished()
                delay(IDLE_WAIT_MS)
                continue
            }

            // 本次任务所属的代次
            val task = claimed.first
            val myGeneration = claimed.second

            // SAF 解析：worker 跑在 IO 线程上
            val uri = resolveOne(task)

            // 解析期间目录可能已切换（或已强制刷新）：代次变了就丢弃结果，
            // 绝不能把旧目录 / 旧缓存代次的 Uri 发出去
            val stillValid = mutex.withLock { generation == myGeneration }
            if (!stillValid) {
                continue
            }

            // 结果进 Channel，由调用方串行消费（UNLIMITED，不会丢）
            _results.send(ArtworkResult(kind, task, uri))

            mutex.withLock { completed++ }
            publishProgress()
        }
    }

    /**
     * 解析单个条目的图片。
     *
     * @return 图片 Uri；没有源文件时为 null
     */
    private suspend fun resolveOne(id: String): Uri? {
        return try {
            val cachedUri = cachedUriOf(id)
            if (cachedUri != null) {
                // 缓存命中：直接给应用内缓存文件的 Uri，完全跳过 SAF 查询
                cachedUri
            } else {
                // 索引只整体替换，读一份不可变快照即可
                val indexSnapshot = mutex.withLock { index }
                when (kind) {
                    ArtworkKind.SONG_JACKET -> resolveSongJacket(id, indexSnapshot)
                    ArtworkKind.PACK_BANNER -> resolvePackBanner(id, indexSnapshot)
                }
            }
        } catch (e: CancellationException) {
            // 协程被取消（ViewModel 销毁等）不是错误，直接向上抛，让 worker 正常结束
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解析图片失败: $kind/$id", e)
            null
        }
    }

    /**
     * 缓存命中时返回应用内缓存文件的 Uri；未命中返回 null。
     */
    private fun cachedUriOf(id: String): Uri? {
        if (!cache.isCached(id)) return null
        val file = cache.fileFor(id)
        // 代次可能在上面两次调用之间变化，这里复核一次，避免指向不存在的文件
        return if (file.isFile) Uri.fromFile(file) else null
    }

    /**
     * 解析曲绘：索引命中 → 列出曲目文件夹 → 选曲绘文件 → 落盘到应用内缓存。
     */
    private suspend fun resolveSongJacket(
        id: String,
        indexSnapshot: Map<String, DocumentFile>
    ): Uri? {
        // 没有这个文件夹 → 该曲目不存在（可能已被删除或改名）
        val folder = indexSnapshot[id] ?: return null

        val children = folder.listFiles() ?: return null
        val jacket = ArtworkFileRules.selectSongJacket(children.mapNotNull { it.name }) ?: return null
        val file = children.firstOrNull { it.name == jacket.fileName } ?: return null

        // 缩略图本身就是 256px JPEG，直接复制字节；全尺寸图需要降采样转码
        val cached = if (jacket.isThumbnail) {
            cache.copyToCache(id, file.uri)
        } else {
            cache.transcodeToCache(id, file.uri, SONG_JACKET_SIZE, SONG_JACKET_SIZE)
        }
        // 落盘失败时回退到 SAF 的 content:// Uri：图片仍能显示，只是读取慢一些
        return cached?.let { Uri.fromFile(it) } ?: file.uri
    }

    /**
     * 解析曲包封面：索引命中 → 转码到应用内缓存。
     */
    private suspend fun resolvePackBanner(
        id: String,
        indexSnapshot: Map<String, DocumentFile>
    ): Uri? {
        val file = indexSnapshot[id] ?: return null

        // 封面是横幅，按 512x256 转码
        val cached = cache.transcodeToCache(id, file.uri, PACK_BANNER_WIDTH, PACK_BANNER_HEIGHT)
        // 落盘失败时回退到 SAF 的 content:// Uri
        return cached?.let { Uri.fromFile(it) } ?: file.uri
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
        var needPurge = false
        mutex.withLock {
            if (queue.size != 0 || completed < total) return@withLock
            _scanState.value = ArtworkScanState.Completed(total)
            if (purgePending) {
                purgePending = false
                needPurge = true
            }
        }
        if (needPurge) {
            // 同步文件 IO，worker 已在 IO 线程上；放在锁外避免阻塞其它 worker
            cache.purgeOtherGenerations()
        }
    }
}
