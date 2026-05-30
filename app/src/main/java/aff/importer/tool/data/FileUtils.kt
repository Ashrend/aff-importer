package aff.importer.tool.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 公共文件操作工具
 */
internal object FileUtils {

    fun readFileContent(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun writeFileContent(context: Context, uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.write(content)
                writer.flush()
            }
        }
    }

    fun createBackup(context: Context, directory: DocumentFile, originalFile: DocumentFile, backupName: String): Boolean {
        return try {
            directory.findFile(backupName)?.delete()
            val backupFile = directory.createFile("application/octet-stream", backupName)
                ?: throw IllegalStateException("无法创建备份文件")
            context.contentResolver.openInputStream(originalFile.uri)?.use { input ->
                context.contentResolver.openOutputStream(backupFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取原始文件")
            true
        } catch (e: Exception) {
            Log.e("FileUtils", "创建备份失败: ${e.message}", e)
            false
        }
    }

    fun formatWithTwoSpaces(json: String): String {
        val lines = json.lines()
        val result = StringBuilder()
        for (line in lines) {
            var leadingSpaces = 0
            for (c in line) {
                if (c == ' ') leadingSpaces++
                else break
            }
            val indentLevel = leadingSpaces / 4
            val newIndent = "  ".repeat(indentLevel)
            val remainder = line.substring(leadingSpaces)
            result.append(newIndent).append(remainder).append('\n')
        }
        return result.toString().trimEnd()
    }
}
