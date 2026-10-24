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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.GroupDraft
import com.dsbot.mobile.ui.common.ConfirmDialog
import com.dsbot.mobile.ui.common.DsAvatar
import com.dsbot.mobile.ui.common.DsPrimaryButton
import com.dsbot.mobile.ui.common.DsSheet
import com.dsbot.mobile.ui.common.DsSurfaces
import com.dsbot.mobile.ui.common.DsTextField
import com.dsbot.mobile.ui.common.DsTextButton
import com.dsbot.mobile.ui.common.EmojiPickerRow
import com.dsbot.mobile.ui.common.GroupEmojiOptions
import com.dsbot.mobile.ui.preview.DemoData
import com.dsbot.mobile.ui.theme.DsBotColors
import com.dsbot.mobile.ui.theme.DsBotTheme

/**
 * Create/edit sheet for a Bot group: name, emoji, members and optional admin.
 *
 * The admin Bot answers first and may delegate with `@name`; when no admin is
 * picked every member answers in order.
 */
@Composable
fun GroupEditorSheet(
    bots: List<BotProfile>,
    initial: BotGroup?,
    onSave: (GroupDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    DsSheet(onDismiss = onDismiss) {
        GroupEditorContent(
            bots = bots,
            initial = initial,
            onSave = { draft -> onSave(draft) },
            onDelete = onDelete?.let { delete -> { delete() } },
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun GroupEditorContent(
    bots: List<BotProfile>,
    initial: BotGroup?,
    onSave: (GroupDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val editing = initial != null
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var emoji by remember(initial?.id) { mutableStateOf(initial?.emoji ?: "👥") }
    var memberIds by remember(initial?.id) { mutableStateOf(initial?.memberIds?.toSet() ?: emptySet()) }
    var adminId by remember(initial?.id) { mutableStateOf(initial?.adminBotId) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var memberError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (editing) "编辑群组" else "新建群组",
            style = MaterialTheme.typography.titleLarge,
            color = DsSurfaces.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = DsSurfaces.textSecondary)
        }
    }
    Spacer(Modifier.height(4.dp))

    DsTextField(
        value = name,
        onValueChange = {
            name = it
            nameError = null
        },
        label = "群名",
        placeholder = "例如：研究小队",
        isError = nameError != null,
        supportingText = nameError,
        singleLine = true,
    )
    Spacer(Modifier.height(16.dp))

    Text("群头像", style = MaterialTheme.typography.bodyMedium, color = DsSurfaces.textPrimary)
    Spacer(Modifier.height(8.dp))
    EmojiPickerRow(options = GroupEmojiOptions, selected = emoji, onSelect = { emoji = it })
    Spacer(Modifier.height(18.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "成员",
            style = MaterialTheme.typography.bodyMedium,
            color = DsSurfaces.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "已选 ${memberIds.size} / ${bots.size}",
            style = MaterialTheme.typography.labelSmall,
            color = DsSurfaces.textSecondary,
        )
    }
    Spacer(Modifier.height(8.dp))
    if (bots.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DsSurfaces.card,
            border = BorderStroke(1.dp, DsSurfaces.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "团队里还没有 Bot。先创建 Bot，再把它们拉进群。",
                style = MaterialTheme.typography.bodySmall,
                color = DsSurfaces.textSecondary,
                modifier = Modifier.padding(14.dp),
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DsSurfaces.card,
            border = BorderStroke(1.dp, DsSurfaces.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                bots.forEach { bot ->
                    val checked = bot.id in memberIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                memberIds = if (checked) {
                                    if (adminId == bot.id) adminId = null
                                    memberIds - bot.id
                                } else {
                                    memberIds + bot.id
                                }
                                memberError = null
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = DsSurfaces.textSecondary,
                            ),
                        )
                        Spacer(Modifier.width(4.dp))
                        DsAvatar(emoji = bot.emoji, colorIndex = bot.colorIndex, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = bot.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = DsSurfaces.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (bot.isMain) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DsBotColors.Accent.copy(alpha = 0.16f),
                                    ) {
                                        Text(
                                            text = "主",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = DsBotColors.Accent,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = bot.shortPersona.ifBlank { "没有角色设定" },
                                style = MaterialTheme.typography.bodySmall,
                                color = DsSurfaces.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    memberError?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = DsBotColors.Danger)
    }
    Spacer(Modifier.height(18.dp))

    Text("管理员", style = MaterialTheme.typography.bodyMedium, color = DsSurfaces.textPrimary)
    Spacer(Modifier.height(2.dp))
    Text(
        text = "管理员先回答，并可以用 @ 把任务分给其他成员；不选则全体按顺序回答。",
        style = MaterialTheme.typography.bodySmall,
        color = DsSurfaces.textSecondary,
    )
    Spacer(Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DsSurfaces.card,
        border = BorderStroke(1.dp, DsSurfaces.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            AdminRow(
                title = "无（所有成员轮流回答）",
                selected = adminId == null,
                enabled = true,
                onClick = { adminId = null },
            )
            bots.filter { it.id in memberIds }.forEach { bot ->
                AdminRow(
                    title = bot.name,
                    emoji = bot.emoji,
                    colorIndex = bot.colorIndex,
                    selected = adminId == bot.id,
                    enabled = true,
                    onClick = { adminId = bot.id },
                )
            }
            if (memberIds.isEmpty()) {
                Text(
                    text = "先在上面选中成员，才能指定管理员。",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    DsPrimaryButton(
        text = if (editing) "保存修改" else "创建群组",
        onClick = {
            var valid = true
            if (name.isBlank()) {
                nameError = "先给群组起个名字"
                valid = false
            }
            if (memberIds.isEmpty()) {
                memberError = "至少选择一个 Bot 作为成员"
                valid = false
            }
            if (valid) {
                val ordered = bots.map { it.id }.filter { it in memberIds }
                onSave(
                    GroupDraft(
                        name = name.trim(),
                        memberIds = ordered,
                        adminBotId = adminId?.takeIf { it in memberIds },
                        emoji = emoji,
                    ),
                )
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
                text = "删除这个群组",
                onClick = { showDeleteConfirm = true },
                color = DsBotColors.Danger,
            )
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        ConfirmDialog(
            title = "删除「${initial?.name ?: name}」？",
            message = "会从团队里移除这个群组，成员 Bot 本身不会被删除，聊天记录会一并清理。",
            confirmLabel = "删除群组",
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
private fun AdminRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    emoji: String? = null,
    colorIndex: Int = 0,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = if (enabled) onClick else null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = DsSurfaces.textSecondary,
            ),
        )
        Spacer(Modifier.width(4.dp))
        if (emoji != null) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(DsSurfaces.canvas, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DsAvatar(emoji = emoji, colorIndex = colorIndex, size = 28.dp)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else DsSurfaces.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "新建群组弹层", showBackground = true, backgroundColor = 0xFF151517, heightDp = 900)
@Composable
private fun GroupEditorNewPreview() {
    DsBotTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().background(DsSurfaces.canvas)) {
            Column(Modifier.padding(20.dp)) {
                GroupEditorContent(
                    bots = DemoData.bots,
                    initial = null,
                    onSave = {},
                    onDelete = null,
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(name = "编辑群组弹层 · 浅色", showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 900)
@Composable
private fun GroupEditorEditPreview() {
    DsBotTheme(darkTheme = false) {
        Box(Modifier.fillMaxSize().background(DsSurfaces.canvas)) {
            Column(Modifier.padding(20.dp)) {
                GroupEditorContent(
                    bots = DemoData.bots,
                    initial = DemoData.group,
                    onSave = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }
    }
}
