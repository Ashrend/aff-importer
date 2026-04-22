package aff.importer.tool.ui.screens

import aff.importer.tool.SonglistViewModel
import aff.importer.tool.data.model.Song
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Songlist 管理主界面
 */
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun SonglistScreen(
    directoryUri: Uri?,
    modifier: Modifier = Modifier,
    viewModel: SonglistViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val lazyGridState = rememberLazyGridState()
    val context = LocalContext.current
    
    // 根据屏幕宽度计算列数：手机 2-3 列，平板最多 4 列
    val configuration = context.resources.configuration
    val screenWidthDp = configuration.screenWidthDp
    val columns = when {
        screenWidthDp >= 840 -> 4
        screenWidthDp >= 600 -> 3
        else -> 2
    }
    
    // 加载歌曲列表（带缓存）
    LaunchedEffect(directoryUri) {
        viewModel.loadSongs(directoryUri)
    }
    
    // 监听滚动状态，优先加载可见区域的曲绘
    LaunchedEffect(lazyGridState, uiState.songs) {
        if (uiState.songs.isEmpty()) return@LaunchedEffect
        
        snapshotFlow { 
            lazyGridState.layoutInfo.visibleItemsInfo.map { uiState.songs.getOrNull(it.index)?.id }.filterNotNull()
        }
            .distinctUntilChanged()
            .debounce(50)  // 50ms 防抖，避免过于频繁的更新
            .collect { visibleIds ->
                // 计算预加载范围（可见项前后各 PRELOAD_RANGE 项）
                val songs = uiState.songs
                val visibleIndices = lazyGridState.layoutInfo.visibleItemsInfo.map { it.index }
                val firstVisible = visibleIndices.minOrNull() ?: 0
                val lastVisible = visibleIndices.maxOrNull() ?: 0
                
                // 扩展预加载范围
                val preloadStart = (firstVisible - 20).coerceAtLeast(0)
                val preloadEnd = (lastVisible + 20).coerceAtMost(songs.size - 1)
                val preloadIds = songs.subList(preloadStart, preloadEnd + 1).map { it.id }
                
                // 通知 ViewModel 更新可见项
                viewModel.updateVisibleItems(
                    visibleItemIds = visibleIds,
                    preloadItemIds = preloadIds
                )
            }
    }
    
    // 监听滚动停止，启动后台加载
    LaunchedEffect(lazyGridState) {
        snapshotFlow { lazyGridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (!isScrolling) {
                    // 滚动停止，启动后台加载
                    viewModel.startBackgroundLoading()
                }
            }
    }
    
    // 显示错误提示
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    
    // 显示删除成功提示
    LaunchedEffect(uiState.deleteSuccess) {
        if (uiState.deleteSuccess) {
            val message = uiState.deletedSongName?.let { 
                "已删除: $it" 
            } ?: "删除完成"
            snackbarHostState.showSnackbar(message)
            viewModel.clearDeleteSuccess()
        }
    }
    
    // 显示保存成功提示
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("保存完成")
            viewModel.clearSaveSuccess()
        }
    }
    
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("曲目管理") },
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
        ) {
            // 搜索栏
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            
            // 内容区域
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.songs.isEmpty() && !uiState.isLoading -> {
                    EmptyState(
                        message = if (uiState.searchQuery.isNotEmpty()) {
                            "没有找到匹配的曲目"
                        } else {
                            uiState.error ?: "没有找到任何曲目"
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    // 歌曲网格 - 使用 remember 缓存避免重复计算
                    val songs = uiState.songs
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = lazyGridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // 关键优化：预加载屏幕外的项，避免滑动时白屏
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            items = songs,
                            key = { it.id }
                        ) { song ->
                            // 直接使用预加载的 URI，无需复杂可见性检测
                            SongCardSimple(
                                song = song,
                                jacketUri = viewModel.getJacketUri(song),
                                onClick = { viewModel.selectSong(song) },
                                onDelete = { viewModel.showDeleteConfirm(song) }
                            )
                        }
                    }
                }
            }
        }
        
        // 详情底部弹窗
        uiState.selectedSong?.let { song ->
            SongDetailBottomSheet(
                song = song,
                onDismiss = { viewModel.selectSong(null) },
                onSave = { updatedSong ->
                    viewModel.updateSong(updatedSong)
                },
                isSaving = uiState.isSaving,
                saveSuccess = uiState.saveSuccess,
                onClearSuccess = viewModel::clearSaveSuccess
            )
        }
        
        // 删除确认对话框
        if (uiState.showDeleteConfirm) {
            DeleteConfirmDialog(
                song = uiState.songToDelete,
                onConfirm = { viewModel.confirmDelete() },
                onDismiss = { viewModel.dismissDeleteConfirm() }
            )
        }
    }
}

