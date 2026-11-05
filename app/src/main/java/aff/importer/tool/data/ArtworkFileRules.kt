package aff.importer.tool.data

/**
 * 谱面目录内图片文件的命名规则（纯逻辑，可单元测试）
 *
 * 只做"文件名层面的判定"，不接触任何 Android 类型。
 */
object ArtworkFileRules {

    /**
     * 曲绘候选文件名，按优先级从高到低排列。
     *
     * 先 256 缩略图，再全尺寸图；同一尺寸下 jpg 优先于 png。
     */
    val SONG_JACKET_PRIORITY: List<String> = listOf(
        "base_256.jpg",
        "1080_base_256.jpg",
        "base.jpg",
        "1080_base.jpg",
        "base_256.png",
        "1080_base_256.png",
        "base.png",
        "1080_base.png"
    )

    /**
     * 可以直接按字节复制进缓存的曲绘文件。
     *
     * 只有本身就是 256px 的 JPEG 才行：缓存统一存 JPEG，
     * PNG（base_256.png / 1080_base_256.png）必须解码后转码成 JPEG，
     * 直接复制字节会往 .jpg 文件里塞 PNG 数据。
     */
    val SONG_JACKET_BYTE_COPY: Set<String> = setOf("base_256.jpg", "1080_base_256.jpg")

    /** 视为图片的扩展名（小写） */
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    /** append 类型曲包的类型标识：封面用 pack/1080/ 下的分隔图 */
    const val APPEND_PACK_TYPE = "append"

    /** 标准曲包封面的文件名前缀 */
    private const val SELECT_PREFIX = "select_"

    /** 1080 尺寸标准曲包封面的文件名前缀 */
    private const val SELECT_1080_PREFIX = "1080_select_"

    /** append 类型曲包分隔图的文件名前缀 */
    private const val DIVIDER_PREFIX = "divider_"

    /**
     * 选中的曲绘文件
     *
     * @param fileName 目录下的实际文件名
     * @param isThumbnail 是否为「256px 的 JPEG 缩略图」，可以直接按字节复制进缓存
     */
    data class SongJacket(val fileName: String, val isThumbnail: Boolean)

    /**
     * 曲包封面候选文件的种类
     */
    enum class PackBannerKind {
        /** pack/select_&lt;id&gt;.&lt;ext&gt; */
        STANDARD,

        /** pack/1080_select_&lt;id&gt;.&lt;ext&gt; */
        STANDARD_1080,

        /** pack/1080/divider_&lt;id&gt;.&lt;ext&gt;（append 类型曲包专用） */
        DIVIDER
    }

    /**
     * 曲包封面的来源位置类别
     */
    enum class PackBannerSource {
        /** 标准候选，在 pack/ 下 */
        STANDARD,

        /** 分隔图，在 pack/1080/ 下 */
        DIVIDER
    }

    /**
     * 解析出来的曲包封面候选
     *
     * @param packId 曲包 id
     * @param kind 候选种类
     */
    data class PackBannerCandidate(val packId: String, val kind: PackBannerKind)

    /**
     * 选中的曲包封面候选
     *
     * @param fileName 选中的文件名
     * @param source 该文件所在的位置类别
     */
    data class PackBannerSelection(val fileName: String, val source: PackBannerSource)

    /**
     * 从目录下的文件名集合中挑出最合适的曲绘文件。
     *
     * 按 [SONG_JACKET_PRIORITY] 的顺序取第一个命中的；
     * 八个候选都不存在时兜底取目录里任意一张图片（jpg / jpeg / png）。
     */
    fun selectSongJacket(names: Collection<String>): SongJacket? {
        val available = names.toSet()
        SONG_JACKET_PRIORITY.forEach { candidate ->
            if (candidate in available) {
                return SongJacket(candidate, isThumbnail = candidate in SONG_JACKET_BYTE_COPY)
            }
        }

        // 兜底：目录里任意一张图片。
        // 先按文件名排序再取第一个，保证同样的目录内容总是选出同一张图
        // （上游用的是文件系统顺序，不可复现）。
        val fallback = names
            .filter { extensionOf(it) in IMAGE_EXTENSIONS }
            .sortedBy { it }
            .firstOrNull()
            ?: return null
        return SongJacket(fallback, isThumbnail = fallback in SONG_JACKET_BYTE_COPY)
    }

