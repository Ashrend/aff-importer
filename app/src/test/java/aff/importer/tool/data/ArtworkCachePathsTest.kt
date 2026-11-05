package aff.importer.tool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `命名空间目录与目录标识目录层级正确`() {
        val cacheDir = temp.newFolder("cache")
        val ns = ArtworkCachePaths.namespaceDir(cacheDir, "songs")
        assertEquals(File(cacheDir, "artwork/songs"), ns)
        assertEquals(
            File(cacheDir, "artwork/songs/dirA"),
            ArtworkCachePaths.directoryDir(cacheDir, "songs", "dirA")
        )
    }

    @Test
    fun `代次目录在目录标识目录下`() {
        val cacheDir = temp.newFolder("cache")
        val dir = ArtworkCachePaths.generationDir(cacheDir, "songs", "dirA", 3)
        assertEquals(File(cacheDir, "artwork/songs/dirA/gen-3"), dir)
    }

    @Test
    fun `没有任何代次目录时当前代次为 0`() {
        val cacheDir = temp.newFolder("cache")
        assertEquals(0, ArtworkCachePaths.existingGeneration(cacheDir, "songs", "dirA"))
    }

    @Test
    fun `当前代次取已存在代次的最大值`() {
        val cacheDir = temp.newFolder("cache")
        ArtworkCachePaths.generationDir(cacheDir, "songs", "dirA", 0).mkdirs()
        ArtworkCachePaths.generationDir(cacheDir, "songs", "dirA", 4).mkdirs()
        ArtworkCachePaths.generationDir(cacheDir, "packs", "dirA", 7).mkdirs()
        assertEquals(4, ArtworkCachePaths.existingGeneration(cacheDir, "songs", "dirA"))
        assertEquals(7, ArtworkCachePaths.existingGeneration(cacheDir, "packs", "dirA"))
    }

    @Test
    fun `缓存文件路径是代次目录下的 jpg 文件`() {
        val cacheDir = temp.newFolder("cache")
        val f = ArtworkCachePaths.fileFor(cacheDir, "songs", "dirA", 2, "my_song")
        assertEquals(File(cacheDir, "artwork/songs/dirA/gen-2/my_song.jpg"), f)
        assertTrue(f.name.endsWith(".jpg"))
    }

    @Test
    fun `代次目录名严格是 gen 前缀加数字`() {
        val cacheDir = temp.newFolder("cache")
        val dir = ArtworkCachePaths.generationDir(cacheDir, "packs", "dirA", 12)
        assertEquals("gen-12", dir.name)
        assertEquals("dirA", dir.parentFile?.name)
    }

    @Test
    fun `目录标识对同一个 Uri 稳定`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AArcaea"
        val first = ArtworkCachePaths.directoryKey(uri)
        val second = ArtworkCachePaths.directoryKey(uri)
        assertEquals(first, second)
        // 必须是固定长度，不能随输入长度变化
        assertEquals(16, first.length)
    }

    @Test
    fun `不同 Uri 得到不同目录标识`() {
        val a = ArtworkCachePaths.directoryKey("content://com.android.externalstorage.documents/tree/primary%3AArcaea")
        val b = ArtworkCachePaths.directoryKey("content://com.android.externalstorage.documents/tree/primary%3AArcaea2")
        assertNotEquals(a, b)
    }

    @Test
    fun `目录标识只含文件系统安全字符`() {
        val key = ArtworkCachePaths.directoryKey(
            "content://com.android.externalstorage.documents/tree/primary%3AArcaea/文档 1"
        )
        // 只允许小写十六进制字母与数字，路径里不会出现分隔符或空格
        assertTrue(key.matches(Regex("[0-9a-f]+")))
        assertEquals(16, key.length)
    }

    @Test
    fun `不同目录标识得到不同的文件路径`() {
        val cacheDir = temp.newFolder("cache")
        val a = ArtworkCachePaths.fileFor(cacheDir, "songs", "dirA", 1, "my_song")
        val b = ArtworkCachePaths.fileFor(cacheDir, "songs", "dirB", 1, "my_song")
        assertNotEquals(a, b)
        assertNotEquals(a.parentFile, b.parentFile)
    }

    @Test
    fun `代次按目录标识隔离`() {
        val cacheDir = temp.newFolder("cache")
        ArtworkCachePaths.generationDir(cacheDir, "songs", "dirA", 3).mkdirs()
        // A 目录的 gen-3 对 B 目录不可见
        assertEquals(3, ArtworkCachePaths.existingGeneration(cacheDir, "songs", "dirA"))
        assertEquals(0, ArtworkCachePaths.existingGeneration(cacheDir, "songs", "dirB"))
    }

    @Test
    fun `旧版布局的代次目录不再被当作当前代次`() {
        val cacheDir = temp.newFolder("cache")
        // 旧布局：gen-N 直接挂在命名空间目录下
        File(cacheDir, "artwork/songs/gen-5").mkdirs()
        assertEquals(0, ArtworkCachePaths.existingGeneration(cacheDir, "songs", "dirA"))
        // 但旧目录仍然能被识别出来，供一次性清理使用
        assertEquals(1, ArtworkCachePaths.legacyGenerationDirs(cacheDir, "songs").size)
    }

    @Test
    fun `目录标识目录下的代次目录不会被当成旧版布局`() {
        val cacheDir = temp.newFolder("cache")
        ArtworkCachePaths.generationDir(cacheDir, "songs", "dirA", 2).mkdirs()
        assertTrue(ArtworkCachePaths.legacyGenerationDirs(cacheDir, "songs").isEmpty())
        assertEquals(1, ArtworkCachePaths.generationDirs(cacheDir, "songs", "dirA").size)
    }
}
