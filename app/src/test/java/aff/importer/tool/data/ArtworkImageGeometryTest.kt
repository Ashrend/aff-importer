package aff.importer.tool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkImageGeometryTest {

    @Test
    fun `尺寸完全一致时不裁剪也不缩放`() {
        val plan = ArtworkImageGeometry.plan(256, 256, 256, 256)
        assertNotNull(plan)
        assertEquals(0, plan!!.srcLeft)
        assertEquals(0, plan.srcTop)
        assertEquals(256, plan.srcWidth)
        assertEquals(256, plan.srcHeight)
        assertEquals(256, plan.dstWidth)
        assertEquals(256, plan.dstHeight)
    }

    @Test
    fun `源图比目标宽时居中裁掉左右两侧`() {
        val plan = ArtworkImageGeometry.plan(512, 256, 256, 256)
        assertNotNull(plan)
        assertEquals(128, plan!!.srcLeft)
        assertEquals(0, plan.srcTop)
        assertEquals(256, plan.srcWidth)
        assertEquals(256, plan.srcHeight)
        assertEquals(256, plan.dstWidth)
        assertEquals(256, plan.dstHeight)
    }

    @Test
    fun `源图比目标高时居中裁掉上下两侧`() {
        val plan = ArtworkImageGeometry.plan(256, 512, 256, 256)
        assertNotNull(plan)
        assertEquals(0, plan!!.srcLeft)
        assertEquals(128, plan.srcTop)
        assertEquals(256, plan.srcWidth)
        assertEquals(256, plan.srcHeight)
        assertEquals(256, plan.dstWidth)
        assertEquals(256, plan.dstHeight)
    }

    @Test
    fun `源图比目标小时不放大`() {
        val plan = ArtworkImageGeometry.plan(128, 128, 256, 256)
        assertNotNull(plan)
        assertEquals(0, plan!!.srcLeft)
        assertEquals(0, plan.srcTop)
        assertEquals(128, plan.srcWidth)
        assertEquals(128, plan.srcHeight)
        assertEquals(128, plan.dstWidth)
        assertEquals(128, plan.dstHeight)
    }

    @Test
    fun `正方形源图放进横幅目标时裁剪而不是拉伸`() {
        val plan = ArtworkImageGeometry.plan(256, 256, 512, 256)
        assertNotNull(plan)
        // 宽度保持 256，高度裁掉一半
        assertEquals(256, plan!!.srcWidth)
        assertEquals(128, plan.srcHeight)
        // 上下居中
        assertEquals(0, plan.srcLeft)
        assertEquals(64, plan.srcTop)
        // 输出仍是 2:1，没有被拉成 512x256 的变形图
        assertEquals(256, plan.dstWidth)
        assertEquals(128, plan.dstHeight)
    }

    @Test
    fun `大尺寸正方形源图放进横幅目标时等比缩小到目标框`() {
        val plan = ArtworkImageGeometry.plan(1024, 1024, 512, 256)
        assertNotNull(plan)
        // 按宽度保留，高度裁一半
        assertEquals(1024, plan!!.srcWidth)
        assertEquals(512, plan.srcHeight)
        assertEquals(0, plan.srcLeft)
        assertEquals(256, plan.srcTop)
        // 缩小后正好是目标框
        assertEquals(512, plan.dstWidth)
        assertEquals(256, plan.dstHeight)
    }

    @Test
    fun `参数非法时返回 null`() {
        assertNull(ArtworkImageGeometry.plan(0, 256, 256, 256))
        assertNull(ArtworkImageGeometry.plan(256, -1, 256, 256))
        assertNull(ArtworkImageGeometry.plan(256, 256, 0, 256))
        assertNull(ArtworkImageGeometry.plan(256, 256, 256, 0))
    }
}
