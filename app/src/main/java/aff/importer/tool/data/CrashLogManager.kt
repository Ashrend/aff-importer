package aff.importer.tool.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import aff.importer.tool.data.model.LogEntry
import aff.importer.tool.data.model.LogLevel
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object CrashLogManager {

    private const val TAG = "CrashLogManager"
    private const val MAX_BUFFER = 200
    private const val DUMP_COUNT = 50
    private const val CRASH_DIR = "crashes"

    private val logBuffer = ConcurrentLinkedDeque<LogEntry>()

    @Volatile
    var cachedDirectoryUri: Uri? = null

    private lateinit var appContext: Context

    fun addLog(entry: LogEntry) {
        logBuffer.addLast(entry)
        if (logBuffer.size > MAX_BUFFER) {
            logBuffer.removeFirst()
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "写入崩溃日志失败", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        val fileName = "crash_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))}.log"

        val content = buildCrashContent(timeStr, thread, throwable)

        // 写入内部存储（必定可用）
        val internalDir = File(appContext.filesDir, CRASH_DIR)
        internalDir.mkdirs()
        File(internalDir, fileName).writeText(content)

        // 尝试写入用户乐曲目录（SAF 方式，尽力而为）
        cachedDirectoryUri?.let { uri ->
            try {
                val dir = DocumentFile.fromTreeUri(appContext, uri)
                dir?.let { d ->
                    d.findFile(fileName)?.delete()
                    d.createFile("text/plain", fileName)?.let { file ->
                        appContext.contentResolver.openOutputStream(file.uri)?.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入外部目录崩溃日志失败", e)
            }
        }
    }

    private fun buildCrashContent(timeStr: String, thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("============================================")
        sb.appendLine("          ARCASA IMPORTER CRASH LOG")
        sb.appendLine("============================================")
        sb.appendLine("Time: $timeStr")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("App Version: ${getAppVersion()}")
        sb.appendLine()
        sb.appendLine("--- Crash Thread ---")
        sb.appendLine("Name: ${thread.name}")
        sb.appendLine("ID: ${thread.id}")
        sb.appendLine("Priority: ${thread.priority}")
        sb.appendLine()
        sb.appendLine("--- Stack Trace ---")
        appendThrowable(sb, throwable)
        sb.appendLine()
        sb.appendLine("--- Recent Logs (last $DUMP_COUNT) ---")
        val recentLogs = logBuffer.toList().takeLast(DUMP_COUNT)
        if (recentLogs.isEmpty()) {
            sb.appendLine("(no logs recorded)")
        } else {
            recentLogs.forEach { entry ->
                val levelTag = when (entry.level) {
                    LogLevel.INFO -> "I"
                    LogLevel.SUCCESS -> "S"
                    LogLevel.WARNING -> "W"
                    LogLevel.ERROR -> "E"
                }
                val et = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestamp))
                sb.appendLine("[$et][$levelTag] ${entry.message}")
            }
        }
        sb.appendLine()
        sb.appendLine("============================================")
        sb.appendLine("            END OF CRASH LOG")
        sb.appendLine("============================================")
        return sb.toString()
    }

    private fun appendThrowable(sb: StringBuilder, throwable: Throwable) {
        sb.appendLine("${throwable::class.java.name}: ${throwable.message}")
        throwable.stackTrace.forEach { element ->
            sb.appendLine("\tat $element")
        }
        if (throwable.cause != null) {
            sb.appendLine("Caused by:")
            appendThrowable(sb, throwable.cause!!)
        }
    }

    private fun getAppVersion(): String {
        return try {
            val pkg = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            "${pkg.versionName} (${pkg.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
