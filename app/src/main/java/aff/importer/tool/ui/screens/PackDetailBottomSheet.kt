package aff.importer.tool.ui.screens

import aff.importer.tool.data.model.LocalizedText
import aff.importer.tool.data.model.Pack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 曲包详情底部弹窗 - 元数据编辑
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailBottomSheet(
    pack: Pack,
    onDismiss: () -> Unit,
    onSave: (Pack) -> Unit,
    isSaving: Boolean,
    saveSuccess: Boolean,
    onClearSuccess: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )
    val scrollState = rememberScrollState()

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            onClearSuccess()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        sheetState.show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        windowInsets = WindowInsets(0, 0, 0, 0),
        dragHandle = { },
        modifier = Modifier.heightIn(max = 900.dp)
    ) {
        PackDetailContent(
            pack = pack,
            onDismiss = onDismiss,
            onSave = onSave,
            isSaving = isSaving,
            scrollState = scrollState
        )
    }
}

@Composable
private fun PackDetailContent(
    pack: Pack,
    onDismiss: () -> Unit,
    onSave: (Pack) -> Unit,
    isSaving: Boolean,
    scrollState: androidx.compose.foundation.ScrollState
) {
    var id by remember { mutableStateOf(pack.id) }
    var type by remember { mutableStateOf(pack.type) }

    var nameEn by remember { mutableStateOf(pack.nameLocalized.en) }
    var nameJa by remember { mutableStateOf(pack.nameLocalized.ja) }
    var nameKo by remember { mutableStateOf(pack.nameLocalized.ko) }
    var nameZhHans by remember { mutableStateOf(pack.nameLocalized.zhHans) }
    var nameZhHant by remember { mutableStateOf(pack.nameLocalized.zhHant) }

    var descEn by remember { mutableStateOf(pack.descriptionLocalized.en) }
    var descJa by remember { mutableStateOf(pack.descriptionLocalized.ja) }
    var descKo by remember { mutableStateOf(pack.descriptionLocalized.ko) }
    var descZhHans by remember { mutableStateOf(pack.descriptionLocalized.zhHans) }
    var descZhHant by remember { mutableStateOf(pack.descriptionLocalized.zhHant) }

    var packParent by remember { mutableStateOf(pack.packParent) }
    var isExtendPack by remember { mutableStateOf(pack.isExtendPack) }
    var customBanner by remember { mutableStateOf(pack.customBanner) }
    var plusCharacter by remember { mutableStateOf(pack.plusCharacter.toString()) }
    var section by remember { mutableStateOf(pack.section) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "编辑曲包信息",
            style = MaterialTheme.typography.headlineSmall
        )

        HorizontalDivider()

        SectionTitle("基本信息")
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("ID (只读)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
            singleLine = true
        )

        OutlinedTextField(
            value = type,
            onValueChange = { type = it },
            label = { Text("类型 (type)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        SectionTitle("曲包名称 (name_localized)")
        OutlinedTextField(
            value = nameEn,
            onValueChange = { nameEn = it },
            label = { Text("默认（英文）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = nameJa,
            onValueChange = { nameJa = it },
            label = { Text("日文") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = nameKo,
                onValueChange = { nameKo = it },
                label = { Text("韩文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = nameZhHans,
                onValueChange = { nameZhHans = it },
                label = { Text("简体中文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = nameZhHant,
                onValueChange = { nameZhHant = it },
                label = { Text("繁体中文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        SectionTitle("描述 (description_localized)")
        OutlinedTextField(
            value = descEn,
            onValueChange = { descEn = it },
            label = { Text("默认（英文）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = descJa,
            onValueChange = { descJa = it },
            label = { Text("日文") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = descKo,
                onValueChange = { descKo = it },
                label = { Text("韩文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = descZhHans,
                onValueChange = { descZhHans = it },
                label = { Text("简体中文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = descZhHant,
                onValueChange = { descZhHant = it },
                label = { Text("繁体中文") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        SectionTitle("其他设置")
        OutlinedTextField(
            value = packParent,
            onValueChange = { packParent = it },
            label = { Text("父曲包 (pack_parent)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = plusCharacter,
                onValueChange = { plusCharacter = it },
                label = { Text("Plus 角色 (plus_character)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = section,
                onValueChange = { section = it },
                label = { Text("分区 (section)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { isExtendPack = !isExtendPack }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = isExtendPack,
                    onCheckedChange = { isExtendPack = it }
                )
                Text("扩展曲包")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { customBanner = !customBanner }
            ) {
                androidx.compose.material3.Checkbox(
                    checked = customBanner,
                    onCheckedChange = { customBanner = it }
                )
                Text("自定义横幅")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                    val updatedPack = pack.copy(
                        id = id,
                        type = type,
                        nameLocalized = LocalizedText(
                            en = nameEn,
                            ja = nameJa,
                            ko = nameKo,
                            zhHans = nameZhHans,
                            zhHant = nameZhHant
                        ),
                        descriptionLocalized = LocalizedText(
                            en = descEn,
                            ja = descJa,
                            ko = descKo,
                            zhHans = descZhHans,
                            zhHant = descZhHant
                        ),
                        packParent = packParent,
                        isExtendPack = isExtendPack,
                        customBanner = customBanner,
                        plusCharacter = plusCharacter.toIntOrNull() ?: pack.plusCharacter,
                        section = section
                    )
                    onSave(updatedPack)
                },
                modifier = Modifier.weight(1f),
                enabled = !isSaving
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

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}
