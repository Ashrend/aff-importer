package aff.importer.tool.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内图片缓存的文件层。
 *
 * 目录布局： cacheDir/artwork/<namespace>/gen-<N>/<id>.jpg
 *
 * 为什么需要这一层：Coil 2 不会为 content://（SAF）资源写磁盘缓存，
 * 每次显示曲绘都要重新经由 SAF 提供者读一遍。改为先把图片解析到应用私有缓存目录，
 * 再以 file:// 交给 Coil，读取就落在普通文件系统上。
 *
 * 代次（generation）用于强制刷新：换一代次即换一套路径，
 * Coil 的缓存键（路径 + lastModified）随之改变，必然重新加载。
 *
 * 代次还承担「按目录隔离」的职责：缓存路径里没有目录维度，
 * 因此目标目录一变就必须换代次，否则上一个目录里同名 id 的图片
 * 会被当成当前目录的缓存命中（由 [ArtworkRepository] 在检测到目录切换时推进）。
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
     * 当前代次，只读，由 [advanceGeneration] 改变。
     * 跨线程可见：IO worker 读、主线程写。
     */
    @Volatile
    var generation: Int = ArtworkCachePaths.existingGeneration(cacheDir, namespace)
        private set

    /**
     * 当前代次下该 id 的缓存文件路径。
     * 文件名由 [ArtworkFileRules.cacheFileName] 生成（id 已清洗），不会出现目录穿越。
     */
    fun fileFor(id: String): File =
        ArtworkCachePaths.fileFor(cacheDir, namespace, generation, id)

    /** 当前代次下是否已有该 id 的缓存文件 */
    fun isCached(id: String): Boolean = fileFor(id).isFile

    /**
     * 直接复制字节到缓存，不解码也不重新编码。
     * 用于本来就是 256px JPEG 缩略图的曲绘。
     *
     * @return 缓存文件路径；失败返回 null，且不会留下半截文件
     */
    suspend fun copyToCache(id: String, source: Uri): File? = withContext(Dispatchers.IO) {
        val target = fileFor(id)
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
        } catch (e: Exception) {
            Log.e(TAG, "复制图片到缓存失败: $id", e)
            null
        } finally {
            // rename 成功后临时文件已不存在，delete 是空操作；失败时清理掉半截文件
            tmp.delete()
        }
    }

    /**
     * 解码源图片并转码到缓存（降采样后精确缩放到目标尺寸，输出 JPEG）。
     * 源图已小于目标尺寸时不放大，直接用解码结果。
     *
     * @param targetWidth 目标宽度（曲绘 256、曲包封面 512）
     * @param targetHeight 目标高度（曲绘 256、曲包封面 256）
     * @return 缓存文件路径；失败返回 null，且不会留下半截文件
     */
    suspend fun transcodeToCache(
        id: String,
        source: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): File? = withContext(Dispatchers.IO) {
        val target = fileFor(id)
        val tmp = tmpFileOf(target)
        try {
            val bitmap = decodeBitmap(source, targetWidth, targetHeight) ?: return@withContext null
            try {
                // 源图已小于目标尺寸时直接用原图，不放大
                val output = if (bitmap.width <= targetWidth && bitmap.height <= targetHeight) {
                    bitmap
                } else {
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
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
                    if (output !== bitmap) output.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "转码图片到缓存失败: $id", e)
            null
        } finally {
            // rename 成功后临时文件已不存在，delete 是空操作；失败时清理掉半截文件
            tmp.delete()
        }
    }

    /**
     * 删除当前代次下该 id 的缓存文件（含可能残留的临时文件）。
     * 同步执行，调用方应在 IO 线程调用。
     */
    fun delete(id: String) {
        val target = fileFor(id)
        target.delete()
        tmpFileOf(target).delete()
    }

    /**
     * 代次自增并返回新值。
     * 不删除旧代次目录：刷新过程中旧图仍然可用，等新扫描完成后再由 [purgeOtherGenerations] 清理。
     *
     * 两个调用方：
     * - [ArtworkRepository.requestRefresh]：用户手动点刷新；
     * - [ArtworkRepository] 检测到目标目录变化：让缓存按目录隔离。
     */
    fun advanceGeneration(): Int = synchronized(this) {
        generation = generation + 1
        generation
    }

    /**
     * 递归删除当前代次之外的所有代次目录。
     * 同步执行，调用方应在 IO 线程调用。
     */
    fun purgeOtherGenerations() {
        val current = generation
        val children = ArtworkCachePaths.namespaceDir(cacheDir, namespace).listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (!name.startsWith("gen-")) continue
            val number = name.removePrefix("gen-").toIntOrNull() ?: continue
            if (number == current) continue
            deleteRecursively(child)
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
