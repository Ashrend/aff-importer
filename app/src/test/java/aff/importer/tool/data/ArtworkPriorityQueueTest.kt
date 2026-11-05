package aff.importer.tool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPriorityQueueTest {

    @Test
    fun `按入队顺序取出`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b", "c"))
        assertEquals("a", q.poll())
        assertEquals("b", q.poll())
        assertEquals("c", q.poll())
        assertNull(q.poll())
    }

    @Test
    fun `入队自动去重`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b"))
        q.enqueue(listOf("a", "c"))
        assertEquals(3, q.size)
        assertEquals("a", q.poll())
        assertEquals("b", q.poll())
        assertEquals("c", q.poll())
    }

    @Test
    fun `prioritize 把指定项移到队首并保持给定顺序`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b", "c", "d"))
        q.prioritize(listOf("d", "b"))
        assertEquals("d", q.poll())
        assertEquals("b", q.poll())
        assertEquals("a", q.poll())
        assertEquals("c", q.poll())
    }

    @Test
    fun `prioritize 不影响不在队列里的项`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b"))
        q.prioritize(listOf("zzz"))
        assertEquals("a", q.poll())
        assertEquals("b", q.poll())
    }

    @Test
    fun `已被取走的项不受 prioritize 影响`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b", "c"))
        assertEquals("a", q.poll())
        q.prioritize(listOf("a"))
        assertEquals("b", q.poll())
        assertEquals("c", q.poll())
    }

    @Test
    fun `prioritize 传入重复 id 时只前插一次`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b", "c"))
        q.prioritize(listOf("a", "a"))
        assertEquals(3, q.size)
        assertEquals("a", q.poll())
        assertEquals("b", q.poll())
        assertEquals("c", q.poll())
        assertNull(q.poll())
    }

    @Test
    fun `clear 清空队列`() {
        val q = ArtworkPriorityQueue()
        q.enqueue(listOf("a", "b"))
        q.clear()
        assertEquals(0, q.size)
        assertNull(q.poll())
    }
}
