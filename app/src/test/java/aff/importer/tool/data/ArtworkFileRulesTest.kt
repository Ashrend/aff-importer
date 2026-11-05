package aff.importer.tool.data

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

    @Test
    fun `曲绘候选是八个文件且顺序固定`() {
        assertEquals(
            listOf(
                "base_256.jpg",
                "1080_base_256.jpg",
                "base.jpg",
                "1080_base.jpg",
                "base_256.png",
                "1080_base_256.png",
                "base.png",
                "1080_base.png"
            ),
            ArtworkFileRules.SONG_JACKET_PRIORITY
        )
        assertEquals(8, ArtworkFileRules.SONG_JACKET_PRIORITY.size)
    }

    @Test
    fun `八个候选都在时取优先级最高的那个`() {
        val result = ArtworkFileRules.selectSongJacket(ArtworkFileRules.SONG_JACKET_PRIORITY)
        assertEquals("base_256.jpg", result?.fileName)
    }

    @Test
    fun `没有 jpg 候选时选中 png 候选`() {
        val result = ArtworkFileRules.selectSongJacket(listOf("base_256.png", "1080_base_256.png"))
        assertEquals("base_256.png", result?.fileName)
        assertFalse(result!!.isThumbnail)
    }

    @Test
    fun `只有 png 候选时按优先级选 base png`() {
        val result = ArtworkFileRules.selectSongJacket(listOf("1080_base.png", "base.png"))
        assertEquals("base.png", result?.fileName)
        assertFalse(result!!.isThumbnail)
    }

    @Test
    fun `目录里只有 cover png 时走兜底`() {
        val result = ArtworkFileRules.selectSongJacket(listOf("diff.aff", "cover.png"))
        assertEquals("cover.png", result?.fileName)
        assertFalse(result!!.isThumbnail)
    }

    @Test
    fun `目录里只有 something jpeg 时走兜底`() {
        val result = ArtworkFileRules.selectSongJacket(listOf("something.jpeg"))
        assertEquals("something.jpeg", result?.fileName)
        assertFalse(result!!.isThumbnail)
    }

    @Test
    fun `兜底不会选中非图片文件`() {
        assertNull(ArtworkFileRules.selectSongJacket(listOf("diff.aff", "preview.ogg")))
        assertNull(ArtworkFileRules.selectSongJacket(listOf("diff.aff", "preview.ogg", "readme.txt")))
    }

    @Test
    fun `兜底结果是确定的`() {
        val names = listOf("preview.ogg", "cover.png", "art.jpg", "diff.aff")
        val first = ArtworkFileRules.selectSongJacket(names)
        // 顺序打乱也要选出同一张
        val second = ArtworkFileRules.selectSongJacket(names.shuffled())
        assertEquals("art.jpg", first?.fileName)
        assertEquals(first?.fileName, second?.fileName)
    }

    @Test
    fun `只有 jpg 的 256 缩略图能按字节复制`() {
        assertTrue(ArtworkFileRules.selectSongJacket(listOf("base_256.jpg"))!!.isThumbnail)
        assertTrue(ArtworkFileRules.selectSongJacket(listOf("1080_base_256.jpg"))!!.isThumbnail)
        // PNG 必须转码，不能直接复制字节
        assertFalse(ArtworkFileRules.selectSongJacket(listOf("base_256.png"))!!.isThumbnail)
        assertFalse(ArtworkFileRules.selectSongJacket(listOf("1080_base_256.png"))!!.isThumbnail)
        assertFalse(ArtworkFileRules.selectSongJacket(listOf("base.jpg"))!!.isThumbnail)
        assertFalse(ArtworkFileRules.selectSongJacket(listOf("1080_base.jpg"))!!.isThumbnail)
    }

    @Test
    fun `标准曲包封面候选按优先级排列`() {
        assertEquals(
            listOf(
                "1080_select_x.png",
                "select_x.png",
                "1080_select_x.jpg",
                "select_x.jpg",
                "1080_select_x.jpeg",
                "select_x.jpeg"
            ),
            ArtworkFileRules.packBannerCandidates("x")
        )
    }

    @Test
    fun `append 曲包分隔图候选按优先级排列`() {
        assertEquals(
            listOf("divider_x.png", "divider_x.jpg", "divider_x.jpeg"),
            ArtworkFileRules.packDividerCandidates("x")
        )
    }

    @Test
    fun `append 曲包在 1080 目录存在时用分隔图`() {
        val selection = ArtworkFileRules.selectPackBanner(
            packId = "x",
            packType = "append",
            hasDividerFolder = true,
            standardNames = listOf("select_x.png"),
            dividerNames = listOf("divider_x.png", "divider_x.jpg")
        )
        assertEquals("divider_x.png", selection?.fileName)
        assertEquals(ArtworkFileRules.PackBannerSource.DIVIDER, selection?.source)
    }

    @Test
    fun `append 曲包在 1080 目录存在但没有分隔图时没有封面`() {
        val selection = ArtworkFileRules.selectPackBanner(
            packId = "x",
            packType = "append",
            hasDividerFolder = true,
            standardNames = listOf("select_x.png"),
            dividerNames = emptyList()
        )
        assertNull(selection)
    }

    @Test
    fun `append 曲包在 1080 目录不存在时用标准候选`() {
        val selection = ArtworkFileRules.selectPackBanner(
            packId = "x",
            packType = "append",
            hasDividerFolder = false,
            standardNames = listOf("select_x.png"),
            dividerNames = emptyList()
        )
        assertEquals("select_x.png", selection?.fileName)
        assertEquals(ArtworkFileRules.PackBannerSource.STANDARD, selection?.source)
    }

    @Test
    fun `标准曲包优先选 1080 变体且没有兜底`() {
        val selection = ArtworkFileRules.selectPackBanner(
            packId = "x",
            packType = "pack",
            hasDividerFolder = false,
            standardNames = listOf("select_x.jpg", "1080_select_x.png"),
            dividerNames = emptyList()
        )
        assertEquals("1080_select_x.png", selection?.fileName)
        assertEquals(ArtworkFileRules.PackBannerSource.STANDARD, selection?.source)

        // 标准曲包只有这六个候选，别的名字不算封面
        assertNull(
            ArtworkFileRules.selectPackBanner(
                packId = "x",
                packType = "pack",
                hasDividerFolder = false,
                standardNames = listOf("banner_x.png"),
                dividerNames = emptyList()
            )
        )
    }

    @Test
    fun `曲包封面候选名能解析出 id 与种类`() {
        val standard = ArtworkFileRules.parsePackBannerCandidate("select_001.png")
        assertEquals("001", standard?.packId)
        assertEquals(ArtworkFileRules.PackBannerKind.STANDARD, standard?.kind)

        val standard1080 = ArtworkFileRules.parsePackBannerCandidate("1080_select_001.jpg")
        assertEquals("001", standard1080?.packId)
        assertEquals(ArtworkFileRules.PackBannerKind.STANDARD_1080, standard1080?.kind)

        val divider = ArtworkFileRules.parsePackBannerCandidate("divider_001.jpeg")
        assertEquals("001", divider?.packId)
        assertEquals(ArtworkFileRules.PackBannerKind.DIVIDER, divider?.kind)

        // 不是图片扩展名或前缀不对都返回 null
        assertNull(ArtworkFileRules.parsePackBannerCandidate("select_001.txt"))
        assertNull(ArtworkFileRules.parsePackBannerCandidate("banner_001.png"))
        assertNull(ArtworkFileRules.parsePackBannerCandidate("select_.png"))
    }

    @Test
    fun `扩展名取最后一个点之后的小写`() {
        assertEquals("png", ArtworkFileRules.extensionOf("base.PNG"))
        assertEquals("jpeg", ArtworkFileRules.extensionOf("a.b.jpeg"))
        assertEquals("", ArtworkFileRules.extensionOf("diff."))
        assertEquals("", ArtworkFileRules.extensionOf("noext"))
    }
}
