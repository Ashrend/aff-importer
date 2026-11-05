package aff.importer.tool.data.model

/**
 * 应用内缓存的图片种类
 * 每个种类拥有独立的缓存命名空间与代次，互不影响
 */
enum class ArtworkKind(val namespace: String) {
    /** 曲目曲绘 */
    SONG_JACKET("songs"),

    /** 曲包封面 */
    PACK_BANNER("packs")
}
