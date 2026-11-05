package aff.importer.tool.ui.screens

import aff.importer.tool.R
import aff.importer.tool.SonglistViewModel
import aff.importer.tool.data.model.DuplicateSongGroup
import aff.importer.tool.data.model.ArtworkScanState
import aff.importer.tool.data.model.Song
import aff.importer.tool.ui.components.ImagePlaceholderIcon
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Songlist 管理主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonglistScreen(
    directoryUri: Uri?,
    modifier: Modifier = Modifier,
    viewModel: SonglistViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // 扫描进度单独订阅：它变化时只重组进度条，不会带动整个网格
    val artworkState by viewModel.artworkScanState.collectAsState()
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
    
    // 上报可见区间，让扫描队列优先处理当前看得见的曲目
    // 这里不能加 debounce：可见区间在滚动时每帧都在变，防抖会让整段滚动期间都不出图
    LaunchedEffect(lazyGridState, uiState.songs) {
        if (uiState.songs.isEmpty()) return@LaunchedEffect

        snapshotFlow {
            lazyGridState.layoutInfo.visibleItemsInfo
                .map { uiState.songs.getOrNull(it.index)?.getActualFolderId() }
                .filterNotNull()
        }
            .distinctUntilChanged()
            .collect { viewModel.prioritizeArtworks(it) }
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
                context.getString(R.string.songlist_deleted, it)
            } ?: "删除完成"
            snackbarHostState.showSnackbar(message)
            viewModel.clearDeleteSuccess()
        }
    }
    
    // 显示保存成功提示
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(context.getString(R.string.songlist_saved))
            viewModel.clearSaveSuccess()
        }
    }
    
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.songlist_title)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(
                        onClick = {
                            if (directoryUri != null) viewModel.refreshArtworks()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新曲绘")
                    }
                }
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

            // 曲绘扫描进度（只订阅进度，不影响网格）
            ArtworkScanProgress(state = artworkState)

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
                            stringResource(R.string.songlist_empty_search)
                        } else {
                            uiState.error ?: stringResource(R.string.songlist_empty)
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
                        // 曲绘由后台扫描逐个填充，当前可见的曲目会被插到队首优先解析
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(
                            items = songs,
                            // 重复条目可能存在同名 id，所以 key 里带上序号保证唯一
                            key = { index, song -> "${song.id}#$index" }
                        ) { _, song ->
                            // 曲绘 Uri 在条目自己的 composable 作用域里读取，
                            // 这样写入新值时只会重组这一张卡片
                            SongCardSimple(
                                song = song,
                                jacketUri = viewModel.artworkUriFor(song.getActualFolderId()),
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
        
        // 重复条目提示对话框
        if (uiState.showDuplicateDialog && uiState.duplicateGroups.isNotEmpty()) {
            DuplicateSongsDialog(
                groups = uiState.duplicateGroups,
                onDeleteEntry = { index -> viewModel.deleteDuplicateEntry(index) },
                onDismiss = { viewModel.dismissDuplicateDialog() }
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
        placeholder = { Text(stringResource(R.string.songlist_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
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
 * 曲绘扫描进度条。
 *
 * 只订阅扫描进度这个独立的状态流：进度变化时只重组这里，
 * 不会带着整个网格一起重组。
 */
@Composable
private fun ArtworkScanProgress(state: ArtworkScanState) {
    when (state) {
        is ArtworkScanState.Scanning -> {
            val progress = if (state.total > 0) state.completed.toFloat() / state.total else 0f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                LinearProgressIndicator(progress = { progress })
                Text(
                    text = "正在加载曲绘 ${state.completed}/${state.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> Unit
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

    // 加载失败时的占位（cacheDir 被系统清理时，已发布的 file:// Uri 会失效）
    val placeholderPainter = rememberVectorPainter(ImagePlaceholderIcon)

    // 图片请求记忆化：避免每次重组都重新构建请求对象
    val imageRequest = remember(song.id, jacketUri) {
        jacketUri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .size(256, 256)
                .build()
        }
    }

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
                imageRequest?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = placeholderPainter
                    )
                }

                // 曲绘还没准备好时显示占位图标，避免整块留白看起来像加载失败
                if (imageRequest == null) {
                    Icon(
                        imageVector = ImagePlaceholderIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.Center)
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
                    text = song.getDisplayArtist().ifBlank { context.getString(R.string.songlist_unknown_artist) },
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
            val isInscribed = diff.ratingClass == 3 && diff.ratingClassAlias == 1
            val color = when {
                isInscribed -> androidx.compose.ui.graphics.Color(0xFF00008B)
                diff.ratingClass == 0 -> androidx.compose.ui.graphics.Color(0xFF87CEEB)
                diff.ratingClass == 1 -> androidx.compose.ui.graphics.Color(0xFF90EE90)
                diff.ratingClass == 2 -> androidx.compose.ui.graphics.Color(0xFF800080)
                diff.ratingClass == 3 -> androidx.compose.ui.graphics.Color(0xFFFF0000)
                diff.ratingClass == 4 -> androidx.compose.ui.graphics.Color(0xFFDA70D6)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val textColor = when {
                isInscribed -> androidx.compose.ui.graphics.Color.White
                diff.ratingClass == 0 || diff.ratingClass == 1 || diff.ratingClass == 4 -> androidx.compose.ui.graphics.Color.Black
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
        title = { Text(stringResource(R.string.confirm_delete_title)) },
        text = {
            Text(stringResource(R.string.songlist_delete_confirm, song.getDisplayTitle(), song.id))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 重复条目处理对话框 - 让用户选择删除哪一项
 */
@Composable
private fun DuplicateSongsDialog(
    groups: List<DuplicateSongGroup>,
    onDeleteEntry: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.songlist_duplicate_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.songlist_duplicate_message),
                    style = MaterialTheme.typography.bodySmall
                )
                groups.forEach { group ->
                    Text(
                        text = stringResource(
                            R.string.songlist_duplicate_group,
                            group.id,
                            group.entries.size
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    group.entries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "#${entry.index + 1}  ${entry.song.getDisplayTitle()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val details = buildString {
                                    append(entry.song.getDisplayArtist())
                                    if (entry.song.set.isNotBlank()) append(" · ${entry.song.set}")
                                    if (entry.song.version.isNotBlank()) append(" · v${entry.song.version}")
                                }
                                Text(
                                    text = details,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { onDeleteEntry(entry.index) }) {
                                Text(
                                    text = stringResource(R.string.songlist_duplicate_delete_entry),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.songlist_duplicate_later))
            }
        }
    )
}
