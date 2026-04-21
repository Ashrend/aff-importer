package aff.importer.tool.ui.screens

import aff.importer.tool.data.model.Song
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 歌曲详情底部弹窗 - 元数据编辑（优化版，更快响应）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailBottomSheet(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (Song) -> Unit,
    isSaving: Boolean,
    saveSuccess: Boolean,
    onClearSuccess: () -> Unit
) {
    // 使用 skipPartiallyExpanded = true 避免中间状态，直接展开
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )
    val scrollState = rememberScrollState()
    
    // 成功保存后关闭
    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            onClearSuccess()
            onDismiss()
        }
    }
    
    // 立即展开，减少动画延迟
    LaunchedEffect(Unit) {
        sheetState.show()
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 减少窗口内边距，让内容更大
        windowInsets = WindowInsets(0, 0, 0, 0),
        dragHandle = { /* 隐藏拖动手柄，减少渲染负担 */ },
        modifier = Modifier.heightIn(max = 900.dp)
    ) {
        SongDetailContent(
            song = song,
            onDismiss = onDismiss,
            onSave = onSave,
            isSaving = isSaving,
            scrollState = scrollState
        )
    }
}

@Composable
private fun SongDetailContent(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (Song) -> Unit,
    isSaving: Boolean,
    scrollState: androidx.compose.foundation.ScrollState
) {
    // 编辑状态 - 基本字段
    var id by remember { mutableStateOf(song.id) }
    // 标题多语言（英文是默认值，直接填在"默认"位置）
    var titleDefault by remember { mutableStateOf(song.titleLocalized.en) }
    var titleJa by remember { mutableStateOf(song.titleLocalized.ja) }
    var titleKo by remember { mutableStateOf(song.titleLocalized.ko) }
    var titleZhHans by remember { mutableStateOf(song.titleLocalized.zhHans) }
    var titleZhHant by remember { mutableStateOf(song.titleLocalized.zhHant) }
    
    // 艺术家（英文是默认值，直接填在"默认"位置）
    var artistDefault by remember { mutableStateOf(song.artist.takeIf { it.isNotBlank() } ?: song.artistLocalized.en) }
    var artistJa by remember { mutableStateOf(song.artistLocalized.ja) }
    var artistKo by remember { mutableStateOf(song.artistLocalized.ko) }
    
    var bpm by remember { mutableStateOf(song.bpm) }
    var bpmBase by remember { mutableStateOf(song.bpmBase.toString()) }
    var set by remember { mutableStateOf(song.set) }
    var purchase by remember { mutableStateOf(song.purchase) }
    var bg by remember { mutableStateOf(song.bg) }
    var bgInverse by remember { mutableStateOf(song.bgInverse) }
    var side by remember { mutableStateOf(song.side.toString()) }
    var version by remember { mutableStateOf(song.version) }
    var idx by remember { mutableStateOf(song.idx.toString()) }
    var audioPreview by remember { mutableStateOf(song.audioPreview.toString()) }
    var audioPreviewEnd by remember { mutableStateOf(song.audioPreviewEnd.toString()) }
    
    var worldUnlock by remember { mutableStateOf(song.worldUnlock) }
    var remoteDl by remember { mutableStateOf(song.remoteDl) }
    
    // 难度编辑状态
    var difficulties by remember { mutableStateOf(song.difficulties) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding() // 键盘弹出时自动调整
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Text(
            text = "编辑曲目信息",
            style = MaterialTheme.typography.headlineSmall
        )

        HorizontalDivider()

        // 基本信息
        SectionTitle("基本信息")
        OutlinedTextField(
            value = idx,
            onValueChange = { idx = it },
            label = { Text("IDX") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("ID (只读)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            singleLine = true
        )

        // 标题（多语言）- 英文是默认值
        SectionTitle("曲目标题 (title_localized)")
        OutlinedTextField(
            value = titleDefault,
            onValueChange = { titleDefault = it },
            label = { Text("默认（英文）*") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = titleJa,
            onValueChange = { titleJa = it },
            label = { Text("日文") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = titleKo,
                onValueChange = { titleKo = it },
                label = { Text("韩文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = titleZhHans,
                onValueChange = { titleZhHans = it },
                label = { Text("简体中文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = titleZhHant,
                onValueChange = { titleZhHant = it },
                label = { Text("繁体中文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        // 艺术家 - 英文是默认值
        SectionTitle("艺术家")
        OutlinedTextField(
            value = artistDefault,
            onValueChange = { artistDefault = it },
            label = { Text("默认（英文）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = artistJa,
                onValueChange = { artistJa = it },
                label = { Text("日文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = artistKo,
                onValueChange = { artistKo = it },
                label = { Text("韩文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        
        // 音频预览
        SectionTitle("音频预览 (毫秒)")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = audioPreview,
                onValueChange = { audioPreview = it },
                label = { Text("开始 (audioPreview)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = audioPreviewEnd,
                onValueChange = { audioPreviewEnd = it },
                label = { Text("结束 (audioPreviewEnd)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        // BPM 信息
        SectionTitle("BPM 信息")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = bpm,
                onValueChange = { bpm = it },
                label = { Text("BPM 显示") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = bpmBase,
                onValueChange = { bpmBase = it },
                label = { Text("BPM 基础值") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        // 曲包信息
        SectionTitle("曲包信息")
        OutlinedTextField(
            value = set,
            onValueChange = { set = it },
            label = { Text("曲包 (set)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = purchase,
            onValueChange = { purchase = it },
            label = { Text("购买方式 (purchase)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // 游戏设置
        SectionTitle("游戏设置")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = side,
                onValueChange = { side = it },
                label = { Text("Side (0=光, 1=对立, 2=无色, 3=Lephon)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = bg,
                onValueChange = { bg = it },
                label = { Text("背景 (bg)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        OutlinedTextField(
            value = bgInverse,
            onValueChange = { bgInverse = it },
            label = { Text("反转背景 (bg_inverse)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = version,
            onValueChange = { version = it },
            label = { Text("版本") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // 复选框选项
        SectionTitle("选项")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // world_unlock
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.clickable { worldUnlock = !worldUnlock }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = worldUnlock,
                    onCheckedChange = { worldUnlock = it }
                )
                Text("需要世界解锁")
            }

            // remote_dl
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.clickable { remoteDl = !remoteDl }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = remoteDl,
                    onCheckedChange = { remoteDl = it }
                )
                Text("远程下载")
            }
        }

        // 难度信息编辑
        SectionTitle("难度信息")
        
        // 显示已存在的难度
        difficulties.sortedBy { it.ratingClass }.forEachIndexed { index, difficulty ->
            val existingClasses = difficulties.map { it.ratingClass }.toSet()
            
            DifficultyEditor(
                difficulty = difficulty,
                onUpdate = { updated ->
                    difficulties = difficulties.toMutableList().apply {
                        val idx = this.indexOfFirst { it.ratingClass == difficulty.ratingClass }
                        if (idx >= 0) this[idx] = updated
                    }
                },
                onDelete = {
                    // 如果删除后至少还剩3个难度（0,1,2），则允许删除
                    if (difficulties.size > 3) {
                        difficulties = difficulties.filter { it.ratingClass != difficulty.ratingClass }
                    }
                },
                canDelete = difficulties.size > 3 && difficulty.ratingClass >= 3 // 只有额外难度可以删除
            )
            if (index < difficulties.size - 1) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        // 添加难度按钮
        val existingClasses = difficulties.map { it.ratingClass }.toSet()
        val availableClasses = (0..4).filter { it !in existingClasses }
        
        if (availableClasses.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            SectionTitle("添加额外难度")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableClasses.forEach { ratingClass ->
                    val diffName = when (ratingClass) {
                        3 -> "BYD"
                        4 -> "ETR"
                        else -> "难度$ratingClass"
                    }
                    Button(
                        onClick = {
                            val newDifficulty = Song.Difficulty(
                                ratingClass = ratingClass,
                                chartDesigner = "",
                                jacketDesigner = "",
                                rating = 0
                            )
                            difficulties = (difficulties + newDifficulty).sortedBy { it.ratingClass }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(diffName)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                enabled = !isSaving
            ) {
                Text("取消")
            }

            Button(
                onClick = {
                    val updatedSong = song.copy(
                        idx = idx.toIntOrNull() ?: song.idx,
                        id = id,
                        titleLocalized = Song.LocalizedText(
                            en = titleDefault,
                            ja = titleJa,
                            ko = titleKo,
                            zhHans = titleZhHans,
                            zhHant = titleZhHant
                        ),
                        artist = artistDefault,
                        artistLocalized = Song.LocalizedText(
                            en = artistDefault,
                            ja = artistJa,
                            ko = artistKo
                        ),
                        bpm = bpm,
                        bpmBase = bpmBase.toDoubleOrNull() ?: song.bpmBase,
                        set = set,
                        purchase = purchase,
                        bg = bg,
                        bgInverse = bgInverse,
                        side = side.toIntOrNull() ?: song.side,
                        version = version,
                        audioPreview = audioPreview.toIntOrNull() ?: song.audioPreview,
                        audioPreviewEnd = audioPreviewEnd.toIntOrNull() ?: song.audioPreviewEnd,
                        worldUnlock = worldUnlock,
                        remoteDl = remoteDl,
                        difficulties = difficulties
                    )
                    onSave(updatedSong)
                },
                modifier = Modifier.weight(1f),
                enabled = !isSaving && titleDefault.isNotBlank()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text("保存")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 难度编辑器组件
 */
@Composable
private fun DifficultyEditor(
    difficulty: Song.Difficulty,
    onUpdate: (Song.Difficulty) -> Unit,
    onDelete: (() -> Unit)? = null,
    canDelete: Boolean = false
) {
    val difficultyNames = mapOf(
        0 to "PST (Past)",
        1 to "PRS (Present)", 
        2 to "FTR (Future)",
        3 to "BYD (Beyond)",
        4 to "ETR (Eternal)"
    )
    
    // 高级选项展开状态（仅 BYD/ETR）
    var showAdvancedOptions by remember { mutableStateOf(false) }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 难度名称和删除按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = difficultyNames[difficulty.ratingClass] ?: "Unknown",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Row {
                // 高级选项开关（仅 BYD/ETR）
                if (difficulty.ratingClass >= 3) {
                    TextButton(onClick = { showAdvancedOptions = !showAdvancedOptions }) {
                        Text(if (showAdvancedOptions) "收起高级选项" else "高级选项")
                    }
                }
                
                // 删除按钮（仅额外难度显示）
                if (canDelete && onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("删除此难度")
                    }
                }
            }
        }
        
        // 谱师
        OutlinedTextField(
            value = difficulty.chartDesigner,
            onValueChange = { onUpdate(difficulty.copy(chartDesigner = it)) },
            label = { Text("谱师 (chartDesigner)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        // 绘师
        OutlinedTextField(
            value = difficulty.jacketDesigner,
            onValueChange = { onUpdate(difficulty.copy(jacketDesigner = it)) },
            label = { Text("绘师 (jacketDesigner)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        // 难度等级和评级
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = difficulty.rating.toString(),
                onValueChange = { 
                    val newRating = it.toIntOrNull() ?: difficulty.rating
                    onUpdate(difficulty.copy(rating = newRating))
                },
                label = { Text("等级 (rating)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            
            // ratingPlus 复选框
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onUpdate(difficulty.copy(ratingPlus = !difficulty.ratingPlus)) }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = difficulty.ratingPlus,
                    onCheckedChange = { onUpdate(difficulty.copy(ratingPlus = it)) }
                )
                Text("有+号")
            }
        }
        
        // BYD/ETR 高级选项
        if (difficulty.ratingClass >= 3 && showAdvancedOptions) {
            AdvancedDifficultyOptions(
                difficulty = difficulty,
                onUpdate = onUpdate
            )
        }
    }
}

/**
 * BYD/ETR 难度高级选项编辑器
 */
@Composable
private fun AdvancedDifficultyOptions(
    difficulty: Song.Difficulty,
    onUpdate: (Song.Difficulty) -> Unit
) {
    // 使用独立的字符串状态来存储用户输入，避免数值转换问题
    var dateInput by remember(difficulty.date) { 
        mutableStateOf(if (difficulty.date > 0) difficulty.date.toString() else "") 
    }
    var bpmBaseInput by remember(difficulty.bpmBase) { 
        mutableStateOf(if (difficulty.bpmBase > 0) difficulty.bpmBase.toString() else "") 
    }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = "难度特定覆盖设置",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        // 标题覆盖 - 默认英文，支持日文
        SectionTitle("标题覆盖 (title_localized)")
        OutlinedTextField(
            value = difficulty.titleLocalized.en,
            onValueChange = { 
                onUpdate(difficulty.copy(titleLocalized = difficulty.titleLocalized.copy(en = it)))
            },
            label = { Text("默认（英文）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = difficulty.titleLocalized.ja,
            onValueChange = { 
                onUpdate(difficulty.copy(titleLocalized = difficulty.titleLocalized.copy(ja = it)))
            },
            label = { Text("日文") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        // 艺术家覆盖（仅支持普通artist字段，不支持localized）
        SectionTitle("艺术家覆盖 (artist)")
        OutlinedTextField(
            value = difficulty.artist,
            onValueChange = { onUpdate(difficulty.copy(artist = it)) },
            label = { Text("artist") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        // BPM 覆盖
        SectionTitle("BPM 覆盖")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = difficulty.bpm,
                onValueChange = { onUpdate(difficulty.copy(bpm = it)) },
                label = { Text("bpm") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = bpmBaseInput,
                onValueChange = { 
                    bpmBaseInput = it
                    val newValue = it.toDoubleOrNull() ?: 0.0
                    onUpdate(difficulty.copy(bpmBase = newValue))
                },
                label = { Text("bpm_base") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        
        // 背景覆盖
        SectionTitle("背景覆盖")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = difficulty.bg,
                onValueChange = { onUpdate(difficulty.copy(bg = it)) },
                label = { Text("bg") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = difficulty.bgInverse,
                onValueChange = { onUpdate(difficulty.copy(bgInverse = it)) },
                label = { Text("bg_inverse") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        
        // 版本和日期
        SectionTitle("版本和日期")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = difficulty.version,
                onValueChange = { onUpdate(difficulty.copy(version = it)) },
                label = { Text("version") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = dateInput,
                onValueChange = { 
                    dateInput = it
                    val newValue = it.toLongOrNull() ?: 0L
                    onUpdate(difficulty.copy(date = newValue))
                },
                label = { Text("date (时间戳)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        
        // 复选框选项
        SectionTitle("其他选项")
        Column {
            // jacketOverride
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(difficulty.copy(jacketOverride = !difficulty.jacketOverride)) }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = difficulty.jacketOverride,
                    onCheckedChange = { onUpdate(difficulty.copy(jacketOverride = it)) }
                )
                Text("jacketOverride (难度特定封面)")
            }
            
            // audioOverride
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(difficulty.copy(audioOverride = !difficulty.audioOverride)) }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = difficulty.audioOverride,
                    onCheckedChange = { onUpdate(difficulty.copy(audioOverride = it)) }
                )
                Text("audioOverride (难度特定音频)")
            }
            
            // hidden_until_unlocked
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(difficulty.copy(hiddenUntilUnlocked = !difficulty.hiddenUntilUnlocked)) }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = difficulty.hiddenUntilUnlocked,
                    onCheckedChange = { onUpdate(difficulty.copy(hiddenUntilUnlocked = it)) }
                )
                Text("hidden_until_unlocked (解锁前隐藏)")
            }
            
            // world_unlock
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onUpdate(difficulty.copy(worldUnlock = !difficulty.worldUnlock)) }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = difficulty.worldUnlock,
                    onCheckedChange = { onUpdate(difficulty.copy(worldUnlock = it)) }
                )
                Text("world_unlock (需要世界模式解锁)")
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}