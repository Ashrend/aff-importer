package aff.importer.tool.data

import aff.importer.tool.data.model.ArtworkCacheLocation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内图片缓存的文件层。
 *
 * 目录布局： cacheDir/artwork/<namespace>/<dirKey>/gen-<N>/<id>.jpg
 *
 * 为什么需要这一层：Coil 2 不会为 content://（SAF）资源写磁盘缓存，
 * 每次显示曲绘都要重新经由 SAF 提供者读一遍。改为先把图片解析到应用私有缓存目录，
 * 再以 file:// 交给 Coil，读取就落在普通文件系统上。
 *
 * 目录标识（dirKey）负责「按目录隔离」：它由目标目录 Uri 的 SHA-256 摘要派生
 * （见 [ArtworkCachePaths.directoryKey]），只依赖 Uri 字符串，因此
 * - 不同目标目录的缓存路径天然不同，不会互相串图；
 * - 同一个目录在下次冷启动时仍然落在同一套路径上，热缓存直接复用，不必重新拷贝。
 *
 * 代次（generation）负责「强制刷新」：同一个目录内自增一代就换一套路径，
 * Coil 的缓存键（路径）随之改变，必然重新加载。
 *
 * 所有读写都以**显式位置**（[ArtworkCacheLocation] = 目录标识 + 代次）为参数：
 * 扫描任务认领时把自己的位置带走，之后目录切换也不会改写它落盘的目标，
 * 不会出现「上一个目录的曲绘被写进下一个目录的缓存目录」。
 * 类里保留的 ambient 状态只用于「当前是哪个目录」这类 bookkeeping
 * （见 [useDirectory] / [advanceGeneration]），不参与任何路径计算。
 */
class ArtworkCache(context: Context, val namespace: String) {

    companion object {
        private const val TAG = "ArtworkCache"

        /** 临时文件后缀：写完并关闭流后再 rename，避免 Coil 读到半截文件 */
        private const val TMP_SUFFIX = ".tmp"

        /** 缓存统一输出 JPEG */
        private const val JPEG_QUALITY = 85
    }

    private val appContext: Context = context.applicationContext
    private val cacheDir: File = appContext.cacheDir

    /**
     * 当前绑定的目录标识与它下面的代次。
     * null 表示还没有绑定任何目录。
     *
     * 只用于「当前是哪个目录」这类 bookkeeping：
     * 真正的文件读写一律以显式位置（[ArtworkCacheLocation]）为参数，
     * 不读这里的环境状态 —— 环境状态随时可能因为用户切换目录而改变。
     */
    @Volatile
    private var state: State? = null

    /**
     * 进程内记住的「目录标识 → 代次」。
     *
     * 首次见到某个目录标识时从磁盘读出它已有的最大代次（复用热缓存），
     * 之后本进程内的换代次都记在这里 —— 否则「刚推进代次、还没落盘」
     * 时重新绑定该目录会把代次退回旧值，刷新就白做了。
     * 只在 `synchronized(this)` 临界区里访问。
     */
    private val generations = HashMap<String, Int>()

    /**
     * 旧版布局的代次目录是否已开始清理（只清一次）。
     *
     * 用 CAS 保证只有一个线程真正执行删除；删除本身是阻塞的文件 IO，
     * 不能在锁内做（见 [useDirectory]）。
     */
    private val legacyCleanupStarted = AtomicBoolean(false)

    /** 当前目录标识与代次的一对不可变快照 */
    private data class State(val directoryKey: String, val generation: Int)

    /**
     * 当前绑定的位置；还没绑定任何目录时返回 null。
     */
    fun currentLocation(): ArtworkCacheLocation? {
        val current = state ?: return null
        return ArtworkCacheLocation(current.directoryKey, current.generation)
    }

    /**
     * 把缓存指向某个目录标识，并返回这个目录标识当前的位置。
     *
     * - 换到**另一个**目录标识：代次取该标识已有的代次（热缓存直接复用）；
     * - 换到**同一个**目录标识：代次保持不变（[generations] 里记的就是它）。
     *
     * 旧版布局（gen-N 直接挂在命名空间目录下）不再被读取，
     * 首次绑定目录标识时顺手删掉，避免旧字节一直占着空间。
     *
     * @param dirKey 目录标识（由 [ArtworkCachePaths.directoryKey] 派生）
     * @return 该目录标识当前的位置快照（目录标识 + 代次）
     */
    fun useDirectory(dirKey: String): ArtworkCacheLocation {
        // 旧版布局的清理是递归文件 IO，必须在锁外做：
        // worker 取缓存位置（locationOf）时也要拿这把锁，
        // 锁内做递归删除会把所有 worker 一起堵住。
        purgeLegacyGenerations()

        synchronized(this) {
            val generation = generations.getOrPut(dirKey) {
                ArtworkCachePaths.existingGeneration(cacheDir, namespace, dirKey)
            }
            state = State(dirKey, generation)
            return ArtworkCacheLocation(dirKey, generation)
        }
    }

