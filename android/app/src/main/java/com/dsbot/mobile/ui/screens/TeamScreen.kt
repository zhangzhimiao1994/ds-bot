package com.dsbot.mobile.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.ConversationIds
import com.dsbot.mobile.bots.ConversationSummary
import com.dsbot.mobile.bots.TeamState
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.ui.common.ConfirmDialog
import com.dsbot.mobile.ui.common.ConnectionPill
import com.dsbot.mobile.ui.common.DsAvatar
import com.dsbot.mobile.ui.common.DsBadge
import com.dsbot.mobile.ui.common.DsCard
import com.dsbot.mobile.ui.common.DsSurfaces
import com.dsbot.mobile.ui.common.DsTextButton
import com.dsbot.mobile.ui.common.EmptyState
import com.dsbot.mobile.ui.common.ErrorBanner
import com.dsbot.mobile.ui.common.InfoNote
import com.dsbot.mobile.ui.common.LoadingState
import com.dsbot.mobile.ui.common.SectionHeader
import com.dsbot.mobile.ui.common.avatarColorFor
import com.dsbot.mobile.ui.common.relativeTime
import com.dsbot.mobile.ui.preview.DemoData
import com.dsbot.mobile.ui.theme.DsBotColors
import com.dsbot.mobile.ui.theme.DsBotTheme

