package aff.importer.tool.ui.screens

import aff.importer.tool.R
import aff.importer.tool.PacklistViewModel
import aff.importer.tool.data.model.DuplicatePackGroup
import aff.importer.tool.data.model.ArtworkScanState
import aff.importer.tool.data.model.Pack
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.draw.clip
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
 * Packlist 管理主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacklistScreen(
    directoryUri: Uri?,
    modifier: Modifier = Modifier,
    viewModel: PacklistViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // 扫描进度单独订阅：它变化时只重组进度条，不会带动整个网格
    val artworkState by viewModel.artworkScanState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val lazyGridState = rememberLazyGridState()
    val context = LocalContext.current

    // 加载曲包列表
    LaunchedEffect(directoryUri) {
        viewModel.loadPacks(directoryUri)
    }

    // 上报可见区间，让扫描队列优先处理当前看得见的曲包
    // 这里不能加 debounce：可见区间在滚动时每帧都在变，防抖会让整段滚动期间都不出图
    LaunchedEffect(lazyGridState, uiState.packs) {
        if (uiState.packs.isEmpty()) return@LaunchedEffect

        snapshotFlow {
            lazyGridState.layoutInfo.visibleItemsInfo
                .map { uiState.packs.getOrNull(it.index)?.id }
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

    // 显示保存成功提示
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar(context.getString(R.string.packlist_saved))
            viewModel.clearSaveSuccess()
        }
    }

    // 显示删除成功提示
    LaunchedEffect(uiState.deleteSuccess) {
        if (uiState.deleteSuccess) {
            val message = uiState.deletedPackName?.let {
                context.getString(R.string.packlist_deleted, it)
            } ?: "删除完成"
            snackbarHostState.showSnackbar(message)
            viewModel.clearDeleteSuccess()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // 状态栏 inset 由上面的 TopAppBar 负责，底部由 MainActivity 的 NavigationBar 负责，
        // 这里再加一遍会在顶栏下方和底部各多出一段空白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.packlist_title)) },
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
                        Icon(Icons.Default.Refresh, contentDescription = "刷新封面")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showCreatePack() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.packlist_create)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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

            // 封面扫描进度（只订阅进度，不影响网格）
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
                uiState.packs.isEmpty() && !uiState.isLoading -> {
                EmptyState(
                    message = if (uiState.searchQuery.isNotEmpty()) {
                        stringResource(R.string.packlist_empty_search)
                    } else {
                        uiState.error ?: stringResource(R.string.packlist_empty)
                    },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    val packs = uiState.packs
                    // 根据屏幕宽度计算列数
                    val configuration = context.resources.configuration
                    val screenWidthDp = configuration.screenWidthDp
                    val columns = when {
                        screenWidthDp >= 840 -> 4
                        screenWidthDp >= 600 -> 3
                        else -> 2
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = lazyGridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(
                            items = packs,
                            // 重复条目可能存在同名 id，所以 key 里带上序号保证唯一
                            key = { index, pack -> "${pack.id}#$index" }
                        ) { _, pack ->
                            // 封面 Uri 在条目自己的 composable 作用域里读取，
                            // 这样写入新值时只会重组这一张卡片
                            PackCard(
                                pack = pack,
                                bannerUri = viewModel.artworkUriFor(pack.id),
                                onClick = { viewModel.selectPack(pack) },
                                onDelete = { viewModel.showDeleteConfirm(pack) }
                            )
                        }
                    }
                }
            }
        }

        // 曲包编辑底部弹窗
        uiState.selectedPack?.let { pack ->
            PackDetailBottomSheet(
                pack = pack,
                onDismiss = { viewModel.selectPack(null) },
                onSave = { updatedPack ->
                    viewModel.updatePack(updatedPack)
                },
                isSaving = uiState.isSaving,
                saveSuccess = uiState.saveSuccess,
                onClearSuccess = viewModel::clearSaveSuccess
            )
        }

        // 新建曲包弹窗
        if (uiState.showCreateDialog) {
            PackDetailBottomSheet(
                pack = Pack(id = ""),
                onDismiss = { viewModel.dismissCreatePack() },
                onSave = { newPack ->
                    viewModel.createPack(newPack)
                },
                isSaving = uiState.isSaving,
                saveSuccess = false,
                onClearSuccess = viewModel::clearSaveSuccess,
                isNew = true
            )
        }

        // 删除确认对话框
        if (uiState.showDeleteConfirm) {
            DeleteConfirmDialog(
                pack = uiState.packToDelete,
                onConfirm = { viewModel.confirmDelete() },
                onDismiss = { viewModel.dismissDeleteConfirm() }
            )
        }

        // 重复条目提示对话框
        if (uiState.showDuplicateDialog && uiState.duplicateGroups.isNotEmpty()) {
            DuplicatePacksDialog(
                groups = uiState.duplicateGroups,
                onDeleteEntry = { index -> viewModel.deleteDuplicateEntry(index) },
                onDismiss = { viewModel.dismissDuplicateDialog() }
            )
        }
    }
}