    /**
     * 标准曲包封面候选文件名，按优先级从高到低。
     *
     * 1080 尺寸的变体优先于普通变体，png 优先于 jpg / jpeg。
     */
    fun packBannerCandidates(packId: String): List<String> = listOf(
        "1080_select_$packId.png",
        "select_$packId.png",
        "1080_select_$packId.jpg",
        "select_$packId.jpg",
        "1080_select_$packId.jpeg",
        "select_$packId.jpeg"
    )

    /**
     * append 类型曲包的分隔图候选文件名，按优先级从高到低。
     * 这些文件位于 pack/1080/ 目录下。
     */
    fun packDividerCandidates(packId: String): List<String> = listOf(
        "divider_$packId.png",
        "divider_$packId.jpg",
        "divider_$packId.jpeg"
    )

    /**
     * 选出曲包封面应该用的源文件。
     *
     * append 类型曲包的封面在 pack/1080/ 下的分隔图里：
     * 只要 pack/1080/ 目录存在，就只认分隔图，找不到就没有封面
     * （不退回标准候选，免得显示成别的曲包的图）；
     * pack/1080/ 目录不存在时才走标准候选。
     *
     * @param packId 曲包 id
     * @param packType 曲包类型；"append" 表示用分隔图
     * @param hasDividerFolder pack/1080/ 目录是否存在
     * @param standardNames pack/ 目录下实际存在的文件名
     * @param dividerNames pack/1080/ 目录下实际存在的文件名
     * @return 选中的候选；没有可用封面时为 null
     */
    fun selectPackBanner(
        packId: String,
        packType: String?,
        hasDividerFolder: Boolean,
        standardNames: Collection<String>,
        dividerNames: Collection<String>
    ): PackBannerSelection? {
        val isAppend = packType?.lowercase() == APPEND_PACK_TYPE
        if (isAppend && hasDividerFolder) {
            // append 类型不参与同层级 fallback：找不到分隔图就是没有封面
            val divider = packDividerCandidates(packId).firstOrNull { it in dividerNames }
                ?: return null
            return PackBannerSelection(divider, PackBannerSource.DIVIDER)
        }

        // 标准曲包：六个候选里取第一个命中的，没有就真的没有封面
        val standard = packBannerCandidates(packId).firstOrNull { it in standardNames }
            ?: return null
        return PackBannerSelection(standard, PackBannerSource.STANDARD)
    }

    /**
     * 解析曲包封面文件名中的曲包 id。
     * 形如 `select_&lt;id&gt;.&lt;扩展名&gt;`；不是该前缀、去掉扩展名后为空时返回 null。
     */
    fun parsePackBannerId(fileName: String): String? {
        if (!fileName.startsWith(SELECT_PREFIX)) return null
        val raw = fileName.removePrefix(SELECT_PREFIX)
        val dotIndex = raw.lastIndexOf('.')
        val id = if (dotIndex >= 0) raw.substring(0, dotIndex) else raw
        return if (id.isBlank()) null else id
    }

    /**
     * 解析曲包封面候选文件名。
     *
     * 识别 `select_&lt;id&gt;.&lt;ext&gt;`、`1080_select_&lt;id&gt;.&lt;ext&gt;`
     * 与 `divider_&lt;id&gt;.&lt;ext&gt;`；扩展名不是图片扩展名时返回 null。
     */
    fun parsePackBannerCandidate(fileName: String): PackBannerCandidate? {
        if (extensionOf(fileName) !in IMAGE_EXTENSIONS) return null

        val stem = fileName.substringBeforeLast('.')
        val kind: PackBannerKind
        val id: String
        when {
            stem.startsWith(DIVIDER_PREFIX) -> {
                kind = PackBannerKind.DIVIDER
                id = stem.removePrefix(DIVIDER_PREFIX)
            }
            stem.startsWith(SELECT_1080_PREFIX) -> {
                kind = PackBannerKind.STANDARD_1080
                id = stem.removePrefix(SELECT_1080_PREFIX)
            }
            else -> {
                kind = PackBannerKind.STANDARD
                id = parsePackBannerId(fileName) ?: return null
            }
        }
        if (id.isBlank()) return null
        return PackBannerCandidate(id, kind)
    }

    /**
     * 取文件名的小写扩展名；没有扩展名时返回空串。
     */
    fun extensionOf(fileName: String): String = fileName.substringAfterLast(".", "").lowercase()

    /**
     * 清洗 id，只保留字母、数字、下划线和连字符，其余替换为下划线。
     * 用于生成安全的缓存文件名，避免 id 里的分隔符造成目录穿越。
     */
    fun sanitizeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** 缓存文件名 = 清洗后的 id + jpg 后缀 */
    fun cacheFileName(id: String): String = "${sanitizeId(id)}.jpg"
}
