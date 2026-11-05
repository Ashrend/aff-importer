package aff.importer.tool.data

import java.io.File

/**
 * 应用内图片缓存的目录布局（纯逻辑，可单元测试）
 *
 * 结构： cacheDir/artwork/<namespace>/gen-<N>/<id>.jpg
 * 代次用于强制刷新：换一代次即换路径，Coil 的缓存键随之改变，必然重新加载。
 */
object ArtworkCachePaths {

    const val ROOT_DIR = "artwork"

    fun root(cacheDir: File): File = File(cacheDir, ROOT_DIR)

    fun namespaceDir(cacheDir: File, namespace: String): File = File(root(cacheDir), namespace)

    fun generationDir(cacheDir: File, namespace: String, generation: Int): File =
        File(namespaceDir(cacheDir, namespace), "gen-$generation")

    /**
     * 取出该命名空间下已存在的最大代次；不存在时返回 0
     */
    fun existingGeneration(cacheDir: File, namespace: String): Int {
        val dir = namespaceDir(cacheDir, namespace)
        val children = dir.listFiles() ?: return 0
        var max = -1
        for (child in children) {
            val name = child.name
            if (!name.startsWith("gen-")) continue
            val n = name.removePrefix("gen-").toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return if (max < 0) 0 else max
    }

    fun fileFor(cacheDir: File, namespace: String, generation: Int, id: String): File =
        File(generationDir(cacheDir, namespace, generation), ArtworkFileRules.cacheFileName(id))
}
