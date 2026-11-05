package aff.importer.tool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkStateStoreTest {

    @Test
    fun `prepare 为每个 id 建立状态槽`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a", "b"))
        assertTrue(store.contains("a"))
        assertTrue(store.contains("b"))
        assertFalse(store.contains("c"))
    }

    @Test
    fun `未赋值的条目读到 null`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a"))
        assertNull(store.valueFor("a"))
    }

    @Test
    fun `set 之后能读到值`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a"))
        store.set("a", "uri")
        assertEquals("uri", store.valueFor("a"))
    }

    @Test
    fun `重复 prepare 不会覆盖已有值`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a"))
        store.set("a", "uri")
        store.prepare(listOf("a"))
        assertEquals("uri", store.valueFor("a"))
    }

    @Test
    fun `remove 之后条目不再存在`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a"))
        store.remove("a")
        assertFalse(store.contains("a"))
        assertNull(store.valueFor("a"))
    }

    @Test
    fun `clear 清空所有条目`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a", "b"))
        store.clear()
        assertEquals(0, store.ids.size)
    }

    @Test
    fun `retainOnly 丢弃不在列表里的条目但保留其它条目`() {
        val store = ArtworkStateStore<String>()
        store.prepare(listOf("a", "b"))
        store.set("a", "uri-a")
        store.retainOnly(listOf("a", "c"))
        assertTrue(store.contains("a"))
        assertFalse(store.contains("b"))
        assertEquals("uri-a", store.valueFor("a"))
    }
}
