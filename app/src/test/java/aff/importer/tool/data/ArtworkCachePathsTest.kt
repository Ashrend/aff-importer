package aff.importer.tool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtworkCachePathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `根目录在 cacheDir 下名为 artwork`() {
        val cacheDir = temp.newFolder("cache")
        assertEquals(
            File(cacheDir, "artwork"),
            ArtworkCachePaths.root(cacheDir)
        )
    }

    @Test
    fun `命名空间目录与代次目录层级正确`() {
        val cacheDir = temp.newFolder("cache")
        val ns = ArtworkCachePaths.namespaceDir(cacheDir, "songs")
        assertEquals(File(cacheDir, "artwork/songs"), ns)
        assertEquals(File(cacheDir, "artwork/songs/gen-3"), ArtworkCachePaths.generationDir(cacheDir, "songs", 3))
    }

    @Test
    fun `没有任何代次目录时当前代次为 0`() {
        val cacheDir = temp.newFolder("cache")
        assertEquals(0, ArtworkCachePaths.existingGeneration(cacheDir, "songs"))
    }

    @Test
    fun `当前代次取已存在代次的最大值`() {
        val cacheDir = temp.newFolder("cache")
        ArtworkCachePaths.generationDir(cacheDir, "songs", 0).mkdirs()
        ArtworkCachePaths.generationDir(cacheDir, "songs", 4).mkdirs()
        ArtworkCachePaths.generationDir(cacheDir, "packs", 7).mkdirs()
        assertEquals(4, ArtworkCachePaths.existingGeneration(cacheDir, "songs"))
        assertEquals(7, ArtworkCachePaths.existingGeneration(cacheDir, "packs"))
    }

    @Test
    fun `缓存文件路径是代次目录下的 jpg 文件`() {
        val cacheDir = temp.newFolder("cache")
        val f = ArtworkCachePaths.fileFor(cacheDir, "songs", 2, "my_song")
        assertEquals(File(cacheDir, "artwork/songs/gen-2/my_song.jpg"), f)
        assertTrue(f.name.endsWith(".jpg"))
    }

    @Test
    fun `代次目录名严格是 gen 前缀加数字`() {
        val cacheDir = temp.newFolder("cache")
        val dir = ArtworkCachePaths.generationDir(cacheDir, "packs", 12)
        assertEquals("gen-12", dir.name)
        assertTrue(dir.parentFile?.name == "packs")
    }
}
