package aff.importer.tool.data.model

/**
 * 一个已认领的扫描任务。
 *
 * 任务在认领的那一刻就把「扫描代次 + 缓存位置 + 索引快照」一起带走：
 * 之后即使目录切换或换代次，它也只对着自己带走的位置读写缓存，
 * 绝不会把上一个目录的曲绘写进下一个目录的缓存目录。
 *
 * @param id 条目 id：曲目为实际文件夹 id，曲包为 pack id
 * @param generation 认领时的扫描代次：解析完成后要再比对一次，代次变了就丢弃结果
 * @param location 钉住的缓存位置（目录标识 + 缓存代次），读写缓存只看它
 * @param index 认领时的索引快照：与 [location] 来自同一批发布，两者永远配套
 */
data class ArtworkScanTask<I : Any>(
    val id: String,
    val generation: Int,
    val location: ArtworkCacheLocation,
    val index: I
)
