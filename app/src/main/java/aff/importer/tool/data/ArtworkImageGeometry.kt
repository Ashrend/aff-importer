package aff.importer.tool.data

/**
 * 等比缩放 + 居中裁剪的几何计算（纯逻辑，可单元测试）。
 *
 * 缓存里的缩略图必须和 UI 的显示效果一致：UI 用的是 ContentScale.Crop，
 * 也就是「先按目标比例裁掉多余部分，再铺满目标框」。
 * 如果直接把源图拉伸成目标尺寸，变形会被烤进缓存文件里，UI 之后再裁也救不回来。
 *
 * 规则：
 * 1. 在源图上取「目标比例」的最大居中矩形（源图比例与目标一致时就是整张图）；
 * 2. 源图比目标框大 → 等比缩小到目标框尺寸；
 * 3. 源图比目标框小 → 不放大，保持原分辨率（要放大由 UI 在显示时负责）。
 */
object ArtworkImageGeometry {

    /**
     * 裁剪与缩放方案。
     *
     * @param srcLeft 源图上裁剪矩形的左边界
     * @param srcTop 源图上裁剪矩形的上边界
     * @param srcWidth 裁剪矩形的宽
     * @param srcHeight 裁剪矩形的高
     * @param dstWidth 输出宽
     * @param dstHeight 输出高
     */
    data class CropPlan(
        val srcLeft: Int,
        val srcTop: Int,
        val srcWidth: Int,
        val srcHeight: Int,
        val dstWidth: Int,
        val dstHeight: Int
    )

    /**
     * 计算裁剪与缩放方案。
     *
     * @param srcWidth 源图宽
     * @param srcHeight 源图高
     * @param targetWidth 目标宽（曲绘 256、曲包封面 512）
     * @param targetHeight 目标高（曲绘 256、曲包封面 256）
     * @return 方案；任一参数非正数时返回 null
     */
    fun plan(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): CropPlan? {
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }

        // 1. 按目标比例取最大的居中裁剪矩形
        //    用交叉相乘比较比例，避免浮点误差
        val cropWidth: Int
        val cropHeight: Int
        if (srcWidth.toLong() * targetHeight > srcHeight.toLong() * targetWidth) {
            // 源图更宽：按高度裁剪
            cropHeight = srcHeight
            cropWidth = (srcHeight.toLong() * targetWidth / targetHeight)
                .toInt()
                .coerceIn(1, srcWidth)
        } else {
            // 源图更高，或比例与目标一致：按宽度裁剪
            cropWidth = srcWidth
            cropHeight = (srcWidth.toLong() * targetHeight / targetWidth)
                .toInt()
                .coerceIn(1, srcHeight)
        }

        // 2. 缩放：把裁剪矩形缩到目标框；源图比目标框小时不放大
        val scale = minOf(
            targetWidth.toDouble() / cropWidth,
            targetHeight.toDouble() / cropHeight
        )
        val dstWidth: Int
        val dstHeight: Int
        if (scale >= 1.0) {
            // 源图比目标框小：保持原分辨率，不放大
            dstWidth = cropWidth
            dstHeight = cropHeight
        } else {
            // 源图比目标框大：等比缩小到目标框
            dstWidth = targetWidth
            dstHeight = targetHeight
        }

        // 3. 居中
        val left = (srcWidth - cropWidth) / 2
        val top = (srcHeight - cropHeight) / 2
        return CropPlan(left, top, cropWidth, cropHeight, dstWidth, dstHeight)
    }
}
