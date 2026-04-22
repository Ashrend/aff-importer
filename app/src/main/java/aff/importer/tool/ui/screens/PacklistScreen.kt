package aff.importer.tool.ui.screens

import aff.importer.tool.PacklistViewModel
import aff.importer.tool.data.model.Pack
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
 * Packlist 管理主界面
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun PacklistScreen(
    directoryUri: Uri?,
    modifier: Modifier = Modifier,
    viewModel: PacklistViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lazyGridState = rememberLazyGridState()

    // 加载曲包列表
    LaunchedEffect(directoryUri) {
        viewModel.loadPacks(directoryUri)
    }

    // 监听滚动状态，优先加载可见区域的横幅
    LaunchedEffect(lazyGridState, uiState.packs) {
        if (uiState.packs.isEmpty()) return@LaunchedEffect

        snapshotFlow {
            lazyGridState.layoutInfo.visibleItemsInfo.map { uiState.packs.getOrNull(it.index)?.id }.filterNotNull()
        }
            .distinctUntilChanged()
            .debounce(50)
            .collect { visibleIds ->
                val packs = uiState.packs
                val visibleIndices = lazyGridState.layoutInfo.visibleItemsInfo.map { it.index }
                val firstVisible = visibleIndices.minOrNull() ?: 0
                val lastVisible = visibleIndices.maxOrNull() ?: 0

                val preloadStart = (firstVisible - 20).coerceAtLeast(0)
                val preloadEnd = (lastVisible + 20).coerceAtMost(packs.size - 1)
                val preloadIds = packs.subList(preloadStart, preloadEnd + 1).map { it.id }

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

    // 显示保存成功提示
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbarHostState.showSnackbar("保存完成")
            viewModel.clearSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("曲包管理") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = modifier
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
                uiState.packs.isEmpty() && !uiState.isLoading -> {
                    EmptyState(
                        message = if (uiState.searchQuery.isNotEmpty()) {
                            "没有找到匹配的曲包"
                        } else {
                            uiState.error ?: "没有找到任何曲包"
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
                        items(
                            items = packs,
                            key = { it.id }
                        ) { pack ->
                            PackCard(
                                pack = pack,
                                bannerUri = viewModel.getBannerUri(pack),
                                onClick = { viewModel.selectPack(pack) }
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 横幅区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                bannerUri?.let { uri ->
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
        placeholder = { Text("搜索曲包 ID 或名称") },
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
