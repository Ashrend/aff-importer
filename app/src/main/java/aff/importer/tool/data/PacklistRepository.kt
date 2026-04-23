package aff.importer.tool.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import aff.importer.tool.data.model.Pack
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Packlist 仓库，处理 packlist 读取与 JSON 更新
 */
class PacklistRepository(private val context: Context) {

    companion object {
        private const val TAG = "PacklistRepository"
        private const val PACKLIST_FILENAME = "packlist"
    }

    fun hasPacklistFile(directoryUri: Uri): Boolean {
        val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return false
        return directory.findFile(PACKLIST_FILENAME)?.isFile == true
    }

    suspend fun getAllPacks(directoryUri: Uri): List<Pack> = withContext(Dispatchers.IO) {
        try {
            val packlistFile = getPacklistFile(directoryUri) ?: return@withContext emptyList()
            val content = FileUtils.readFileContent(context, packlistFile.uri) ?: return@withContext emptyList()
            parsePacksFromContent(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all packs", e)
            emptyList()
        }
    }

    private fun parsePacksFromContent(content: String): List<Pack> {
        val packs = mutableListOf<Pack>()
        try {
            val jsonElement = JsonParser.parseString(content)
            val packsArray = when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("packs") ->
                    jsonElement.asJsonObject.getAsJsonArray("packs")
                jsonElement.isJsonArray -> jsonElement.asJsonArray
                else -> null
            }
            packsArray?.forEach { element ->
                if (element.isJsonObject) packs.add(Pack.fromJsonObject(element.asJsonObject))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse packs", e)
        }
        return packs
    }

    suspend fun getPackBannerUri(directoryUri: Uri, packId: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext null
            val packFolder = directory.findFile("pack") ?: return@withContext null
            packFolder.findFile("select_$packId.png")?.uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get banner for $packId", e)
            null
        }
    }

    suspend fun updatePack(directoryUri: Uri, updatedPack: Pack): Boolean = withContext(Dispatchers.IO) {
        try {
            val packlistFile = getPacklistFile(directoryUri)
                ?: throw IllegalStateException("找不到 packlist 文件")
            val content = FileUtils.readFileContent(context, packlistFile.uri)
                ?: throw IllegalStateException("无法读取 packlist 文件")

            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: throw IllegalStateException("无法访问目录")
            FileUtils.createBackup(context, directory, packlistFile, "packlist.backup")

            val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            val jsonElement = JsonParser.parseString(content)
            var updated = false

            val resultContent = when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("packs") -> {
                    val rootObject = jsonElement.asJsonObject
                    val packsArray = rootObject.getAsJsonArray("packs")
                    for (i in 0 until packsArray.size()) {
                        if (packsArray[i].asJsonObject.get("id")?.asString == updatedPack.id) {
                            packsArray[i] = updatedPack.toJsonObject()
                            updated = true
                            break
                        }
                    }
                    if (!updated) throw IllegalStateException("找不到 id 为 ${updatedPack.id} 的曲包")
                    FileUtils.formatWithTwoSpaces(gson.toJson(rootObject))
                }
                jsonElement.isJsonArray -> {
                    val packsArray = jsonElement.asJsonArray
                    for (i in 0 until packsArray.size()) {
                        if (packsArray[i].asJsonObject.get("id")?.asString == updatedPack.id) {
                            packsArray[i] = updatedPack.toJsonObject()
                            updated = true
                            break
                        }
                    }
                    if (!updated) throw IllegalStateException("找不到 id 为 ${updatedPack.id} 的曲包")
                    FileUtils.formatWithTwoSpaces(gson.toJson(packsArray))
                }
                else -> throw IllegalStateException("packlist 格式不正确")
            }

            FileUtils.writeFileContent(context, packlistFile.uri, resultContent)
            Log.d(TAG, "Updated pack: ${updatedPack.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update pack", e)
            false
        }
    }

    suspend fun deletePack(directoryUri: Uri, packId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: throw IllegalStateException("无法访问目录")
            val packlistFile = directory.findFile(PACKLIST_FILENAME)
                ?: throw IllegalStateException("找不到 packlist 文件")
            val content = FileUtils.readFileContent(context, packlistFile.uri)
                ?: throw IllegalStateException("无法读取 packlist 文件")

            FileUtils.createBackup(context, directory, packlistFile, "packlist.backup")

            val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            val jsonElement = JsonParser.parseString(content)
            var removed = false

            val resultContent = when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("packs") -> {
                    val rootObject = jsonElement.asJsonObject
                    val packsArray = rootObject.getAsJsonArray("packs")
                    val newPacksArray = JsonArray()
                    packsArray.forEach { element ->
                        if (element.asJsonObject.get("id")?.asString != packId) {
                            newPacksArray.add(element)
                        } else {
                            removed = true
                        }
                    }
                    if (!removed) throw IllegalStateException("找不到 id 为 $packId 的曲包")
                    rootObject.add("packs", newPacksArray)
                    FileUtils.formatWithTwoSpaces(gson.toJson(rootObject))
                }
                jsonElement.isJsonArray -> {
                    val packsArray = jsonElement.asJsonArray
                    val newPacksArray = JsonArray()
                    packsArray.forEach { element ->
                        if (element.asJsonObject.get("id")?.asString != packId) {
                            newPacksArray.add(element)
                        } else {
                            removed = true
                        }
                    }
                    if (!removed) throw IllegalStateException("找不到 id 为 $packId 的曲包")
                    FileUtils.formatWithTwoSpaces(gson.toJson(newPacksArray))
                }
                else -> throw IllegalStateException("packlist 格式不正确")
            }

            FileUtils.writeFileContent(context, packlistFile.uri, resultContent)
            Log.d(TAG, "Deleted pack: $packId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete pack", e)
            false
        }
    }

    private fun getPacklistFile(directoryUri: Uri): DocumentFile? {
        val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return null
        return directory.findFile(PACKLIST_FILENAME)
    }
}
