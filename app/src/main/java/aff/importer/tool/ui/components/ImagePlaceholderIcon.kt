package aff.importer.tool.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 图片占位图标（曲目页与曲包页共用）。
 *
 * 为什么自己画：material-icons-core 里没有 Icons.Default.Image
 * （它属于 material-icons-extended 依赖），而本项目不引入额外依赖，
 * 因此按 Material 的图片图标造型手写一个 ImageVector。
 *
 * 填充色用中性灰：作为 Icon 使用时会被 tint 覆盖；
 * 作为 Painter 使用（例如 AsyncImage 的加载失败占位）时保留这个颜色，
 * 深浅主题下都能看清。
 */
val ImagePlaceholderIcon: ImageVector = ImageVector.Builder(
    name = "ImagePlaceholder",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = SolidColor(Color(0xFF8A8A8A)),
        pathFillType = PathFillType.EvenOdd
    ) {
        // 外框
        moveTo(3f, 5f)
        lineTo(21f, 5f)
        lineTo(21f, 19f)
        lineTo(3f, 19f)
        close()
        // 内框：EvenOdd 挖空后形成边框
        moveTo(5f, 7f)
        lineTo(5f, 17f)
        lineTo(19f, 17f)
        lineTo(19f, 7f)
        close()
        // 山峰
        moveTo(6f, 17f)
        lineTo(10f, 12.5f)
        lineTo(13f, 15.5f)
        lineTo(15.5f, 13.5f)
        lineTo(18f, 17f)
        close()
        // 太阳
        moveTo(15.5f, 8.5f)
        lineTo(16.5f, 9.5f)
        lineTo(15.5f, 10.5f)
        lineTo(14.5f, 9.5f)
        close()
    }
}.build()
