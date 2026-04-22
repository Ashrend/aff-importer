package aff.importer.tool.data.model

import com.google.gson.JsonObject

/**
 * Arcaea 曲包完整数据类
 */
data class Pack(
    val id: String,
    val type: String = "pack",
    val nameLocalized: LocalizedText = LocalizedText(),
    val descriptionLocalized: LocalizedText = LocalizedText(),
    val packParent: String = "",
    val isExtendPack: Boolean = false,
    val customBanner: Boolean = false,
    val plusCharacter: Int = 0,
    val section: String = ""
) {
    companion object {
        /**
         * 从 JsonObject 解析 Pack
         */
        fun fromJsonObject(json: JsonObject): Pack {
            return Pack(
                id = json.get("id")?.asString ?: "",
                type = json.get("type")?.asString ?: "pack",
                nameLocalized = parseLocalizedText(json.getAsJsonObject("name_localized")),
                descriptionLocalized = parseLocalizedText(json.getAsJsonObject("description_localized")),
                packParent = json.get("pack_parent")?.asString ?: "",
                isExtendPack = json.get("is_extend_pack")?.asBoolean ?: false,
                customBanner = json.get("custom_banner")?.asBoolean ?: false,
                plusCharacter = json.get("plus_character")?.asInt ?: 0,
                section = json.get("section")?.asString ?: ""
            )
        }

        private fun parseLocalizedText(json: JsonObject?): LocalizedText {
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

    /**
     * 转换为 JsonObject
     */
    fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("id", id)
            if (type.isNotEmpty()) addProperty("type", type)
            if (!nameLocalized.isEmpty()) add("name_localized", nameLocalized.toJsonObject())
            if (!descriptionLocalized.isEmpty()) add("description_localized", descriptionLocalized.toJsonObject())
            if (packParent.isNotEmpty()) addProperty("pack_parent", packParent)
            if (isExtendPack) addProperty("is_extend_pack", true)
            if (customBanner) addProperty("custom_banner", true)
            if (plusCharacter > 0) addProperty("plus_character", plusCharacter)
            if (section.isNotEmpty()) addProperty("section", section)
        }
    }

/**
     * 获取显示用的曲包名
     */
    fun getDisplayName(): String {
        return nameLocalized.getDefault().takeIf { it.isNotBlank() } ?: id
    }

    /**
     * 获取显示用的描述
     */
    fun getDisplayDescription(): String {
        return descriptionLocalized.getDefault()
    }
}
