package aff.importer.tool.data.model

/**
 * 图片扫描进度
 *
 * 独立于曲目/曲包列表状态，单独用 StateFlow 发布，
 * 这样进度变化不会触发列表整体重组。
 */
sealed interface ArtworkScanState {
    /** 未开始 */
    data object Idle : ArtworkScanState

    /** 扫描中 */
    data class Scanning(val completed: Int, val total: Int) : ArtworkScanState

    /** 扫描完成 */
    data class Completed(val total: Int) : ArtworkScanState
}