/**
 * Bot team home: connection status, the local roster of Bots and groups.
 *
 * This is a bottom-navigation tab, so it deliberately has no back arrow and
 * leaves the bottom bar to `AppRoot`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    team: TeamState,
    connection: ConnectionState,
    catalog: ModelCatalog?,
    onOpenConversation: (String) -> Unit,
    onCreateBot: () -> Unit,
    onCreateGroup: () -> Unit,
    onEditBot: (BotProfile) -> Unit,
    onDeleteBot: (BotProfile) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<BotProfile?>(null) }
    val summariesById = remember(team.summaries) { team.summaries.associateBy { it.conversationId } }
    val isEmpty = team.loaded && team.bots.isEmpty() && team.groups.isEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DsSurfaces.canvas,
        // AppRoot (parent Scaffold) already pads for the status bar + bottom bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DS Bot · Bot 团队",
                            style = MaterialTheme.typography.titleLarge,
                            color = DsSurfaces.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        ConnectionPill(connection = connection)
                    }
                },
                actions = {
                    IconButton(onClick = onCreateGroup) {
                        Icon(
                            Icons.Filled.GroupAdd,
                            contentDescription = "新建群组",
                            tint = DsSurfaces.textPrimary,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = DsSurfaces.textPrimary,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DsSurfaces.canvas),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateBot,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("创建 Bot", fontWeight = FontWeight.Medium) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!team.loaded) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
            ) {
                if (!team.loaded && team.bots.isEmpty()) {
                    item { LoadingState("正在加载 Bot 团队…") }
                }

                team.lastError?.takeIf { it.isNotBlank() }?.let { message ->
                    item {
                        Spacer(Modifier.height(8.dp))
                        ErrorBanner(message = message)
                    }
                }

                if (!connection.isConnected && team.loaded) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        InfoNote(
                            text = if (team.bots.isEmpty())
                                "电脑还没连上。配对后 Bot 会出现在这里——它们都住在你的电脑上。"
                            else
                                "当前未连接电脑：可以浏览团队，消息会在连上后同步。",
                        )
                    }
                }

                if (isEmpty) {
                    item {
                        EmptyState(
                            emoji = "🛰",
                            title = "Bot 都住在你的电脑上，先把电脑连上",
                            body = "配对电脑后，就能在这里创建 Bot、拉群协作。手机只负责聊天与逐条审批危险操作。",
                            primaryLabel = "创建第一个 Bot",
                            onPrimary = onCreateBot,
                            secondaryLabel = "去设置连接",
                            onSecondary = onOpenSettings,
                        )
                    }
                } else {
                    item {
                        SectionHeader(
                            title = "Bots",
                            count = team.bots.size,
                            trailing = if (team.bots.isEmpty()) null else {
                                { DsTextButton(text = "新建", onClick = onCreateBot) }
                            },
                        )
                    }
                    if (team.bots.isEmpty()) {
                        item {
                            Text(
                                text = "还没有 Bot，点右下角「创建 Bot」开始。",
                                style = MaterialTheme.typography.bodySmall,
                                color = DsSurfaces.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(team.bots, key = { _, bot -> bot.id }) { _, bot ->
                        val summary = summariesById[ConversationIds.bot(bot.id)]
                        BotCard(
                            bot = bot,
                            summary = summary,
                            modelLabel = catalog?.selectionName(bot.model),
                            onClick = { onOpenConversation(ConversationIds.bot(bot.id)) },
                            onEdit = { onEditBot(bot) },
                            onDelete = { pendingDelete = bot },
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        SectionHeader(
                            title = "群组",
                            count = team.groups.size,
                            trailing = {
                                DsTextButton(text = "新建群组", onClick = onCreateGroup)
                            },
                        )
                    }
                    if (team.groups.isEmpty()) {
                        item {
                            Text(
                                text = "还没有群组。把几个 Bot 拉进一个群，它们就能互相 @ 协作。",
                                style = MaterialTheme.typography.bodySmall,
                                color = DsSurfaces.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(team.groups, key = { _, group -> group.id }) { index, group ->
                        val summary = summariesById[ConversationIds.group(group.id)]
                        GroupCard(
                            group = group,
                            bots = team.bots,
                            summary = summary,
                            colorIndex = index + 2,
                            onClick = { onOpenConversation(ConversationIds.group(group.id)) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    item {
                        Spacer(Modifier.height(12.dp))
                        InfoNote(
                            text = "Bot 与聊天记录只保存在这台手机上；电脑端可在 ~/.dsbot/audit.log 查看手机做过的操作。",
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { bot ->
        ConfirmDialog(
            title = "删除「${bot.name}」？",
            message = "会从团队里移除这个 Bot，并清理它在本机的聊天记录。电脑上的 DSH 会话不会被删除。",
            confirmLabel = "删除 Bot",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                onDeleteBot(bot)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotCard(
    bot: BotProfile,
    summary: ConversationSummary?,
    modelLabel: String?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val working = summary?.workingBotIds?.isNotEmpty() == true
    val lastTime = summary?.lastTimeMillis?.takeIf { it > 0L } ?: bot.lastActiveAtMillis
    DsCard(onClick = onClick, onLongClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsAvatar(
                emoji = bot.emoji,
                colorIndex = bot.colorIndex,
                size = 44.dp,
                working = working,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bot.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = DsSurfaces.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bot.isMain) {
                        Spacer(Modifier.width(6.dp))
                        DsBadge(text = "主")
                    }
                    if (working) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "思考中",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsBotColors.Success,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = DsSurfaces.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            containerColor = DsSurfaces.floating,
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑 Bot", color = DsSurfaces.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Edit, contentDescription = null, tint = DsSurfaces.textSecondary)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = DsBotColors.Danger) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = DsBotColors.Danger)
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = bot.shortPersona.ifBlank {
                        if (bot.sessionId == null) "尚未绑定电脑上的会话，首次发送时自动创建。" else "还没有角色设定"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DsSurfaces.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lastTime > 0L) {
                        Text(
                            text = "最后活动 ${relativeTime(lastTime)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsSurfaces.textSecondary,
                        )
                    } else {
                        Text(
                            text = "尚未使用",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsSurfaces.textSecondary,
                        )
                    }
                    if (!modelLabel.isNullOrBlank()) {
                        Text(
                            text = " · $modelLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsSurfaces.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = DsSurfaces.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: BotGroup,
    bots: List<BotProfile>,
    summary: ConversationSummary?,
    colorIndex: Int,
    onClick: () -> Unit,
) {
    val members = remember(group.memberIds, bots) {
        group.memberIds.mapNotNull { id -> bots.firstOrNull { it.id == id } }
    }
    val working = summary?.workingBotIds?.isNotEmpty() == true
    DsCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DsAvatar(emoji = group.emoji, colorIndex = colorIndex, size = 44.dp, working = working)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = DsSurfaces.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${members.size} 位成员",
                        style = MaterialTheme.typography.labelSmall,
                        color = DsSurfaces.textSecondary,
                    )
                    if (working) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "思考中",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsBotColors.Success,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary?.lastText?.takeIf { it.isNotBlank() }
                        ?: members.joinToString("、") { it.name }.ifBlank { "还没有成员" },
                    style = MaterialTheme.typography.bodySmall,
                    color = DsSurfaces.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarStack(members)
                    Spacer(Modifier.weight(1f))
                    if (summary != null && summary.lastTimeMillis > 0L) {
                        Text(
                            text = relativeTime(summary.lastTimeMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = DsSurfaces.textSecondary,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = DsSurfaces.textSecondary,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

/** Overlapping member avatars, at most four plus a "+N" chip. */
@Composable
private fun AvatarStack(members: List<BotProfile>) {
    val visible = members.take(4)
    val extra = members.size - visible.size
    val avatar = 24.dp
    val step = 16.dp
    val width = if (visible.isEmpty()) 0.dp else step * (visible.size - 1) + avatar
    Box(Modifier.height(avatar).width(width)) {
        visible.forEachIndexed { index, bot ->
            Box(
                Modifier
                    .offset(x = step * index)
                    .size(avatar)
                    .clip(CircleShape)
                    .background(DsSurfaces.canvas),
            ) {
                DsAvatar(emoji = bot.emoji, colorIndex = bot.colorIndex, size = avatar)
            }
        }
    }
    if (extra > 0) {
        Spacer(Modifier.width(6.dp))
        Text(
            text = "+$extra",
            style = MaterialTheme.typography.labelSmall,
            color = DsSurfaces.textSecondary,
        )
    } else if (visible.isNotEmpty() && members.isEmpty()) {
        Spacer(Modifier.width(4.dp))
    }
    if (members.isEmpty()) {
        Text(
            text = "无成员",
            style = MaterialTheme.typography.labelSmall,
            color = DsBotColors.Warning,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "团队页 · 深色", showBackground = true, backgroundColor = 0xFF151517, heightDp = 900)
@Composable
private fun TeamScreenPreview() {
    DsBotTheme(darkTheme = true) {
        TeamScreen(
            team = DemoData.team,
            connection = DemoData.connected,
            catalog = DemoData.catalog,
            onOpenConversation = {},
            onCreateBot = {},
            onCreateGroup = {},
            onEditBot = {},
            onDeleteBot = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "团队页 · 浅色", showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 900)
@Composable
private fun TeamScreenLightPreview() {
    DsBotTheme(darkTheme = false) {
        TeamScreen(
            team = DemoData.team,
            connection = DemoData.connected,
            catalog = DemoData.catalog,
            onOpenConversation = {},
            onCreateBot = {},
            onCreateGroup = {},
            onEditBot = {},
            onDeleteBot = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "团队页 · 空态", showBackground = true, backgroundColor = 0xFF151517, heightDp = 800)
@Composable
private fun TeamScreenEmptyPreview() {
    DsBotTheme(darkTheme = true) {
        TeamScreen(
            team = DemoData.emptyTeam,
            connection = DemoData.disconnected,
            catalog = null,
            onOpenConversation = {},
            onCreateBot = {},
            onCreateGroup = {},
            onEditBot = {},
            onDeleteBot = {},
            onOpenSettings = {},
        )
    }
}