/**
 * 搜索栏组件
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("搜索曲目 ID、标题或艺术家") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清除")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp)
    )
}

/**
 * 空状态提示
 */
@Composable
private fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 精简版歌曲卡片 - 优化滑动性能
 */
@Composable
private fun SongCardSimple(
    song: Song,
    jacketUri: Uri?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 曲绘区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                // 简化的图片加载
                jacketUri?.let { uri ->
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .crossfade(50)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .size(256, 256)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                // 删除按钮
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                // 难度徽章
                if (song.difficulties.isNotEmpty()) {
                    SimpleDifficultyBadges(song.difficulties)
                }
            }
            
            // 曲目信息 - 极简布局
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Text(
                    text = song.getDisplayTitle(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = song.getDisplayArtist().ifBlank { "未知" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = song.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1
                )
                
                // 简化的难度显示
                if (song.difficulties.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SimpleDiffList(song.difficulties)
                }
            }
        }
    }
}

/**
 * 简化的难度徽章 - 直接显示数字
 */
@Composable
private fun SimpleDifficultyBadges(difficulties: List<Song.Difficulty>) {
    val sorted = remember(difficulties) { difficulties.sortedBy { it.ratingClass } }
    
    Row(
        modifier = Modifier
            .padding(6.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sorted.forEach { diff ->
            val color = when (diff.ratingClass) {
                0 -> androidx.compose.ui.graphics.Color(0xFF87CEEB)
                1 -> androidx.compose.ui.graphics.Color(0xFF90EE90)
                2 -> androidx.compose.ui.graphics.Color(0xFF800080)
                3 -> androidx.compose.ui.graphics.Color(0xFFFF0000)
                4 -> androidx.compose.ui.graphics.Color(0xFFDA70D6)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val textColor = when (diff.ratingClass) {
                0, 1, 4 -> androidx.compose.ui.graphics.Color.Black
                else -> androidx.compose.ui.graphics.Color.White
            }
            
            Box(
                modifier = Modifier
                    .background(color, RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (diff.ratingPlus) "${diff.rating}+" else "${diff.rating}",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
            }
        }
    }
}

/**
 * 简化的难度列表
 */
@Composable
private fun SimpleDiffList(difficulties: List<Song.Difficulty>) {
    val sorted = remember(difficulties) { difficulties.sortedBy { it.ratingClass } }
    
    Column {
        sorted.forEach { diff ->
            val diffName = when (diff.ratingClass) {
                0 -> "PST"
                1 -> "PRS"
                2 -> "FTR"
                3 -> "BYD"
                4 -> "ETR"
                else -> "?"
            }
            val rating = if (diff.ratingPlus) "${diff.rating}+" else "${diff.rating}"
            
            Row {
                Text(
                    text = "$diffName $rating",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                diff.chartDesigner.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = " | $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 删除确认对话框
 */
@Composable
private fun DeleteConfirmDialog(
    song: Song?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (song == null) return
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = {
            Text("确定要删除曲目 \"${song.getDisplayTitle()}\" (${song.id}) 吗？\n\n这将从 songlist 中移除该曲目的元数据，并删除整个乐曲文件夹。此操作不可撤销。")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}