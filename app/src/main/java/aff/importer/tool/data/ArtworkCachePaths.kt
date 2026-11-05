package aff.importer.tool.data

import aff.importer.tool.data.model.ArtworkCacheLocation
import java.io.File
import java.security.MessageDigest

/**
 * 应用内图片缓存的目录布局（纯逻辑，可单元测试）
 *
 * 结构： cacheDir/artwork/<namespace>/<dirKey>/gen-<N>/<id>.jpg
 *
 * - <dirKey>：目标目录标识，由目录 Uri 的 SHA-256 摘要派生（见 [directoryKey]）。
 *   目录标识进路径 → 不同目标目录的缓存天然隔离，不会出现「上一个目录的曲绘
 *   被当成当前目录的曲绘」。摘要只依赖 Uri 字符串，跨进程重启不变，
 *   因此同一个目录在下次冷启动时仍然命中同一套缓存文件，不必重新拷贝。
 * - <N>：代次。同一目录内点刷新就自增一代 → 路径变化 → Coil 的缓存键变化 →
 *   必然重新加载。
 *
 * 旧版布局是 cacheDir/artwork/<namespace>/gen-<N>/<id>.jpg（没有目录维度），
 * 已不再读取，见 [legacyGenerationDirs]。
 */
object ArtworkCachePaths {

    const val ROOT_DIR = "artwork"

    /** 代次目录名前缀 */
    const val GENERATION_PREFIX = "gen-"

    /** 目录标识的十六进制字符数（16 位十六进制 = 64 位摘要） */
    private const val DIR_KEY_LENGTH = 16

    private const val HEX_DIGITS = "0123456789abcdef"

    fun root(cacheDir: File): File = File(cacheDir, ROOT_DIR)

    fun namespaceDir(cacheDir: File, namespace: String): File = File(root(cacheDir), namespace)

    /**
     * 由目标目录 Uri 派生稳定的目录标识。
     *
     * - **确定性**：只依赖输入字符串，SHA-256 是纯函数，同一 Uri 永远得到同一标识，
     *   跨进程重启也一样 → 缓存能跨重启复用。
     * - **不碰撞**：用密码学摘要而不是 32 位的 [String.hashCode]，
     *   截取前 8 字节（64 位）后仍足以区分实际用到的目录数量。
     * - **文件系统安全**：只含小写十六进制字符，路径长度也可控。
     *
     * @param directoryUri 目标目录 Uri 的字符串形式
     */
    fun directoryKey(directoryUri: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(directoryUri.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(DIR_KEY_LENGTH)
        var i = 0
        while (sb.length < DIR_KEY_LENGTH && i < digest.size) {
            val byte = digest[i].toInt() and 0xFF
            sb.append(HEX_DIGITS[byte ushr 4])
            sb.append(HEX_DIGITS[byte and 0x0F])
            i++
        }
        return sb.toString()
    }

    /** 目录标识目录：命名空间目录下的一个子目录 */
    fun directoryDir(cacheDir: File, namespace: String, dirKey: String): File =
        File(namespaceDir(cacheDir, namespace), dirKey)

    /** 代次目录：目录标识目录下的 gen-<N> */
    fun generationDir(cacheDir: File, namespace: String, dirKey: String, generation: Int): File =
        File(directoryDir(cacheDir, namespace, dirKey), "$GENERATION_PREFIX$generation")

    /** 缓存文件：代次目录下的 <sanitizedId>.jpg */
    fun fileFor(
        cacheDir: File,
        namespace: String,
        dirKey: String,
        generation: Int,
        id: String
    ): File = File(
        generationDir(cacheDir, namespace, dirKey, generation),
        ArtworkFileRules.cacheFileName(id)
    )

    /**
     * 缓存文件：按显式位置（目录标识 + 代次）计算。
     *
     * 扫描任务用它算自己该写到哪里：位置是任务自己带走的，
     * 不会因为用户切换目录而落到别的目录标识下。
     */
    fun fileFor(
        cacheDir: File,
        namespace: String,
        location: ArtworkCacheLocation,
        id: String
    ): File = fileFor(cacheDir, namespace, location.directoryKey, location.generation, id)

    /** 该目录标识下已存在的代次目录 */
    fun generationDirs(cacheDir: File, namespace: String, dirKey: String): List<File> {
        val children = directoryDir(cacheDir, namespace, dirKey).listFiles() ?: return emptyList()
        return children.filter { generationNumber(it.name) != null }
    }

    /**
     * 旧版布局的代次目录（gen-N 直接挂在命名空间目录下）。
     * 新布局不再读取它们，只在首次绑定目录标识时删掉，避免旧字节一直占着空间。
     */
    fun legacyGenerationDirs(cacheDir: File, namespace: String): List<File> {
        val children = namespaceDir(cacheDir, namespace).listFiles() ?: return emptyList()
        return children.filter { generationNumber(it.name) != null }
    }

    /**
     * 取出该目录标识下已存在的最大代次；不存在时返回 0。
     *
     * 只看该目录标识自己的代次目录：A 目录的 gen-3 对 B 目录不可见。
     */
    fun existingGeneration(cacheDir: File, namespace: String, dirKey: String): Int {
        var max = -1
        for (dir in generationDirs(cacheDir, namespace, dirKey)) {
            val n = generationNumber(dir.name) ?: continue
            if (n > max) max = n
        }
        return if (max < 0) 0 else max
    }

    /** 从目录名解析代次号；不是代次目录名时返回 null */
    fun generationNumber(name: String): Int? {
        if (!name.startsWith(GENERATION_PREFIX)) return null
        return name.removePrefix(GENERATION_PREFIX).toIntOrNull()
    }
}