    /**
     * 某个目录标识当前的位置（目录标识 + 代次）。
     *
     * 不改变「当前是哪个目录」，只是查一下这个目录标识现在该用哪一代。
     * 换代次与扫描发布位置在同一个临界区里完成时，用它就能拿到最新的代次。
     */
    fun locationOf(dirKey: String): ArtworkCacheLocation {
        synchronized(this) {
            val generation = generations.getOrPut(dirKey) {
                ArtworkCachePaths.existingGeneration(cacheDir, namespace, dirKey)
            }
            return ArtworkCacheLocation(dirKey, generation)
        }
    }

    /**
     * 指定位置下该 id 的缓存文件路径。
     * 文件名由 [ArtworkFileRules.cacheFileName] 生成（id 已清洗），不会出现目录穿越。
     */
    fun fileFor(location: ArtworkCacheLocation, id: String): File =
        ArtworkCachePaths.fileFor(cacheDir, namespace, location, id)

    /**
     * 直接复制字节到缓存，不解码也不重新编码。
     * 用于本来就是 256px JPEG 缩略图的曲绘。
     *
     * @param location 落盘位置：必须是这个任务自己认领的位置，不能用「当前目录」
     * @param id 条目 id
     * @param source SAF 源文件 Uri
     * @return 缓存文件路径；失败返回 null，且不会留下半截文件
     */
    suspend fun copyToCache(
        location: ArtworkCacheLocation,
        id: String,
        source: Uri
    ): File? = withContext(Dispatchers.IO) {
        val target = fileFor(location, id)
        val tmp = tmpFileOf(target)
        try {
            target.parentFile?.mkdirs()
            val input = appContext.contentResolver.openInputStream(source)
            if (input == null) {
                Log.w(TAG, "无法打开源图片: $source")
                return@withContext null
            }
            input.use { src ->
                tmp.outputStream().use { out -> src.copyTo(out) }
            }
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "重命名失败: ${tmp.name} -> ${target.name}")
                return@withContext null
            }
            target
        } catch (e: CancellationException) {
            // 协程被取消不是错误：向上抛，让 worker 正常结束。
            // CancellationException 是 IllegalStateException 的子类，
            // 不单独处理的话会被下面的通用 catch 吞掉。
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "复制图片到缓存失败: $id", e)
            null
        } finally {
            // rename 成功后临时文件已不存在，delete 是空操作；失败时清理掉半截文件
            tmp.delete()
        }
    }

    /**
     * 解码源图片并转码到缓存（等比缩放 + 居中裁剪到目标尺寸，输出 JPEG）。
     * 源图比目标尺寸小时不放大，直接用解码结果。
     *
     * @param location 落盘位置：必须是这个任务自己认领的位置，不能用「当前目录」
     * @param id 条目 id
     * @param source SAF 源文件 Uri
     * @param targetWidth 目标宽度（曲绘 256、曲包封面 512）
     * @param targetHeight 目标高度（曲绘 256、曲包封面 256）
     * @return 缓存文件路径；失败返回 null，且不会留下半截文件
     */
    suspend fun transcodeToCache(
        location: ArtworkCacheLocation,
        id: String,
        source: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): File? = withContext(Dispatchers.IO) {
        val target = fileFor(location, id)
        val tmp = tmpFileOf(target)
        try {
            val bitmap = decodeBitmap(source, targetWidth, targetHeight) ?: return@withContext null
            var cropped: Bitmap? = null
            try {
                // 等比缩放 + 居中裁剪：比例与 UI 的 ContentScale.Crop 一致，绝不拉伸变形
                val plan = ArtworkImageGeometry.plan(
                    bitmap.width,
                    bitmap.height,
                    targetWidth,
                    targetHeight
                ) ?: return@withContext null

                cropped = Bitmap.createBitmap(
                    bitmap,
                    plan.srcLeft,
                    plan.srcTop,
                    plan.srcWidth,
                    plan.srcHeight
                )
                val output = if (cropped.width == plan.dstWidth && cropped.height == plan.dstHeight) {
                    cropped
                } else {
                    Bitmap.createScaledBitmap(cropped, plan.dstWidth, plan.dstHeight, true)
                }
                try {
                    target.parentFile?.mkdirs()
                    tmp.outputStream().use { out ->
                        if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                            Log.w(TAG, "JPEG 编码失败: $id")
                            return@withContext null
                        }
                    }
                    if (!tmp.renameTo(target)) {
                        Log.w(TAG, "重命名失败: ${tmp.name} -> ${target.name}")
                        return@withContext null
                    }
                    target
                } finally {
                    if (output !== cropped) output.recycle()
                }
            } finally {
                // createBitmap 在裁剪区域等于整张图时会原样返回源 bitmap，这时不能重复回收
                if (cropped != null && cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            // 协程被取消不是错误：向上抛，让 worker 正常结束。
            // CancellationException 是 IllegalStateException 的子类，
            // 不单独处理的话会被下面的通用 catch 吞掉。
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "转码图片到缓存失败: $id", e)
            null
        } finally {
            // rename 成功后临时文件已不存在，delete 是空操作；失败时清理掉半截文件
            tmp.delete()
        }
    }

    /**
     * 删除指定位置下该 id 的缓存文件（含可能残留的临时文件）。
     * 同步执行，调用方应在 IO 线程调用。
     */
    fun delete(location: ArtworkCacheLocation, id: String) {
        val target = fileFor(location, id)
        target.delete()
        tmpFileOf(target).delete()
    }

    /**
     * 推进某个目录标识的代次，并返回新的位置。
     *
     * 不删除旧代次目录：刷新过程中旧图仍然可用，等新扫描完成后再由 [purgeOtherGenerations] 清理。
     *
     * 只用于手动刷新（[ArtworkRepository.requestRefresh]）：
     * 目录之间的隔离由路径里的目录标识负责，不需要靠换代次。
     *
     * @param dirKey 要推进代次的目录标识（调用方应先用 [useDirectory] 绑定同一个标识）
     */
    fun advanceGeneration(dirKey: String): ArtworkCacheLocation {
        synchronized(this) {
            val current = generations.getOrPut(dirKey) {
                ArtworkCachePaths.existingGeneration(cacheDir, namespace, dirKey)
            }
            val next = current + 1
            generations[dirKey] = next
            // 当前绑定的就是这个目录标识时，环境状态同步跟进
            if (state?.directoryKey == dirKey) {
                state = State(dirKey, next)
            }
            return ArtworkCacheLocation(dirKey, next)
        }
    }

    /**
     * 递归删除指定目录标识下、当前代次之外的所有代次目录。
     *
     * **只动这一个目录标识**：别的目录标识属于别的目标目录，
     * 留着它们才能在切回旧目录时立刻复用缓存。
     *
     * 保留的是这个目录标识**当前**的代次（[locationOf]），
     * 所以即使清理期间又推进了代次，正在写的新一代也不会被误删。
     *
     * 同步执行，调用方应在 IO 线程调用。
     */
    fun purgeOtherGenerations(dirKey: String) {
        val current = locationOf(dirKey)
        val dirs = ArtworkCachePaths.generationDirs(cacheDir, namespace, dirKey)
        for (dir in dirs) {
            val number = ArtworkCachePaths.generationNumber(dir.name) ?: continue
            if (number == current.generation) continue
            deleteRecursively(dir)
        }
    }

    /**
     * 清理旧版布局的代次目录（gen-N 直接挂在命名空间目录下）。
     * 新布局不再读取它们，只在首次绑定目录标识时删一次。
     *
     * 递归删除是阻塞的文件 IO，**不能在 synchronized(this) 里做**：
     * worker 取缓存位置时也要拿这把锁，锁内做删除会把所有 worker 一起堵住。
     */
    private fun purgeLegacyGenerations() {
        // CAS：只有一个线程真正执行删除，其余线程直接返回
        if (!legacyCleanupStarted.compareAndSet(false, true)) return
        for (dir in ArtworkCachePaths.legacyGenerationDirs(cacheDir, namespace)) {
            deleteRecursively(dir)
        }
    }

    /**
     * 解码源图片：先只读尺寸，再按采样率解码，使解码结果不小于目标尺寸。
     */
    private fun decodeBitmap(source: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(source)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "无法读取源图片尺寸: $source")
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        val bitmap = appContext.contentResolver.openInputStream(source)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (bitmap == null) {
            Log.w(TAG, "解码失败: $source")
        }
        return bitmap
    }

    /**
     * 采样率：使解码结果不小于目标尺寸，至少为 1。
     */
    private fun calculateSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        if (targetWidth <= 0 || targetHeight <= 0) return 1
        val sample = minOf(srcWidth / targetWidth, srcHeight / targetHeight)
        return if (sample < 1) 1 else sample
    }

    /** 临时文件路径：写完并关闭流后再 rename */
    private fun tmpFileOf(target: File): File = File(target.absolutePath + TMP_SUFFIX)

    /** 递归删除文件或目录 */
    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
