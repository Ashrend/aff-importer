package aff.importer.tool.data.model

/**
 * 导入操作的各个状态
 */
sealed class ImportState {
    /** 空闲状态，等待用户操作 */
    data object Idle : ImportState()
    
    /** 正在选择目录 */
    data object SelectingDirectory : ImportState()
    
    /** 正在解析 ZIP 压缩包 */
    data object ParsingZip : ImportState()
    
    /** 正在解压文件 */
    data class Extracting(val currentFile: String, val progress: Int, val total: Int) : ImportState()
    
    /** 正在更新 songlist 文件 */
    data object UpdatingSonglist : ImportState()
    
    /** 导入成功 */
    data class Success(val songId: String) : ImportState()
    
    /** 导入失败 */
    data class Error(val message: String, val throwable: Throwable? = null) : ImportState()
}

/**
 * 日志条目
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}