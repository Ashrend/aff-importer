package aff.importer.tool.data.model

import com.google.gson.JsonObject

/**
 * 本地化文本
 */
data class LocalizedText(
    val en: String = "",
    val ja: String = "",
    val ko: String = "",
    val zhHans: String = "",
    val zhHant: String = ""
) {
    /**
     * 获取默认显示文本（优先英文，其次其他可用语言）
     */
    fun getDefault(): String {
        return en.takeIf { it.isNotBlank() }
            ?: ja.takeIf { it.isNotBlank() }
            ?: ko.takeIf { it.isNotBlank() }
            ?: zhHans.takeIf { it.isNotBlank() }
            ?: zhHant.takeIf { it.isNotBlank() }
            ?: ""
    }

    fun isEmpty(): Boolean = en.isBlank() && ja.isBlank() && ko.isBlank() && zhHans.isBlank() && zhHant.isBlank()

    /**
     * 转换为 JsonObject
     */
    fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            if (en.isNotEmpty()) addProperty("en", en)
            if (ja.isNotEmpty()) addProperty("ja", ja)
            if (ko.isNotEmpty()) addProperty("ko", ko)
            if (zhHans.isNotEmpty()) addProperty("zh-Hans", zhHans)
            if (zhHant.isNotEmpty()) addProperty("zh-Hant", zhHant)
        }
    }

    companion object {
        /**
         * 从 JsonObject 解析本地化文本
         */
        fun fromJsonObject(json: JsonObject?): LocalizedText {
            if (json == null) return LocalizedText()
            return LocalizedText(
                en = json.get("en")?.asString ?: "",
                ja = json.get("ja")?.asString ?: "",
                ko = json.get("ko")?.asString ?: "",
                zhHans = json.get("zh-Hans")?.asString ?: "",
                zhHant = json.get("zh-Hant")?.asString ?: ""
            )
        }
    }
}
