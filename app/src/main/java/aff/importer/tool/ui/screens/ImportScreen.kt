package aff.importer.tool.ui.screens

import aff.importer.tool.MainViewModel
import aff.importer.tool.R
import aff.importer.tool.data.model.ImportState
import aff.importer.tool.ui.components.DirectoryPickerCard
import aff.importer.tool.ui.components.LogDisplay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 导入主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val importState by viewModel.importState.collectAsState()
    val directoryUri by viewModel.directoryUri.collectAsState()
    val logs by viewModel.logEntries.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    
    // 目录选择器
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        viewModel.onDirectorySelected(uri)
    }
    
    // ZIP 文件选择器
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.onZipFileSelected(uri)
    }
    
    // 错误状态显示 Snackbar
    LaunchedEffect(importState) {
        if (importState is ImportState.Error) {
            val errorMsg = (importState as ImportState.Error).message
            snackbarHostState.showSnackbar(errorMsg)
        }
    }
    
    // 成功对话框
    if (importState is ImportState.Success) {
        val songId = (importState as ImportState.Success).songId
        AlertDialog(
            onDismissRequest = { viewModel.resetState() },
            title = { Text(stringResource(R.string.status_success)) },
            text = { Text("歌曲 \"$songId\" 已成功导入！") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetState() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
    
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 目录选择卡片
            DirectoryPickerCard(
                directoryUri = directoryUri,
                onSelectDirectory = { viewModel.selectDirectory(directoryPickerLauncher) }
            )
            
            // 导入按钮
            FilledTonalButton(
                onClick = { viewModel.selectZipFile(zipPickerLauncher) },
                enabled = directoryUri != null && importState == ImportState.Idle,
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.import_chart))
            }
            
            // 进度指示器
            when (importState) {
                is ImportState.ParsingZip -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.status_parsing_zip),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                is ImportState.Extracting -> {
                    val extracting = importState as ImportState.Extracting
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { extracting.progress.toFloat() / extracting.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${stringResource(R.string.status_extracting)} ${extracting.currentFile}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                is ImportState.UpdatingSonglist -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.status_updating_songlist),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                else -> { /* 其他状态不显示进度 */ }
            }
            
            // 日志显示
            if (logs.isNotEmpty()) {
                LogDisplay(
                    logs = logs,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // 错误状态下的操作按钮
            if (importState is ImportState.Error) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { viewModel.resetState() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(
                        onClick = { viewModel.clearDirectory() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_select_again))
                    }
                }
            }
        }
    }
}