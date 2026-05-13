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
import java.util.concurrent.ConcurrentHashMap

/**
 * Packlist 仓库，处理 packlist 读取与 JSON 更新
 */
class PacklistRepository(private val context: Context) {

    companion object {
        private const val TAG = "PacklistRepository"
        private const val PACKLIST_FILENAME = "packlist"
        private const val BACKUP_FILENAME = "packlist.backup"
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
            when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("packs") -> {
                    val packsArray = jsonElement.asJsonObject.getAsJsonArray("packs")
                    packsArray.forEach { element ->
                        if (element.isJsonObject) {
                            packs.add(Pack.fromJsonObject(element.asJsonObject))
                        }
                    }
                }
                jsonElement.isJsonArray -> {
                    jsonElement.asJsonArray.forEach { element ->
                        if (element.isJsonObject) {
                            packs.add(Pack.fromJsonObject(element.asJsonObject))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse packs", e)
        }
        return packs
    }

    private fun findPackFolder(directory: DocumentFile): DocumentFile? {
        return directory.listFiles().firstOrNull { it.isDirectory && it.name == "pack" }
    }

    private fun findBannerNames(packId: String): List<String> {
        return listOf(
            "1080_select_$packId.png",
            "1080_slect_$packId.png",
            "select_$packId.png"
        )
    }

    private fun findBannerFile(packFolder: DocumentFile, subDir: DocumentFile?, packId: String): Uri? {
        val names = findBannerNames(packId)
        for (name in names) {
            packFolder.findFile(name)?.let { return it.uri }
        }
        subDir?.let { dir ->
            for (name in names) {
                dir.findFile(name)?.let { return it.uri }
            }
        }
        return null
    }

    /**
     * 批量获取所有曲包的横幅 URI（优化版）
     * 一次性扫描 pack/ 和 pack/1080/ 目录，通过单次 listFiles 替代多次 findFile。
     *
     * @param packIds 所有需要查找横幅的曲包 ID 列表
     * @return packId -> bannerUri 的映射表
     */
    suspend fun getAllPackBannerUris(directoryUri: Uri, packIds: List<String>): Map<String, Uri?> = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext emptyMap()

            val allFiles = directory.listFiles()
            val packFolder = allFiles.firstOrNull { it.isDirectory && it.name == "pack" }

            val rootFileMap = allFiles.filter { it.isFile && it.name != null }.associateBy { it.name!! }

            val packFileMap: Map<String, DocumentFile>
            val subDirFileMap: Map<String, DocumentFile>

            if (packFolder != null) {
                val packFiles = packFolder.listFiles()
                packFileMap = packFiles.filter { it.isFile && it.name != null }.associateBy { it.name!! }
                subDirFileMap = packFiles.firstOrNull { it.isDirectory && it.name == "1080" }
                    ?.let { dir ->
                        dir.listFiles().filter { it.isFile && it.name != null }.associateBy { it.name!! }
                    }
                    ?: emptyMap()
            } else {
                packFileMap = emptyMap()
                subDirFileMap = emptyMap()
            }

            val result = ConcurrentHashMap<String, Uri?>(packIds.size)
            for (packId in packIds) {
                val names = findBannerNames(packId)
                var found: Uri? = null
                for (name in names) {
                    found = packFileMap[name]?.uri
                        ?: subDirFileMap[name]?.uri
                        ?: rootFileMap[name]?.uri
                    if (found != null) break
                }
                if (found != null) {
                    result[packId] = found
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch load pack banner URIs", e)
            emptyMap()
        }
    }

    suspend fun getPackBannerUri(directoryUri: Uri, packId: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext null
            val packFolder = findPackFolder(directory)
            val subDir = packFolder?.let { folder ->
                folder.listFiles().firstOrNull { it.isDirectory && it.name == "1080" }
            }
            val names = findBannerNames(packId)

            // 先查 pack/ 再查 pack/1080/ 最后查根目录
            for (name in names) {
                packFolder?.findFile(name)?.let { return@withContext it.uri }
            }
            subDir?.let { dir ->
                for (name in names) {
                    dir.findFile(name)?.let { return@withContext it.uri }
                }
            }
            for (name in names) {
                directory.findFile(name)?.let { return@withContext it.uri }
            }
            null
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

            FileUtils.createBackup(context, DocumentFile.fromTreeUri(context, directoryUri)!!, packlistFile, BACKUP_FILENAME)

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

    suspend fun addPack(directoryUri: Uri, newPack: Pack): Boolean = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: throw IllegalStateException("无法访问目录")
            val packlistFile = directory.findFile(PACKLIST_FILENAME)
                ?: throw IllegalStateException("找不到 packlist 文件")
            val content = FileUtils.readFileContent(context, packlistFile.uri)
                ?: throw IllegalStateException("无法读取 packlist 文件")

            FileUtils.createBackup(context, directory, packlistFile, BACKUP_FILENAME)

            val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            val jsonElement = JsonParser.parseString(content)
            val newPackObject = newPack.toJsonObject()

            val resultContent = when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("packs") -> {
                    val rootObject = jsonElement.asJsonObject
                    val packsArray = rootObject.getAsJsonArray("packs")
                    packsArray.add(newPackObject)
                    FileUtils.formatWithTwoSpaces(gson.toJson(rootObject))
                }
                jsonElement.isJsonArray -> {
                    val packsArray = jsonElement.asJsonArray
                    packsArray.add(newPackObject)
                    FileUtils.formatWithTwoSpaces(gson.toJson(packsArray))
                }
                else -> throw IllegalStateException("packlist 格式不正确")
            }

            FileUtils.writeFileContent(context, packlistFile.uri, resultContent)
            Log.d(TAG, "Added pack: ${newPack.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add pack", e)
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

            FileUtils.createBackup(context, directory, packlistFile, BACKUP_FILENAME)

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
