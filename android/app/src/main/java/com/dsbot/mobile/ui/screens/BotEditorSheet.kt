package com.dsbot.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsbot.mobile.bots.BotDraft
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.BotTemplate
import com.dsbot.mobile.bots.BotTemplates
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.ui.common.BotEmojiOptions
import com.dsbot.mobile.ui.common.ColorPickerRow
import com.dsbot.mobile.ui.common.ConfirmDialog
import com.dsbot.mobile.ui.common.DsPrimaryButton
import com.dsbot.mobile.ui.common.DsSheet
import com.dsbot.mobile.ui.common.DsSurfaces
import com.dsbot.mobile.ui.common.DsTextField
import com.dsbot.mobile.ui.common.DsTextButton
import com.dsbot.mobile.ui.common.DsToggleChip
import com.dsbot.mobile.ui.common.EmojiPickerRow
import com.dsbot.mobile.ui.preview.DemoData
import com.dsbot.mobile.ui.theme.DsBotColors
import com.dsbot.mobile.ui.theme.DsBotTheme

/**
 * Create/edit sheet for one Bot: personality, avatar, model, working folder and
 * whether it is the team's main Bot. Editing mode exposes the destructive
 * delete action behind a second confirmation.
 */
@Composable
fun BotEditorSheet(
    initial: BotProfile?,
    catalog: ModelCatalog?,
    templates: List<BotTemplate> = BotTemplates.all,
    onSave: (BotDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    DsSheet(onDismiss = onDismiss) {
        BotEditorContent(
            initial = initial,
            catalog = catalog,
            templates = templates,
            onSave = { draft -> onSave(draft) },
            onDelete = onDelete?.let { delete -> { delete() } },
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun BotEditorContent(
    initial: BotProfile?,
    catalog: ModelCatalog?,
    templates: List<BotTemplate>,
    onSave: (BotDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val editing = initial != null
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var persona by remember(initial?.id) { mutableStateOf(initial?.persona.orEmpty()) }
    var emoji by remember(initial?.id) { mutableStateOf(initial?.emoji ?: "🤖") }
    var colorIndex by remember(initial?.id) { mutableStateOf(initial?.colorIndex ?: 0) }
    var selection by remember(initial?.id) { mutableStateOf(initial?.model) }
    var cwd by remember(initial?.id) { mutableStateOf(initial?.cwd.orEmpty()) }
    var isMain by remember(initial?.id) { mutableStateOf(initial?.isMain ?: false) }
    var appliedTemplate by remember(initial?.id) { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (editing) "编辑 Bot" else "新建 Bot",
            style = MaterialTheme.typography.titleLarge,
            color = DsSurfaces.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭",
                tint = DsSurfaces.textSecondary,
            )
        }
    }
    Spacer(Modifier.height(4.dp))

    DsTextField(
        value = name,
        onValueChange = {
            name = it
            nameError = null
        },
        label = "名字",
        placeholder = "例如：代码匠",
        isError = nameError != null,
        supportingText = nameError,
        singleLine = true,
    )
    Spacer(Modifier.height(16.dp))

    Text("头像", style = MaterialTheme.typography.bodyMedium, color = DsSurfaces.textPrimary)
    Spacer(Modifier.height(8.dp))
    EmojiPickerRow(
        options = BotEmojiOptions,
        selected = emoji,
        onSelect = { emoji = it },
    )
    Spacer(Modifier.height(10.dp))
    ColorPickerRow(selectedIndex = colorIndex, onSelect = { colorIndex = it })
    Spacer(Modifier.height(18.dp))

    Text("角色模板", style = MaterialTheme.typography.bodyMedium, color = DsSurfaces.textPrimary)
    Spacer(Modifier.height(2.dp))
    Text(
        text = "点一个模板快速填入角色设定，之后可以继续修改。",
        style = MaterialTheme.typography.bodySmall,
        color = DsSurfaces.textSecondary,
    )
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(templates, key = { it.name }) { template ->
            DsToggleChip(
                text = template.name,
                leadingEmoji = template.emoji,
                selected = appliedTemplate == template.name,
                onClick = {
                    appliedTemplate = template.name
                    emoji = template.emoji
                    colorIndex = template.colorIndex
                    persona = template.persona
                    if (name.isBlank()) name = template.name
                    nameError = null
                },
            )
        }
    }
    appliedTemplate?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "已套用「$it」模板，角色设定可以继续编辑。",
            style = MaterialTheme.typography.labelSmall,
            color = DsBotColors.Success,
        )
    }
    Spacer(Modifier.height(18.dp))

    DsTextField(
        value = persona,
        onValueChange = { persona = it },
        label = "角色设定",
        placeholder = "告诉它你是谁、擅长什么、说话风格…",
        singleLine = false,
        minLines = 4,
        maxLines = 8,
        imeAction = androidx.compose.ui.text.input.ImeAction.Default,
    )
    Spacer(Modifier.height(16.dp))

    Text("模型", style = MaterialTheme.typography.bodyMedium, color = DsSurfaces.textPrimary)
    Spacer(Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DsSurfaces.card,
        border = BorderStroke(1.dp, DsSurfaces.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showModelPicker = true },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = selection?.let { catalog?.selectionName(it) ?: it.model } ?: "使用默认模型",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DsSurfaces.textPrimary,
                )
                val defaultName = catalog?.selectionName(catalog.defaultSelection)
                Text(
                    text = when {
                        selection == null && defaultName != null -> "电脑端默认：$defaultName"
                        selection == null -> "由电脑端的会话决定"
                        else -> "点按更换"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DsSurfaces.textSecondary,
                )
            }
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = "选择模型",
                tint = DsSurfaces.textSecondary,
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    DsTextField(
        value = cwd,
        onValueChange = { cwd = it },
        label = "工作目录",
        placeholder = "留空使用会话默认目录",
        singleLine = true,
    )
    Spacer(Modifier.height(16.dp))

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DsSurfaces.card,
        border = BorderStroke(1.dp, DsSurfaces.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "设为主 Bot",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DsSurfaces.textPrimary,
                )
                Text(
                    text = "主 Bot 负责理解目标、拆解任务并在群里 @ 其他成员。",
                    style = MaterialTheme.typography.bodySmall,
                    color = DsSurfaces.textSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = isMain,
                onCheckedChange = { isMain = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }

    Spacer(Modifier.height(20.dp))
    DsPrimaryButton(
        text = if (editing) "保存修改" else "创建 Bot",
        onClick = {
            if (name.isBlank()) {
                nameError = "先给它起个名字"
            } else {
                val draft = BotDraft(
                    name = name.trim(),
                    persona = persona.trim(),
                    emoji = emoji,
                    colorIndex = colorIndex,
                    model = selection,
                    cwd = cwd.trim().takeIf { it.isNotEmpty() },
                    isMain = isMain,
                )
                onSave(draft)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DsTextButton(text = "取消", onClick = onDismiss, color = DsSurfaces.textSecondary)
        if (editing && onDelete != null) {
            DsTextButton(
                text = "删除这个 Bot",
                onClick = { showDeleteConfirm = true },
                color = DsBotColors.Danger,
            )
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            catalog = catalog,
            selection = selection,
            onSelect = {
                selection = it
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showDeleteConfirm && onDelete != null) {
        ConfirmDialog(
            title = "删除「${initial?.name ?: name}」？",
            message = "会从团队里移除这个 Bot，并清理它在本机的聊天记录。电脑上的 DSH 会话不受影响。",
            confirmLabel = "删除 Bot",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    catalog: ModelCatalog?,
    selection: ModelSelection?,
    onSelect: (ModelSelection?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = DsSurfaces.floating,
        titleContentColor = DsSurfaces.textPrimary,
        textContentColor = DsSurfaces.textSecondary,
        title = { Text("选择模型", style = MaterialTheme.typography.titleLarge) },
        text = {
            if (catalog == null || catalog.groups.isEmpty()) {
                Text(
                    text = "模型目录还没加载。先连接电脑，或在「设置」里检查连接。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        ModelRow(
                            title = "使用默认模型",
                            subtitle = catalog.selectionName(catalog.defaultSelection)?.let { "电脑端默认：$it" },
                            selected = selection == null,
                            onClick = { onSelect(null) },
                        )
                    }
                    catalog.groups.forEach { group ->
                        item(key = "group-${group.id}") {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = DsSurfaces.textSecondary,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(group.models, key = { "${group.id}-${it.id}" }) { model ->
                            ModelRow(
                                title = model.name,
                                subtitle = model.description ?: group.name,
                                selected = selection?.provider == group.id && selection?.model == model.id,
                                onClick = { onSelect(ModelSelection(group.id, model.id)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = DsSurfaces.textSecondary)
            }
        },
    )
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else DsSurfaces.textPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DsSurfaces.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "新建 Bot 弹层", showBackground = true, backgroundColor = 0xFF151517, heightDp = 1000)
@Composable
private fun BotEditorNewPreview() {
    DsBotTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().background(DsSurfaces.canvas)) {
            Column(Modifier.padding(20.dp)) {
                BotEditorContent(
                    initial = null,
                    catalog = DemoData.catalog,
                    templates = DemoData.templates,
                    onSave = {},
                    onDelete = null,
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "编辑 Bot 弹层 · 浅色", showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 1000)
@Composable
private fun BotEditorEditPreview() {
    DsBotTheme(darkTheme = false) {
        Box(Modifier.fillMaxSize().background(DsSurfaces.canvas)) {
            Column(Modifier.padding(20.dp)) {
                BotEditorContent(
                    initial = DemoData.codeBot,
                    catalog = DemoData.catalog,
                    templates = DemoData.templates,
                    onSave = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }
    }
}
