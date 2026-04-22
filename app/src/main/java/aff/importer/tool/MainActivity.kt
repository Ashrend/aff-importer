package aff.importer.tool

import aff.importer.tool.ui.screens.ImportScreen
import aff.importer.tool.ui.screens.PacklistScreen
import aff.importer.tool.ui.screens.SonglistScreen
import aff.importer.tool.ui.theme.ArcaeaImporterTheme
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat

/**
 * 主 Activity - 单 Activity 架构，带底部导航
 */
class MainActivity : ComponentActivity() {
    
    private val mainViewModel: MainViewModel by viewModels()
    private val songlistViewModel: SonglistViewModel by viewModels()
    private val packlistViewModel: PacklistViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 配置状态栏 - 使用传统方式确保颜色一致
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        
        setContent {
            ArcaeaImporterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        mainViewModel = mainViewModel,
                        songlistViewModel = songlistViewModel,
                        packlistViewModel = packlistViewModel
                    )
                }
            }
        }
    }
}

/**
 * 主屏幕 - 包含底部导航和页面切换
 */
@Composable
private fun MainScreen(
    mainViewModel: MainViewModel,
    songlistViewModel: SonglistViewModel,
    packlistViewModel: PacklistViewModel
) {
    var selectedItem by remember { mutableIntStateOf(0) }
    val items = listOf("导入谱面", "曲目管理", "曲包管理")
    val icons = listOf(Icons.Default.Add, Icons.AutoMirrored.Filled.List, Icons.Default.Menu)

    val directoryUri by mainViewModel.directoryUri.collectAsState()
    val importState by mainViewModel.importState.collectAsState()

    // 监听导入成功，自动刷新曲目管理和曲包管理
    LaunchedEffect(importState) {
        if (importState is aff.importer.tool.data.model.ImportState.Success) {
            songlistViewModel.refreshSongs(directoryUri)
            packlistViewModel.refreshPacks(directoryUri)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        label = { Text(item) },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedItem) {
            0 -> {
                // 导入页面
                ImportScreen(
                    viewModel = mainViewModel,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            1 -> {
                // 曲目管理页面
                SonglistScreen(
                    directoryUri = directoryUri,
                    modifier = Modifier.padding(paddingValues),
                    viewModel = songlistViewModel
                )
            }
            2 -> {
                // 曲包管理页面
                PacklistScreen(
                    directoryUri = directoryUri,
                    modifier = Modifier.padding(paddingValues),
                    viewModel = packlistViewModel
                )
            }
        }
    }
}
