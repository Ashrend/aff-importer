package aff.importer.tool.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import aff.importer.tool.data.model.Song
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 导入结果
 */
data class ImportResult(
    val success: Boolean,
    val songId: String? = null,
    val message: String = ""
)

/**
 * ZIP 条目信息
 */
data class ZipEntryInfo(
    val entry: ZipEntry,
    val data: ByteArray
) {
    val name: String get() = entry.name
    val isDirectory: Boolean get() = entry.isDirectory
}

/**
 * Songlist 仓库，处理 ZIP 解析、文件解压和 JSON 更新
 */
class SonglistRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "SonglistRepository"
        private const val SONGLIST_FILENAME = "songlist"
        private const val BACKUP_FILENAME = "songlist.backup"
    }
    
    /**
     * 检查指定 URI 的目录中是否存在 songlist 文件
     */
    fun hasSonglistFile(directoryUri: Uri): Boolean {
        val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return false
        return directory.findFile(SONGLIST_FILENAME)?.isFile == true
    }
    
    /**
     * 获取目录的显示路径
     */
    fun getDirectoryPath(directoryUri: Uri): String {
        return directoryUri.toString()
    }

    // ==================== Songlist 管理功能 ====================

    /**
     * 获取所有已导入的歌曲列表
     */
    suspend fun getAllSongs(directoryUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        try {
            val songlistFile = getSonglistFile(directoryUri)
                ?: return@withContext emptyList<Song>()

            val content = readFileContent(songlistFile.uri) ?: return@withContext emptyList()
            
            parseSongsFromContent(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all songs", e)
            emptyList()
        }
    }

    /**
     * 从 songlist 内容解析歌曲列表
     */
    private fun parseSongsFromContent(content: String): List<Song> {
        val songs = mutableListOf<Song>()
        
        try {
            val jsonElement = JsonParser.parseString(content)
            
            when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("songs") -> {
                    val songsArray = jsonElement.asJsonObject.getAsJsonArray("songs")
                    songsArray.forEach { element ->
                        if (element.isJsonObject) {
                            songs.add(Song.fromJsonObject(element.asJsonObject))
                        }
                    }
                }
                jsonElement.isJsonArray -> {
                    jsonElement.asJsonArray.forEach { element ->
                        if (element.isJsonObject) {
                            songs.add(Song.fromJsonObject(element.asJsonObject))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse songs", e)
        }
        
        return songs
    }

    /**
     * 获取歌曲曲绘的 URI
     * 优先查找 base.jpg，如果不存在则查找 1080_base.jpg
     */
    suspend fun getSongJacketUri(directoryUri: Uri, songId: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext null
            val songFolder = directory.findFile(songId) ?: return@withContext null
            
            // 优先查找 base.jpg
            songFolder.findFile("base.jpg")?.let { return@withContext it.uri }
            
            // 备选 1080_base.jpg
            songFolder.findFile("1080_base.jpg")?.let { return@withContext it.uri }
            
            // 尝试其他可能的命名
            songFolder.listFiles().forEach { file ->
                val name = file.name?.lowercase() ?: ""
                if (name.contains("base") && (name.endsWith(".jpg") || name.endsWith(".jpeg"))) {
                    return@withContext file.uri
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get jacket for $songId", e)
            null
        }
    }

    /**
     * 更新指定歌曲的元数据
     */
    suspend fun updateSong(directoryUri: Uri, updatedSong: Song): Boolean = withContext(Dispatchers.IO) {
        try {
            val songlistFile = getSonglistFile(directoryUri)
                ?: throw IllegalStateException("找不到 songlist 文件")

            val content = readFileContent(songlistFile.uri)
                ?: throw IllegalStateException("无法读取 songlist 文件")

            // 创建备份
            createBackup(DocumentFile.fromTreeUri(context, directoryUri)!!, songlistFile)

            val gson = GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()

            val jsonElement = JsonParser.parseString(content)
            var updated = false

            val resultContent = when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("songs") -> {
                    val rootObject = jsonElement.asJsonObject
                    val songsArray = rootObject.getAsJsonArray("songs")
                    
                    for (i in 0 until songsArray.size()) {
                        val songObj = songsArray[i].asJsonObject
                        if (songObj.get("id")?.asString == updatedSong.id) {
                            songsArray[i] = updatedSong.toJsonObject()
                            updated = true
                            break
                        }
                    }
                    
                    if (!updated) {
                        throw IllegalStateException("找不到 id 为 ${updatedSong.id} 的歌曲")
                    }
                    
                    formatWithTwoSpaces(gson.toJson(rootObject))
                }
                jsonElement.isJsonArray -> {
                    val songsArray = jsonElement.asJsonArray
                    
                    for (i in 0 until songsArray.size()) {
                        val songObj = songsArray[i].asJsonObject
                        if (songObj.get("id")?.asString == updatedSong.id) {
                            songsArray[i] = updatedSong.toJsonObject()
                            updated = true
                            break
                        }
                    }
                    
                    if (!updated) {
                        throw IllegalStateException("找不到 id 为 ${updatedSong.id} 的歌曲")
                    }
                    
                    formatWithTwoSpaces(gson.toJson(songsArray))
                }
                else -> throw IllegalStateException("songlist 格式不正确")
            }

            writeFileContent(songlistFile.uri, resultContent)
            Log.d(TAG, "Updated song: ${updatedSong.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update song", e)
            false
        }
    }

    /**
     * 删除指定歌曲（从 songlist 移除并删除文件夹）
     */
    suspend fun deleteSong(directoryUri: Uri, songId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: throw IllegalStateException("无法访问目录")

            // 1. 从 songlist 中移除
            val songlistFile = directory.findFile(SONGLIST_FILENAME)
                ?: throw IllegalStateException("找不到 songlist 文件")

            val content = readFileContent(songlistFile.uri)
                ?: throw IllegalStateException("无法读取 songlist 文件")

            // 创建备份
            createBackup(directory, songlistFile)

            val gson = GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()

            val jsonElement = JsonParser.parseString(content)
            var removed = false

            val resultContent = when {
                jsonElement.isJsonObject && jsonElement.asJsonObject.has("songs") -> {
                    val rootObject = jsonElement.asJsonObject
                    val songsArray = rootObject.getAsJsonArray("songs")
                    val newSongsArray = JsonArray()
                    
                    songsArray.forEach { element ->
                        val songObj = element.asJsonObject
                        if (songObj.get("id")?.asString != songId) {
                            newSongsArray.add(element)
                        } else {
                            removed = true
                        }
                    }
                    
                    if (!removed) {
                        throw IllegalStateException("找不到 id 为 $songId 的歌曲")
                    }
                    
                    rootObject.add("songs", newSongsArray)
                    formatWithTwoSpaces(gson.toJson(rootObject))
                }
                jsonElement.isJsonArray -> {
                    val songsArray = jsonElement.asJsonArray
                    val newSongsArray = JsonArray()
                    
                    songsArray.forEach { element ->
                        val songObj = element.asJsonObject
                        if (songObj.get("id")?.asString != songId) {
                            newSongsArray.add(element)
                        } else {
                            removed = true
                        }
                    }
                    
                    if (!removed) {
                        throw IllegalStateException("找不到 id 为 $songId 的歌曲")
                    }
                    
                    formatWithTwoSpaces(gson.toJson(newSongsArray))
                }
                else -> throw IllegalStateException("songlist 格式不正确")
            }

            writeFileContent(songlistFile.uri, resultContent)

            // 2. 删除歌曲文件夹
            val songFolder = directory.findFile(songId)
            if (songFolder != null) {
                val deleted = songFolder.delete()
                Log.d(TAG, "Deleted folder $songId: $deleted")
            }

            Log.d(TAG, "Successfully deleted song: $songId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete song", e)
            false
        }
    }

    /**
     * 获取 songlist 文件
     */
    private fun getSonglistFile(directoryUri: Uri): DocumentFile? {
        val directory = DocumentFile.fromTreeUri(context, directoryUri) ?: return null
        return directory.findFile(SONGLIST_FILENAME)
    }
    
    /**
     * 解析 ZIP 文件，提取 id 和所有文件条目
     */
    suspend fun parseZipFile(zipUri: Uri): Pair<String?, List<ZipEntryInfo>> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<ZipEntryInfo>()
        var songId: String? = null
        var packSonglistContent: String? = null
        
        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipInputStream ->
                var entry: ZipEntry?
                
                while (zipInputStream.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        if (!zipEntry.isDirectory) {
                            val data = readEntryData(zipInputStream)
                            entries.add(ZipEntryInfo(zipEntry, data))
                            
                            val fileName = zipEntry.name.substringAfterLast("/")
                            if (isSonglistFile(fileName)) {
                                packSonglistContent = String(data, Charsets.UTF_8)
                                Log.d(TAG, "Found songlist file: ${zipEntry.name}")
                            }
                        }
                    }
                }
            }
        }
        
        if (packSonglistContent != null) {
            songId = extractSongId(packSonglistContent)
            Log.d(TAG, "Extracted song id: $songId")
        }
        
        Pair(songId, entries)
    }
    
    private fun readEntryData(zipInputStream: ZipInputStream): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count: Int
        
        while (zipInputStream.read(buffer).also { count = it } != -1) {
            outputStream.write(buffer, 0, count)
        }
        
        return outputStream.toByteArray()
    }
    
    private fun isSonglistFile(fileName: String): Boolean {
        val nameWithoutExt = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".", "")
        
        val hasKeyword = nameWithoutExt.contains("songlist", ignoreCase = true) ||
                nameWithoutExt.contains("slst", ignoreCase = true)
        
        val validExt = ext.isEmpty() || ext.equals("json", ignoreCase = true) || 
                ext.equals("txt", ignoreCase = true)
        
        return hasKeyword && validExt
    }
    
    private fun extractSongId(content: String): String? {
        return try {
            val jsonElement = JsonParser.parseString(content)
            
            when {
                jsonElement.isJsonObject -> {
                    val jsonObject = jsonElement.asJsonObject
                    if (jsonObject.has("songs") && jsonObject["songs"].isJsonArray) {
                        val songsArray = jsonObject["songs"].asJsonArray
                        if (songsArray.size() > 0 && songsArray[0].isJsonObject) {
                            songsArray[0].asJsonObject.get("id")?.asString
                        } else null
                    } else {
                        jsonObject.get("id")?.asString
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse songlist JSON", e)
            null
        }
    }
    
    /**
     * 解压文件到指定目录
     */
    suspend fun extractFiles(
        directoryUri: Uri,
        songId: String,
        entries: List<ZipEntryInfo>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: throw IllegalStateException("无法访问目标目录")
            
            val songFolder = directory.createDirectory(songId)
                ?: throw IllegalStateException("无法创建文件夹: $songId")
            
            Log.d(TAG, "Created folder: $songId")
            
            var successCount = 0
            entries.forEach { entryInfo ->
                val relativePath = entryInfo.name.substringAfterLast("/")
                
                if (!entryInfo.isDirectory && relativePath.isNotEmpty()) {
                    val mimeType = getMimeType(relativePath)
                    val newFile = songFolder.createFile(mimeType, relativePath)
                        ?: throw IllegalStateException("无法创建文件: $relativePath")
                    
                    context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                        outputStream.write(entryInfo.data)
                    }
                    
                    successCount++
                }
            }
            
            Log.d(TAG, "Extracted $successCount files successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract files", e)
            false
        }
    }
    
    /**
     * 更新客户端 songlist 文件
     * 策略：创建备份后，将原始内容解析，追加新歌曲，然后用 Gson 格式化输出
     */
    suspend fun updateClientSonglist(
        directoryUri: Uri,
        entries: List<ZipEntryInfo>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val directory = DocumentFile.fromTreeUri(context, directoryUri)
                ?: throw IllegalStateException("无法访问目标目录")
            
            val songlistFile = directory.findFile(SONGLIST_FILENAME)
                ?: throw IllegalStateException("找不到 songlist 文件")
            
            // 1. 创建备份
            createBackup(directory, songlistFile)
            
            // 2. 读取原始内容
            val originalContent = readFileContent(songlistFile.uri)
                ?: throw IllegalStateException("无法读取 songlist 文件")
            
            // 3. 从 ZIP 提取新歌曲
            val packSonglistContent = entries.find { isSonglistFile(it.name.substringAfterLast("/")) }
                ?.let { String(it.data, Charsets.UTF_8) }
                ?: throw IllegalStateException("ZIP 中找不到有效的 songlist 文件")
            
            val packJson = JsonParser.parseString(packSonglistContent)
            val newSongObject: JsonObject = when {
                packJson.isJsonObject && packJson.asJsonObject.has("songs") -> {
                    val songs = packJson.asJsonObject.getAsJsonArray("songs")
                    if (songs.size() > 0 && songs[0].isJsonObject) {
                        songs[0].asJsonObject.deepCopy()
                    } else {
                        throw IllegalStateException("ZIP中的songlist没有歌曲")
                    }
                }
                packJson.isJsonObject -> {
                    packJson.asJsonObject.deepCopy()
                }
                else -> throw IllegalStateException("ZIP中的songlist格式不正确")
            }
            
            // 4. 解析并修改客户端 songlist
            val originalJson = JsonParser.parseString(originalContent)
            
            // 使用 Gson 构建器，自定义缩进为 2 空格
            val gson = GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
            
            // 处理并生成结果
            val resultContent = when {
                originalJson.isJsonObject && originalJson.asJsonObject.has("songs") -> {
                    val rootObject = originalJson.asJsonObject
                    val songsArray = rootObject.getAsJsonArray("songs")
                    songsArray.add(newSongObject)
                    
                    // 使用 Gson 格式化，但将 4 空格替换为 2 空格
                    formatWithTwoSpaces(gson.toJson(rootObject))
                }
                originalJson.isJsonArray -> {
                    val songsArray = originalJson.asJsonArray
                    songsArray.add(newSongObject)
                    formatWithTwoSpaces(gson.toJson(songsArray))
                }
                else -> {
                    throw IllegalStateException("客户端 songlist 格式不正确")
                }
            }
            
            // 5. 写入文件（确保完全替换原内容）
            writeFileContent(songlistFile.uri, resultContent)
            
            Log.d(TAG, "Successfully updated songlist, added song: ${newSongObject.get("id")?.asString}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update songlist", e)
            false
        }
    }
    
    /**
     * 将 Gson 默认的 4 空格缩进转换为 2 空格
     */
    private fun formatWithTwoSpaces(json: String): String {
        val lines = json.lines()
        val result = StringBuilder()
        
        for (line in lines) {
            // 计算前导空格数
            var leadingSpaces = 0
            for (c in line) {
                if (c == ' ') leadingSpaces++
                else break
            }
            
            // 将 4 的倍数空格转换为 2 的倍数
            val indentLevel = leadingSpaces / 4
            val newIndent = "  ".repeat(indentLevel)
            val remainder = line.substring(leadingSpaces)
            
            result.append(newIndent).append(remainder).append('\n')
        }
        
        return result.toString().trimEnd()
    }
    
    /**
     * 创建备份文件
     */
    private fun createBackup(directory: DocumentFile, originalFile: DocumentFile) {
        try {
            directory.findFile(BACKUP_FILENAME)?.delete()
            val backupFile = directory.createFile("application/octet-stream", BACKUP_FILENAME)
            if (backupFile != null) {
                context.contentResolver.openInputStream(originalFile.uri)?.use { input ->
                    context.contentResolver.openOutputStream(backupFile.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Created backup: $BACKUP_FILENAME")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create backup", e)
        }
    }
    
    /**
     * 读取文件内容
     */
    private fun readFileContent(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file", e)
            null
        }
    }
    
    /**
     * 写入文件内容 - 使用 Writer 确保完整写入
     */
    private fun writeFileContent(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
            }
        }
    }
    
    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "aff" -> "application/octet-stream"
            "ogg" -> "audio/ogg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "json" -> "application/json"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}