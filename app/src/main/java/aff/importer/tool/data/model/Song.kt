 package aff.importer.tool.data.model

import com.google.gson.JsonObject

/**
 * Arcaea 曲目完整数据类
 * 根据官方 songlist 格式文档实现
 */
data class Song(
    // 必需字段
    val idx: Int = 0,
    val id: String,
    val titleLocalized: LocalizedText = LocalizedText(),
    val artist: String = "",
    val artistLocalized: LocalizedText = LocalizedText(),
    val bpm: String = "",
    val bpmBase: Double = 0.0,
    val set: String = "",
    val purchase: String = "",
    val audioPreview: Int = 0,
    val audioPreviewEnd: Int = 0,
    val side: Int = 0,
    val bg: String = "",
    val date: Long = 0L,
    
    // 可选字段
    val category: String = "",
    val bgInverse: String = "",
    val bgDaynight: BgDaynight? = null,
    val version: String = "",
    val worldUnlock: Boolean = false,
    val remoteDl: Boolean = false,
    val bydLocalUnlock: Boolean = false,
    val songlistHidden: Boolean = false,
    val noPp: Boolean = false,
    val sourceLocalized: LocalizedText = LocalizedText(),
    val sourceCopyright: String = "",
    val noStream: Boolean = false,
    val jacketLocalized: Map<String, Boolean> = emptyMap(),
    
    // 难度数组
    val difficulties: List<Difficulty> = emptyList()
) {
    /**
     * 背景白天/夜晚配置
     */
    data class BgDaynight(
        val day: String = "",
        val night: String = ""
    )
    
    /**
     * 难度信息
     */
    data class Difficulty(
        // 必需字段
        val ratingClass: Int, // 0=PST, 1=PRS, 2=FTR, 3=BYD, 4=ETR
        val chartDesigner: String = "",
        val jacketDesigner: String = "",
        val rating: Int = 0,
        
        // 可选字段
        val ratingPlus: Boolean = false,
        val legacy11: Boolean = false,
        val plusFingers: Boolean = false,
        val titleLocalized: LocalizedText = LocalizedText(),
        val artist: String = "",
        val artistLocalized: LocalizedText = LocalizedText(),
        val bpm: String = "",
        val bpmBase: Double = 0.0,
        val jacketNight: String = "",
        val jacketOverride: Boolean = false,
        val audioOverride: Boolean = false,
        val hiddenUntil: String = "",
        val bg: String = "",
        val bgInverse: String = "",
        val worldUnlock: Boolean = false,
        val date: Long = 0L,
        val version: String = ""
    ) {
        /**
         * 获取难度显示用的等级字符串，如 "7" 或 "9+"
         */
        fun getRatingString(): String {
            if (rating < 0) return "?"
            val base = rating.toString()
            return if (ratingPlus) "$base+" else base
        }
        
        /**
         * 获取显示用的标题（优先使用 title_localized.en）
         */
        fun getDisplayTitle(): String {
            return titleLocalized.getDefault().takeIf { it.isNotBlank() } ?: ""
        }
        
        /**
         * 获取显示用的艺术家（优先使用 artist_localized.en，其次 artist）
         */
        fun getDisplayArtist(): String {
            return artistLocalized.getDefault().takeIf { it.isNotBlank() } ?: artist
        }
    }

    companion object {
        /**
         * 从 JsonObject 解析 Song
         */
        fun fromJsonObject(json: JsonObject): Song {
            // 必需字段
            val idx = json.get("idx")?.asInt ?: 0
            val id = json.get("id")?.asString ?: ""
            val titleLocalized = parseLocalizedText(json.getAsJsonObject("title_localized"))
            val artist = json.get("artist")?.asString ?: ""
            val artistLocalized = parseLocalizedText(json.getAsJsonObject("artist_localized"))
            val bpm = json.get("bpm")?.asString ?: ""
            val bpmBase = json.get("bpm_base")?.asDouble ?: 0.0
            val set = json.get("set")?.asString ?: ""
            val purchase = json.get("purchase")?.asString ?: ""
            val audioPreview = json.get("audioPreview")?.asInt ?: 0
            val audioPreviewEnd = json.get("audioPreviewEnd")?.asInt ?: 0
            val side = json.get("side")?.asInt ?: 0
            val bg = json.get("bg")?.asString ?: ""
            val date = json.get("date")?.asLong ?: 0L
            
            // 可选字段
            val category = json.get("category")?.asString ?: ""
            val bgInverse = json.get("bg_inverse")?.asString ?: ""
            val bgDaynight = json.getAsJsonObject("bg_daynight")?.let {
                BgDaynight(
                    day = it.get("day")?.asString ?: "",
                    night = it.get("night")?.asString ?: ""
                )
            }
            val version = json.get("version")?.asString ?: ""
            val worldUnlock = json.get("world_unlock")?.asBoolean ?: false
            val remoteDl = json.get("remote_dl")?.asBoolean ?: false
            val bydLocalUnlock = json.get("byd_local_unlock")?.asBoolean ?: false
            val songlistHidden = json.get("songlist_hidden")?.asBoolean ?: false
            val noPp = json.get("no_pp")?.asBoolean ?: false
            val sourceLocalized = parseLocalizedText(json.getAsJsonObject("source_localized"))
            val sourceCopyright = json.get("source_copyright")?.asString ?: ""
            val noStream = json.get("no_stream")?.asBoolean ?: false
            
            // jacket_localized
            val jacketLocalized = mutableMapOf<String, Boolean>()
            json.getAsJsonObject("jacket_localized")?.let { obj ->
                obj.entrySet().forEach { (key, value) ->
                    jacketLocalized[key] = value.asBoolean
                }
            }

            // 解析难度数组
            val difficulties = mutableListOf<Difficulty>()
            json.getAsJsonArray("difficulties")?.forEach { element ->
                if (element.isJsonObject) {
                    difficulties.add(parseDifficulty(element.asJsonObject))
                }
            }

            return Song(
                idx = idx,
                id = id,
                titleLocalized = titleLocalized,
                artist = artist,
                artistLocalized = artistLocalized,
                bpm = bpm,
                bpmBase = bpmBase,
                set = set,
                purchase = purchase,
                audioPreview = audioPreview,
                audioPreviewEnd = audioPreviewEnd,
                side = side,
                bg = bg,
                date = date,
                category = category,
                bgInverse = bgInverse,
                bgDaynight = bgDaynight,
                version = version,
                worldUnlock = worldUnlock,
                remoteDl = remoteDl,
                bydLocalUnlock = bydLocalUnlock,
                songlistHidden = songlistHidden,
                noPp = noPp,
                sourceLocalized = sourceLocalized,
                sourceCopyright = sourceCopyright,
                noStream = noStream,
                jacketLocalized = jacketLocalized,
                difficulties = difficulties
            )
        }
        
        /**
         * 解析本地化文本对象
         */
        private fun parseLocalizedText(json: JsonObject?): LocalizedText {
            return LocalizedText.fromJsonObject(json)
        }
        
        /**
         * 解析难度对象
         */
        private fun parseDifficulty(json: JsonObject): Difficulty {
            return Difficulty(
                ratingClass = json.get("ratingClass")?.asInt ?: 0,
                chartDesigner = json.get("chartDesigner")?.asString ?: "",
                jacketDesigner = json.get("jacketDesigner")?.asString ?: "",
                rating = json.get("rating")?.asInt ?: 0,
                ratingPlus = json.get("ratingPlus")?.asBoolean ?: false,
                legacy11 = json.get("legacy11")?.asBoolean ?: false,
                plusFingers = json.get("plusFingers")?.asBoolean ?: false,
                titleLocalized = parseLocalizedText(json.getAsJsonObject("title_localized")),
                artist = json.get("artist")?.asString ?: "",
                artistLocalized = parseLocalizedText(json.getAsJsonObject("artist_localized")),
                bpm = json.get("bpm")?.asString ?: "",
                bpmBase = json.get("bpm_base")?.asDouble ?: 0.0,
                jacketNight = json.get("jacket_night")?.asString ?: "",
                jacketOverride = json.get("jacketOverride")?.asBoolean ?: false,
                audioOverride = json.get("audioOverride")?.asBoolean ?: false,
                hiddenUntil = json.get("hidden_until")?.asString ?: "",
                bg = json.get("bg")?.asString ?: "",
                bgInverse = json.get("bg_inverse")?.asString ?: "",
                worldUnlock = json.get("world_unlock")?.asBoolean ?: false,
                date = json.get("date")?.asLong ?: 0L,
                version = json.get("version")?.asString ?: ""
            )
        }
    }

    /**
     * 转换为 JsonObject
     */
    fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("idx", idx)
            addProperty("id", id)
            
            // title_localized
            if (!titleLocalized.isEmpty()) {
                add("title_localized", titleLocalized.toJsonObject())
            }
            
            addProperty("artist", artist)
            
            // artist_localized
            if (!artistLocalized.isEmpty()) {
                add("artist_localized", artistLocalized.toJsonObject())
            }
            
            addProperty("bpm", bpm)
            if (bpmBase > 0) addProperty("bpm_base", bpmBase)
            addProperty("set", set)
            addProperty("purchase", purchase)
            if (category.isNotEmpty()) addProperty("category", category)
            addProperty("audioPreview", audioPreview)
            addProperty("audioPreviewEnd", audioPreviewEnd)
            addProperty("side", side)
            addProperty("bg", bg)
            if (bgInverse.isNotEmpty()) addProperty("bg_inverse", bgInverse)
            
            // bg_daynight
            bgDaynight?.let {
                val obj = JsonObject()
                obj.addProperty("day", it.day)
                obj.addProperty("night", it.night)
                add("bg_daynight", obj)
            }
            
            addProperty("date", date)
            if (version.isNotEmpty()) addProperty("version", version)
            if (worldUnlock) addProperty("world_unlock", true)
            if (remoteDl) addProperty("remote_dl", true)
            if (bydLocalUnlock) addProperty("byd_local_unlock", true)
            if (songlistHidden) addProperty("songlist_hidden", true)
            if (noPp) addProperty("no_pp", true)
            
            // source_localized
            if (!sourceLocalized.isEmpty()) {
                add("source_localized", sourceLocalized.toJsonObject())
            }
            
            if (sourceCopyright.isNotEmpty()) addProperty("source_copyright", sourceCopyright)
            if (noStream) addProperty("no_stream", true)
            
            // jacket_localized
            if (jacketLocalized.isNotEmpty()) {
                val obj = JsonObject()
                jacketLocalized.forEach { (key, value) ->
                    obj.addProperty(key, value)
                }
                add("jacket_localized", obj)
            }

            // difficulties
            if (difficulties.isNotEmpty()) {
                val diffArray = com.google.gson.JsonArray()
                difficulties.forEach { diff ->
                    diffArray.add(diff.toJsonObject())
                }
                add("difficulties", diffArray)
            }
        }
    }
    
    /**
     * 将 Difficulty 转换为 JsonObject
     */
    private fun Difficulty.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("ratingClass", ratingClass)
            addProperty("chartDesigner", chartDesigner)
            addProperty("jacketDesigner", jacketDesigner)
            addProperty("rating", rating)
            if (ratingPlus) addProperty("ratingPlus", true)
            if (legacy11) addProperty("legacy11", true)
            if (plusFingers) addProperty("plusFingers", true)
            
            // title_localized
            if (!titleLocalized.isEmpty()) {
                add("title_localized", titleLocalized.toJsonObject())
            }
            
            if (artist.isNotEmpty()) addProperty("artist", artist)
            
            // artist_localized
            if (!artistLocalized.isEmpty()) {
                add("artist_localized", artistLocalized.toJsonObject())
            }
            
            if (bpm.isNotEmpty()) addProperty("bpm", bpm)
            if (bpmBase > 0) addProperty("bpm_base", bpmBase)
            if (jacketNight.isNotEmpty()) addProperty("jacket_night", jacketNight)
            if (jacketOverride) addProperty("jacketOverride", true)
            if (audioOverride) addProperty("audioOverride", true)
            if (hiddenUntil.isNotEmpty()) addProperty("hidden_until", hiddenUntil)
            if (bg.isNotEmpty()) addProperty("bg", bg)
            if (bgInverse.isNotEmpty()) addProperty("bg_inverse", bgInverse)
            if (worldUnlock) addProperty("world_unlock", true)
            if (date > 0) addProperty("date", date)
            if (version.isNotEmpty()) addProperty("version", version)
        }
    }

    /**
     * 获取实际使用的乐曲文件夹ID
     * 当 remote_dl 为 true 时，返回 dl_${id}，否则返回 id
     */
    fun getActualFolderId(): String {
        return if (remoteDl) "dl_$id" else id
    }

    /**
     * 获取显示用的标题（优先使用 title_localized.en，如果不存在则使用其他可用语言）
     */
    fun getDisplayTitle(): String {
        return titleLocalized.getDefault().takeIf { it.isNotBlank() } 
            ?: id
    }
    
    /**
     * 获取显示用的艺术家（优先使用 artist_localized.en，如果不存在则使用 artist）
     */
    fun getDisplayArtist(): String {
        return artistLocalized.getDefault().takeIf { it.isNotBlank() }
            ?: artist
    }

    /**
     * 获取显示用的难度字符串，如 "7 9+ 10"
     */
    fun getDifficultyString(): String {
        return difficulties.joinToString(" ") { it.getRatingString() }
    }

    /**
     * 获取难度等级颜色对应的 side
     */
    fun getSideColor(): String {
        return when (side) {
            0 -> "light" // 光侧
            1 -> "conflict" // 对立侧
            2 -> "colorless" // 无色
            3 -> "lephon" // Lephon侧
            else -> "unknown"
        }
    }
}