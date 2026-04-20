package aff.importer.tool

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import aff.importer.tool.data.PreferencesRepository
import aff.importer.tool.data.SonglistRepository
import aff.importer.tool.data.ZipEntryInfo
import aff.importer.tool.data.model.ImportState
import aff.importer.tool.data.model.LogEntry
import aff.importer.tool.data.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainViewModel 管理应用的主要业务逻辑和 UI 状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val preferencesRepository = PreferencesRepository(application)
    private val songlistRepository = SonglistRepository(application)
    
    // UI 状态
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    
    // 当前选择的目录 URI
    private val _directoryUri = MutableStateFlow<Uri?>(null)
    val directoryUri: StateFlow<Uri?> = _directoryUri.asStateFlow()
    
    // 操作日志
    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()
    
    // 解析后的 ZIP 条目（在解压和更新时使用）
    private var currentZipEntries: List<ZipEntryInfo> = emptyList()
    private var currentSongId: String? = null
    
    init {
        // 尝试恢复已保存的目录
        viewModelScope.launch {
            val savedUri = preferencesRepository.savedDirectoryUri.first()
            savedUri?.let { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    // 检查权限是否仍然有效
                    if (checkUriPermission(uri)) {
                        _directoryUri.value = uri
                        addLog("已恢复上次选择的目录", LogLevel.INFO)
                    } else {
                        addLog("目录权限已过期，请重新选择", LogLevel.WARNING)
                        preferencesRepository.clearDirectoryUri()
                    }
                } catch (e: Exception) {
                    addLog("恢复目录失败: ${e.message}", LogLevel.ERROR)
                }
            }
        }
    }
    
    /**
     * 检查 URI 权限是否仍然有效
     */
    private fun checkUriPermission(uri: Uri): Boolean {
        return try {
            // 尝试读取目录，如果失败则说明权限已过期
            val documentFile = DocumentFile.fromTreeUri(getApplication(), uri)
            documentFile?.canRead() == true && documentFile.canWrite()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 启动目录选择器
     */
    fun selectDirectory(launcher: ActivityResultLauncher<Uri?>) {
        _importState.value = ImportState.SelectingDirectory
        // 传入 null 表示从根目录开始选择
        launcher.launch(null)
    }
    
    /**
     * 处理目录选择结果
     */
    fun onDirectorySelected(uri: Uri?) {
        if (uri == null) {
            _importState.value = ImportState.Error("未选择目录")
            addLog("目录选择已取消", LogLevel.WARNING)
            return
        }
        
        viewModelScope.launch {
            try {
                // 持久化授权
                val contentResolver = getApplication<Application>().contentResolver
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                
                // 验证目录中是否有 songlist 文件
                if (!songlistRepository.hasSonglistFile(uri)) {
                    _importState.value = ImportState.Error("所选目录中没有找到 songlist 文件")
                    addLog("验证失败: 目录中没有 songlist 文件", LogLevel.ERROR)
                    return@launch
                }
                
                // 保存并更新状态
                _directoryUri.value = uri
                preferencesRepository.saveDirectoryUri(uri.toString())
                _importState.value = ImportState.Idle
                
                addLog("目录已选择: ${songlistRepository.getDirectoryPath(uri)}", LogLevel.SUCCESS)
            } catch (e: Exception) {
                _importState.value = ImportState.Error("无法获取目录权限: ${e.message}")
                addLog("权限获取失败: ${e.message}", LogLevel.ERROR)
            }
        }
    }
    
    /**
     * 启动 ZIP 文件选择器
     */
    fun selectZipFile(launcher: ActivityResultLauncher<String>) {
        launcher.launch("application/zip")
    }
    
    /**
     * 处理 ZIP 文件选择结果
     */
    fun onZipFileSelected(uri: Uri?) {
        if (uri == null) {
            addLog("文件选择已取消", LogLevel.WARNING)
            return
        }
        
        val directoryUri = _directoryUri.value
        if (directoryUri == null) {
            _importState.value = ImportState.Error("请先选择 songlist 目录")
            return
        }
        
        viewModelScope.launch {
            try {
                _importState.value = ImportState.ParsingZip
                addLog("正在解析压缩包...", LogLevel.INFO)
                
                // 解析 ZIP 文件
                val (songId, entries) = withContext(Dispatchers.IO) {
                    songlistRepository.parseZipFile(uri)
                }
                
                if (songId == null) {
                    _importState.value = ImportState.Error("无效的谱面包：未找到有效的 id 字段")
                    addLog("解析失败: ZIP 中未找到有效的 songlist 或 id 字段", LogLevel.ERROR)
                    return@launch
                }
                
                currentSongId = songId
                currentZipEntries = entries
                
                addLog("解析成功: 歌曲 id = $songId, 共 ${entries.size} 个文件", LogLevel.SUCCESS)
                
                // 开始解压
                _importState.value = ImportState.Extracting("", 0, entries.size)
                addLog("开始解压文件到 $songId/ 目录...", LogLevel.INFO)
                
                val extractSuccess = withContext(Dispatchers.IO) {
                    songlistRepository.extractFiles(directoryUri, songId, entries)
                }
                
                if (!extractSuccess) {
                    _importState.value = ImportState.Error("文件解压失败")
                    addLog("解压失败", LogLevel.ERROR)
                    return@launch
                }
                
                addLog("文件解压完成", LogLevel.SUCCESS)
                
                // 更新 songlist
                _importState.value = ImportState.UpdatingSonglist
                addLog("正在更新 songlist 文件...", LogLevel.INFO)
                
                val updateSuccess = withContext(Dispatchers.IO) {
                    songlistRepository.updateClientSonglist(directoryUri, entries)
                }
                
                if (!updateSuccess) {
                    _importState.value = ImportState.Error("更新 songlist 失败")
                    addLog("更新失败", LogLevel.ERROR)
                    return@launch
                }
                
                // 全部成功
                _importState.value = ImportState.Success(songId)
                addLog("导入成功！歌曲 id: $songId", LogLevel.SUCCESS)
                
            } catch (e: Exception) {
                _importState.value = ImportState.Error("导入失败: ${e.message}", e)
                addLog("导入异常: ${e.message}", LogLevel.ERROR)
            }
        }
    }
    
    /**
     * 清除当前选择的目录
     */
    fun clearDirectory() {
        viewModelScope.launch {
            _directoryUri.value = null
            preferencesRepository.clearDirectoryUri()
            _importState.value = ImportState.Idle
            addLog("目录已清除", LogLevel.INFO)
        }
    }
    
    /**
     * 重置状态为 Idle
     */
    fun resetState() {
        _importState.value = ImportState.Idle
    }
    
    /**
     * 添加日志条目
     */
    private fun addLog(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(message = message, level = level)
        _logEntries.value = _logEntries.value + entry
    }
    
    /**
     * 清除日志
     */
    fun clearLogs() {
        _logEntries.value = emptyList()
    }
}