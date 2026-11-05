package aff.importer.tool.data

import aff.importer.tool.data.ArtworkFileRules.SongJacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkFileRulesTest {

    @Test
    fun `选曲绘时优先 base_256 jpg`() {
        val result = ArtworkFileRules.selectSongJacket(
            listOf("base.jpg", "base_256.jpg", "1080_base_256.jpg")
        )
        assertEquals("base_256.jpg", result?.fileName)
        assertTrue(result!!.isThumbnail)
    }

    @Test
    fun `没有 256 缩略图时选 1080 base 256`() {
        val result = ArtworkFileRules.selectSongJacket(
            listOf("base.jpg", "1080_base_256.jpg", "diff.aff", "preview.ogg")
        )
        assertEquals("1080_base_256.jpg", result?.fileName)
        assertTrue(result!!.isThumbnail)
    }

    @Test
    fun `只有全尺寸图时选 base jpg 且不是缩略图`() {
        val result = ArtworkFileRules.selectSongJacket(listOf("diff.aff", "base.jpg"))
        assertEquals("base.jpg", result?.fileName)
        assertFalse(result!!.isThumbnail)
    }

    @Test
    fun `只有 1080 base 时选中它`() {
        val result = ArtworkFileRules.selectSongJacket(listOf("1080_base.jpg"))
        assertEquals("1080_base.jpg", result?.fileName)
        assertFalse(result!!.isThumbnail)
    }

    @Test
    fun `没有任何曲绘文件时返回 null`() {
        assertNull(ArtworkFileRules.selectSongJacket(listOf("diff.aff", "preview.ogg")))
        assertNull(ArtworkFileRules.selectSongJacket(emptyList()))
    }

    @Test
    fun `曲包封面名能解析出 id`() {
        assertEquals("001", ArtworkFileRules.parsePackBannerId("select_001.png"))
        assertEquals("my_pack", ArtworkFileRules.parsePackBannerId("select_my_pack.png"))
        assertEquals("a.b", ArtworkFileRules.parsePackBannerId("select_a.b.png"))
        assertEquals("pack_x", ArtworkFileRules.parsePackBannerId("select_pack_x.jpg"))
    }

    @Test
    fun `非 select 前缀的曲包文件返回 null`() {
        assertNull(ArtworkFileRules.parsePackBannerId("banner.png"))
        assertNull(ArtworkFileRules.parsePackBannerId("select_.png"))
        assertNull(ArtworkFileRules.parsePackBannerId(""))
    }

    @Test
    fun `id 清洗只保留字母数字下划线和连字符`() {
        assertEquals("my_song-01", ArtworkFileRules.sanitizeId("my_song-01"))
        assertEquals("a_b_c", ArtworkFileRules.sanitizeId("a/b\\c"))
        assertEquals("x", ArtworkFileRules.sanitizeId("x"))
    }

    @Test
    fun `缓存文件名是清洗后的 id 加 jpg 后缀`() {
        assertEquals("my_song-01.jpg", ArtworkFileRules.cacheFileName("my_song-01"))
        assertEquals("a_b_c.jpg", ArtworkFileRules.cacheFileName("a/b\\c"))
    }
}
