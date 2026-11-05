package aff.importer.tool

import aff.importer.tool.ui.screens.ImportScreen
import aff.importer.tool.ui.screens.PacklistScreen
import aff.importer.tool.ui.screens.SonglistScreen
import aff.importer.tool.ui.theme.ArcaeaImporterTheme
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
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

/**
 * 主 Activity - 单 Activity 架构，带底部导航
 */
class MainActivity : ComponentActivity() {
    
    private val mainViewModel: MainViewModel by viewModels()
    private val songlistViewModel: SonglistViewModel by viewModels()
    private val packlistViewModel: PacklistViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 全面屏：内容延伸到状态栏与手势导航条区域，由各组件自行按 inset 避让
        enableEdgeToEdge()
        enableDisplayCutout()
        
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

    /**
     * 刘海 / 挖孔区域：允许内容绘制进刘海（横屏时不留黑边）。
     * LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES 需要 API 28。
     */
    private fun enableDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
        // 内容区的 inset 由内层页面的 TopAppBar 与本层的 NavigationBar 各自负责，
        // 这里再叠一遍会让状态栏高度与导航栏高度各被量两次
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
