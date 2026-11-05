package aff.importer.tool.data

/**
 * 谱面目录内图片文件的命名规则（纯逻辑，可单元测试）
 *
 * 只做"文件名层面的判定"，不接触任何 Android 类型。
 */
object ArtworkFileRules {

    /**
     * 曲绘候选文件名，按优先级从高到低排列。
     * 前两项是 256 缩略图，后两项是全尺寸图。
     */
    val SONG_JACKET_PRIORITY: List<String> =
        listOf("base_256.jpg", "1080_base_256.jpg", "base.jpg", "1080_base.jpg")

    /**
     * 选中的曲绘文件
     *
     * @param fileName 目录下的实际文件名
     * @param isThumbnail 是否为缩略图（前两项优先候选属于缩略图）
     */
    data class SongJacket(val fileName: String, val isThumbnail: Boolean)

    /**
     * 从目录下的文件名集合中挑出最合适的曲绘文件。
     * 按 [SONG_JACKET_PRIORITY] 的顺序取第一个命中的；都不存在时返回 null。
     */
    fun selectSongJacket(names: Collection<String>): SongJacket? {
        val available = names.toSet()
        SONG_JACKET_PRIORITY.forEachIndexed { index, candidate ->
            if (candidate in available) {
                return SongJacket(candidate, isThumbnail = index < 2)
            }
        }
        return null
    }

    /**
     * 解析曲包封面文件名中的曲包 id。
     * 形如 `select_<id>.<扩展名>`；不是该前缀、去掉扩展名后为空时返回 null。
     */
    fun parsePackBannerId(fileName: String): String? {
        if (!fileName.startsWith("select_")) return null
        val raw = fileName.removePrefix("select_")
        val dotIndex = raw.lastIndexOf('.')
        val id = if (dotIndex >= 0) raw.substring(0, dotIndex) else raw
        return if (id.isBlank()) null else id
    }

    /**
     * 清洗 id，只保留字母、数字、下划线和连字符，其余替换为下划线。
     * 用于生成安全的缓存文件名，避免 id 里的分隔符造成目录穿越。
     */
    fun sanitizeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** 缓存文件名 = 清洗后的 id + jpg 后缀 */
    fun cacheFileName(id: String): String = "${sanitizeId(id)}.jpg"
}