/**
 * 封面扫描进度条。
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
                    text = "正在加载封面 ${state.completed}/${state.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> Unit
    }
}

/**
 * 曲包卡片
 */
@Composable
private fun PackCard(
    pack: Pack,
    bannerUri: Uri?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 加载失败时的占位（cacheDir 被系统清理时，已发布的 file:// Uri 会失效）
    val placeholderPainter = rememberVectorPainter(ImagePlaceholderIcon)

    // 图片请求记忆化：避免每次重组都重新构建请求对象
    val imageRequest = remember(pack.id, bannerUri) {
        bannerUri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCachePolicy(CachePolicy.ENABLED)
                // 曲包封面是横幅（512x256），按横幅尺寸解码；
                // 用正方形尺寸会在 ContentScale.Crop 下裁掉左右两侧
                .size(512, 256)
                .build()
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 横幅区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                imageRequest?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = placeholderPainter
                    )
                }

                // 封面还没准备好时显示占位图标，避免整块留白看起来像加载失败
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

                // 类型标签
                val typeLabel = when (pack.type) {
                    "single" -> "单曲"
                    "pack" -> "曲包"
                    else -> pack.type
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // 曲包信息
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Text(
                    text = pack.getDisplayName(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = pack.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1
                )

                if (pack.getDisplayDescription().isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pack.getDisplayDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
        placeholder = { Text(stringResource(R.string.packlist_search_hint)) },
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
 * 删除确认对话框
 */
@Composable
private fun DeleteConfirmDialog(
    pack: Pack?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (pack == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete_title)) },
        text = {
            Text(stringResource(R.string.packlist_delete_confirm, pack.getDisplayName(), pack.id))
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
 * 重复条目处理对话框 - 让用户选择删除哪一项
 */
@Composable
private fun DuplicatePacksDialog(
    groups: List<DuplicatePackGroup>,
    onDeleteEntry: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packlist_duplicate_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.packlist_duplicate_message),
                    style = MaterialTheme.typography.bodySmall
                )
                groups.forEach { group ->
                    Text(
                        text = stringResource(
                            R.string.packlist_duplicate_group,
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
                                    text = "#${entry.index + 1}  ${entry.pack.getDisplayName()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val details = buildString {
                                    if (entry.pack.type.isNotBlank()) append("type=${entry.pack.type}")
                                    if (entry.pack.section.isNotBlank()) append(" · ${entry.pack.section}")
                                    if (entry.pack.packParent.isNotBlank()) append(" · parent=${entry.pack.packParent}")
                                }
                                if (details.isNotBlank()) {
                                    Text(
                                        text = details,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(onClick = { onDeleteEntry(entry.index) }) {
                                Text(
                                    text = stringResource(R.string.packlist_duplicate_delete_entry),
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
                Text(stringResource(R.string.packlist_duplicate_later))
            }
        }
    )
}
