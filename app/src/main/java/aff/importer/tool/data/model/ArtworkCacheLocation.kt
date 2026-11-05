package aff.importer.tool.data.model

/**
 * 应用内图片缓存的一个「位置」：目录标识 + 该目录标识下的缓存代次。
 *
 * 位置是不可变值。扫描任务认领时把自己的位置一起带走，
 * 之后目录切换或换代次都影响不到它 —— 任务因此绝不会把上一个目录的曲绘
 * 写进下一个目录的缓存目录。
 *
 * @param directoryKey 目录标识（由 `ArtworkCachePaths.directoryKey` 从目标目录 Uri 派生）
 * @param generation 该目录标识下的缓存代次
 */
data class ArtworkCacheLocation(
    val directoryKey: String,
    val generation: Int
)
