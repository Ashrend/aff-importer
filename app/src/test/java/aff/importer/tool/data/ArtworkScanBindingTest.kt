package aff.importer.tool.data

import aff.importer.tool.data.model.ArtworkCacheLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫描「钉住」协议的单元测试。
 *
 * 这一层是纯逻辑：发布「索引 + 缓存位置」、认领任务时把两者一起带走。
 * 被测的行为就是本次修复的核心 —— 任务认领后，
 * 不管环境里的「当前目录 / 当前代次」怎么变，它读写缓存的位置都不变。
 */
class ArtworkScanBindingTest {

    @Test
    fun `认领任务带走当前发布的索引与位置`() {
        val binding = ArtworkScanBinding<Map<String, String>>()
        binding.publish(mapOf("song" to "folder"), ArtworkCacheLocation("dirA", 0))

        val task = binding.claim("song", 3)

        assertEquals("song", task?.id)
        assertEquals(3, task?.generation)
        assertEquals(ArtworkCacheLocation("dirA", 0), task?.location)
        assertEquals(mapOf("song" to "folder"), task?.index)
    }

    @Test
    fun `还没发布过批次时认领任务返回空`() {
        val binding = ArtworkScanBinding<Map<String, String>>()

        assertNull(binding.claim("song", 0))
        assertNull(binding.location())
    }

    @Test
    fun `换目录后旧任务仍指向旧目录的位置`() {
        val binding = ArtworkScanBinding<Map<String, String>>()
        binding.publish(mapOf("song" to "folderA"), ArtworkCacheLocation("dirA", 0))
        val oldTask = binding.claim("song", 1)!!

        // 用户切换到另一个目录，新的一批被发布
        binding.publish(mapOf("song" to "folderB"), ArtworkCacheLocation("dirB", 0))
        val newTask = binding.claim("song", 2)!!

        assertEquals(ArtworkCacheLocation("dirA", 0), oldTask.location)
        assertEquals(ArtworkCacheLocation("dirB", 0), newTask.location)
        assertNotEquals(oldTask.location, newTask.location)
    }

    @Test
    fun `换目录后旧任务仍用旧索引解析`() {
        val binding = ArtworkScanBinding<Map<String, String>>()
        binding.publish(mapOf("song" to "folderA"), ArtworkCacheLocation("dirA", 0))
        val oldTask = binding.claim("song", 1)!!

        binding.publish(mapOf("song" to "folderB"), ArtworkCacheLocation("dirB", 0))

        // 索引与位置配套：旧任务不会拿新目录的索引去解析，也不会写进新目录的缓存
        assertEquals(mapOf("song" to "folderA"), oldTask.index)
        assertEquals(ArtworkCacheLocation("dirA", 0), oldTask.location)
    }

    @Test
    fun `换代次后旧任务仍指向旧代次`() {
        val binding = ArtworkScanBinding<Map<String, String>>()
        binding.publish(mapOf("song" to "folder"), ArtworkCacheLocation("dirA", 0))
        val oldTask = binding.claim("song", 1)!!

        // 手动刷新：同一个目录推进一代
        binding.publish(mapOf("song" to "folder"), ArtworkCacheLocation("dirA", 1))
        val newTask = binding.claim("song", 2)!!

        assertEquals(ArtworkCacheLocation("dirA", 0), oldTask.location)
        assertEquals(ArtworkCacheLocation("dirA", 1), newTask.location)
    }

    @Test
    fun `发布新批次会整体替换旧的索引与位置`() {
        val binding = ArtworkScanBinding<Map<String, String>>()
        binding.publish(mapOf("a" to "folderA"), ArtworkCacheLocation("dirA", 0))

        binding.publish(mapOf("b" to "folderB"), ArtworkCacheLocation("dirB", 2))

        assertEquals(ArtworkCacheLocation("dirB", 2), binding.location())
        val task = binding.claim("b", 5)!!
        assertEquals(mapOf("b" to "folderB"), task.index)
    }

    @Test
    fun `认领任务不会改变已发布的位置`() {
        val binding = ArtworkScanBinding<Map<String, String>>()
        binding.publish(mapOf("a" to "folderA"), ArtworkCacheLocation("dirA", 0))

        binding.claim("a", 1)

        assertEquals(ArtworkCacheLocation("dirA", 0), binding.location())
    }
}
