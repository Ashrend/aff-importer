package aff.importer.tool.data

import aff.importer.tool.data.model.ArtworkCacheLocation
import aff.importer.tool.data.model.ArtworkScanTask

/**
 * 扫描的「钉住」协议（纯逻辑，不加锁，线程安全由调用方的 Mutex 保证）
 *
 * 一次扫描把「索引 + 缓存位置」整体发布出来，worker 认领任务时把两者一起取走。
 *
 * 为什么必须整体发布：
 * - 索引决定「这个 id 对应哪个 SAF 文件」；
 * - 缓存位置决定「这个文件写到哪个缓存目录」。
 *
 * 两者一旦错配，就会出现「用 A 目录的图写进 B 目录的缓存」这类串图，
 * 而且是永久性的（缓存命中后不会再重新拷贝）。
 * 所以认领任务时必须一次性把两者都带走，之后不许再读环境状态
 * （环境状态随时可能因为用户切换目录而改变）。
 */
class ArtworkScanBinding<I : Any> {

    /** 当前发布的一批：索引 + 缓存位置 */
    private var current: Batch<I>? = null

    /**
     * 整体发布本批次的索引与缓存位置。
     *
     * 调用方必须在自己的锁内调用，保证「发布」与「worker 认领」不会交错。
     *
     * @param index 本批次的索引快照
     * @param location 本批次的缓存位置（目录标识 + 缓存代次）
     */
    fun publish(index: I, location: ArtworkCacheLocation) {
        current = Batch(index, location)
    }

    /** 当前发布的缓存位置；还没发布过任何批次时为 null */
    fun location(): ArtworkCacheLocation? = current?.location

    /**
     * 认领一个任务：把当前批次的索引与缓存位置一起带走。
     *
     * 调用方必须在自己的锁内调用，使认领与「取任务」成为同一个临界区。
     *
     * @param id 条目 id
     * @param generation 当前的扫描代次
     * @return 任务；还没发布过任何批次时返回 null
     */
    fun claim(id: String, generation: Int): ArtworkScanTask<I>? {
        val batch = current ?: return null
        return ArtworkScanTask(
            id = id,
            generation = generation,
            location = batch.location,
            index = batch.index
        )
    }

    /** 已发布的一批：索引 + 缓存位置 */
    private data class Batch<I : Any>(val index: I, val location: ArtworkCacheLocation)
}
