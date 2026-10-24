package com.dsbot.mobile.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsbot.mobile.bots.AuthorKind
import com.dsbot.mobile.bots.ConversationIds
import com.dsbot.mobile.bots.ConversationKind
import com.dsbot.mobile.bots.ConversationState
import com.dsbot.mobile.bots.MessageStatus
import com.dsbot.mobile.bots.TeamMessage
import com.dsbot.mobile.bots.TeamState
import com.dsbot.mobile.bots.ToolTrace
import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.ui.common.ConfirmDialog
import com.dsbot.mobile.ui.common.DsAvatar
import com.dsbot.mobile.ui.common.DsBadge
import com.dsbot.mobile.ui.common.DsDangerButton
import com.dsbot.mobile.ui.common.DsSurfaces
import com.dsbot.mobile.ui.common.DsTextButton
import com.dsbot.mobile.ui.common.EmptyState
import com.dsbot.mobile.ui.common.ErrorBanner
import com.dsbot.mobile.ui.common.LoadingState
import com.dsbot.mobile.ui.common.StatusDot
import com.dsbot.mobile.ui.common.clockText
import com.dsbot.mobile.ui.common.fingerprintShort
import com.dsbot.mobile.ui.common.rememberBreathing
import com.dsbot.mobile.ui.common.relativeTime
import com.dsbot.mobile.ui.preview.DemoData
import com.dsbot.mobile.ui.theme.DsBotColors
import com.dsbot.mobile.ui.theme.DsBotTheme

private val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

/**
 * One chat surface shared by 1:1 Bot chats and group chats.
 *
 * Security-critical part: approval cards always render the **complete**
 * `argumentsJson`, the request fingerprint and a second confirmation for
 * dangerous calls. There is no "always allow" action (docs/SECURITY.md §1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversation: ConversationState,
    approvals: List<ApprovalRequest>,
    catalog: ModelCatalog?,
    team: TeamState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (ApprovalRequest, ApprovalDecision) -> Unit,
    onClear: () -> Unit,
    onEditBot: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isGroup = conversation.kind == ConversationKind.GROUP
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Roster resolution: the real team roster first, transcript authors as fallback.
    val roster: List<AuthorRef> = remember(team, conversation.conversationId, conversation.messages) {
        buildList {
            val targetId = ConversationIds.targetId(conversation.conversationId)
            if (conversation.kind == ConversationKind.BOT) {
                team.bot(targetId)?.let { add(AuthorRef(it.id, it.name, it.emoji, it.colorIndex)) }
            } else {
                val memberIds = team.summaries
                    .firstOrNull { it.conversationId == conversation.conversationId }
                    ?.memberIds
                    .orEmpty()
                val memberBots = if (memberIds.isNotEmpty()) {
                    memberIds.mapNotNull { team.bot(it) }
                } else {
                    team.bots
                }
                memberBots.forEach { add(AuthorRef(it.id, it.name, it.emoji, it.colorIndex)) }
            }
            if (isEmpty()) {
                conversation.messages
                    .filter { it.authorKind == AuthorKind.BOT && it.authorBotId != null }
                    .forEach { message ->
                        message.authorBotId?.let { id ->
                            add(
                                AuthorRef(
                                    id = id,
                                    name = message.authorName ?: "成员",
                                    emoji = message.authorEmoji ?: "🤖",
                                    colorIndex = message.authorColorIndex,
                                ),
                            )
                        }
                    }
            }
        }.distinctBy { it.id }
    }
    val authors = roster
    val workingNames = conversation.workingBotIds.map { id ->
        roster.firstOrNull { it.id == id }?.name ?: "成员"
    }
    val selfBot = if (conversation.kind == ConversationKind.BOT) {
        team.bot(ConversationIds.targetId(conversation.conversationId))
    } else {
        null
    }
    val modelLabel = catalog?.selectionName(selfBot?.model)
    val mentionQuery = mentionQueryOf(input, isGroup)
    val mentionCandidates = if (mentionQuery == null) emptyList() else {
        authors.filter { it.name.contains(mentionQuery, ignoreCase = true) }
    }

    val listState = rememberLazyListState()
    val itemCount = conversation.messages.size + approvals.size
    val streamingChars = conversation.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(itemCount, streamingChars, approvals.size) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DsSurfaces.canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = DsSurfaces.textPrimary,
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DsAvatar(
                            emoji = conversation.emoji,
                            colorIndex = authors.firstOrNull()?.colorIndex ?: 0,
                            size = 34.dp,
                            working = conversation.isWorking,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = DsSurfaces.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = when {
                                    conversation.isWorking && isGroup -> "正在思考：${workingNames.joinToString("、")}"
                                    conversation.isWorking -> "正在思考…"
                                    isGroup -> "群聊 · ${authors.size} 位成员"
                                    else -> modelLabel ?: "1 对 1 对话"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (conversation.isWorking) DsBotColors.Success else DsSurfaces.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = DsSurfaces.textPrimary,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            shape = RoundedCornerShape(12.dp),
                            containerColor = DsSurfaces.floating,
                        ) {
                            if (onEditBot != null && !isGroup) {
                                DropdownMenuItem(
                                    text = { Text("编辑 Bot", color = DsSurfaces.textPrimary) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Edit, contentDescription = null, tint = DsSurfaces.textSecondary)
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onEditBot()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("清空记录", color = DsBotColors.Danger) },
                                leadingIcon = {
                                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = DsBotColors.Danger)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showClearDialog = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DsSurfaces.canvas),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            conversation.error?.takeIf { it.isNotBlank() }?.let {
                ErrorBanner(message = it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            if (isGroup && authors.isNotEmpty()) {
                GroupMemberRow(authors = authors)
            }

            if (isGroup && conversation.isWorking) {
                WorkingBanner(names = workingNames)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (conversation.messages.isEmpty() && approvals.isEmpty()) {
                    item {
                        when {
                            conversation.loading -> LoadingState("正在加载消息…")
                            else -> EmptyState(
                                emoji = conversation.emoji,
                                title = if (isGroup) "和「${conversation.title}」的成员聊聊" else "和 ${conversation.title} 开始对话",
                                body = if (isGroup)
                                    "消息会发给群里的 Bot；用 @ 可以指定让谁先回答。"
                                else
                                    "消息会发到电脑上的 DSH 会话，它会以这个 Bot 的角色回复你。",
                            )
                        }
                    }
                }

                items(conversation.messages, key = { it.id }) { message ->
                    when (message.authorKind) {
                        AuthorKind.SYSTEM -> SystemNotice(message = message)
                        AuthorKind.USER -> UserBubble(message = message)
                        AuthorKind.BOT -> BotMessage(
                            message = message,
                            showAuthor = true,
                        )
                    }
                }

                if (approvals.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "待审批 ${approvals.size} 条 · 请逐条确认",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsBotColors.Warning,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(approvals, key = { it.eventId }) { approval ->
                        ApprovalCard(
                            approval = approval,
                            handledLabel = handled[approval.eventId],
                            onDecision = { decision ->
                                handled = handled + (approval.eventId to decisionLabel(decision))
                                onApproval(approval, decision)
                            },
                        )
                    }
                }
            }

            Composer(
                input = input,
                onInputChange = { input = it },
                isGroup = isGroup,
                working = conversation.isWorking,
                mentionCandidates = mentionCandidates,
                onPickMention = { author ->
                    input = insertMention(input, author.name)
                },
                onSend = {
                    val text = input.text.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        input = TextFieldValue("")
                    }
                },
                onStop = onStop,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding(),
            )
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            title = "清空这段聊天记录？",
            message = "只会清空手机上的本地记录，电脑上的 DSH 会话历史不受影响。",
            confirmLabel = "清空记录",
            destructive = true,
            onConfirm = {
                showClearDialog = false
                onClear()
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Message row composables
// ---------------------------------------------------------------------------

private data class AuthorRef(
    val id: String,
    val name: String,
    val emoji: String,
    val colorIndex: Int,
)

@Composable
private fun UserBubble(message: TeamMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.status == MessageStatus.SENDING) {
                    Text(
                        text = "发送中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = DsSurfaces.textSecondary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = clockText(message.timeMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SystemNotice(message: TeamMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DsSurfaces.card.copy(alpha = 0.7f),
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = DsSurfaces.textSecondary,
            )
        }
    }
}

@Composable
private fun BotMessage(
    message: TeamMessage,
    showAuthor: Boolean,
) {
    val cursorAlpha = if (message.isStreaming) rememberBreathing(durationMillis = 700) else 0f
    Column {
        if (showAuthor) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsAvatar(
                    emoji = message.authorEmoji ?: "🤖",
                    colorIndex = message.authorColorIndex,
                    size = 30.dp,
                    working = message.isStreaming,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.authorName ?: "Bot",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (message.fromMention) {
                    Spacer(Modifier.width(6.dp))
                    DsBadge(text = "@ 接力", color = DsBotColors.Accent)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = clockText(message.timeMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
                if (message.isStreaming) {
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = DsBotColors.Success, size = 6.dp, pulsing = true)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "生成中",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsBotColors.Success,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
            ReasoningBlock(text = reasoning, streaming = message.isStreaming)
            Spacer(Modifier.height(6.dp))
        }

        if (message.tools.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.tools.forEach { tool -> ToolCard(tool) }
            }
            if (message.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
            }
        }

        val body = buildAnnotatedString {
            append(message.text)
            if (message.isStreaming) {
                withStyle(
                    SpanStyle(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f + 0.7f * cursorAlpha)),
                ) {
                    append("▌")
                }
            }
        }
        if (message.text.isNotBlank() || message.isStreaming) {
            Surface(
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
                color = DsSurfaces.card,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = DsSurfaces.textPrimary,
                    )
                }
            }
        }

        when (message.status) {
            MessageStatus.ERROR -> {
                Spacer(Modifier.height(6.dp))
                ErrorBanner(message = message.error ?: "这一轮回复失败了，可以重试。")
            }
            MessageStatus.INTERRUPTED -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已中断",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DsSurfaces.canvas,
        border = BorderStroke(1.dp, DsSurfaces.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = DsSurfaces.textSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (streaming) "思考过程 · 进行中" else "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                    tint = DsSurfaces.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (expanded) {
                Box(
                    Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = DsSurfaces.textSecondary,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

private fun toolStatusColor(status: String): Color = when (status.uppercase()) {
    "RUNNING" -> DsBotColors.Warning
    "SUCCESS" -> DsBotColors.Success
    "ERROR" -> DsBotColors.Danger
    "ABORTED" -> DsBotColors.DarkTextSecondary
    else -> DsBotColors.DarkTextSecondary
}

private fun toolStatusLabel(status: String): String = when (status.uppercase()) {
    "RUNNING" -> "运行中"
    "SUCCESS" -> "完成"
    "ERROR" -> "失败"
    "ABORTED" -> "已中止"
    else -> status
}

@Composable
private fun ToolCard(tool: ToolTrace) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val color = toolStatusColor(tool.status)
    val isTerminal = tool.name.lowercase() in setOf("bash", "pwsh", "shell", "cmd", "terminal", "computer")
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DsSurfaces.card.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, DsSurfaces.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = tool.summary != null) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isTerminal) Icons.Filled.Terminal else Icons.Filled.Build,
                    contentDescription = null,
                    tint = DsSurfaces.textSecondary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusDot(color = color, size = 6.dp, pulsing = tool.status.uppercase() == "RUNNING")
                Spacer(Modifier.width(4.dp))
                Text(
                    text = toolStatusLabel(tool.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                if (tool.summary != null) {
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起工具详情" else "展开工具详情",
                        tint = DsSurfaces.textSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (expanded && tool.summary != null) {
                Text(
                    text = tool.summary,
                    style = MonoStyle,
                    color = DsSurfaces.textSecondary,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupMemberRow(authors: List<AuthorRef>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            authors.take(6).forEach { author ->
                DsAvatar(emoji = author.emoji, colorIndex = author.colorIndex, size = 22.dp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${authors.size} 位成员",
            style = MaterialTheme.typography.labelSmall,
            color = DsSurfaces.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun WorkingBanner(names: List<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = DsBotColors.Success, size = 7.dp, pulsing = true)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "正在思考：${names.joinToString("、")}",
            style = MaterialTheme.typography.labelSmall,
            color = DsBotColors.Success,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Approval card — docs/SECURITY.md §1
// ---------------------------------------------------------------------------

@Composable
private fun ApprovalCard(
    approval: ApprovalRequest,
    handledLabel: String?,
    onDecision: (ApprovalDecision) -> Unit,
) {
    // The fingerprint the user was shown when the card appeared. If the current
    // request no longer matches, the request was swapped: approving is blocked.
    val shownFingerprint = remember(approval.eventId) { approval.fingerprint }
    val fingerprintMismatch = shownFingerprint != approval.fingerprint
    val handled = handledLabel != null
    val dangerous = approval.isDangerous || fingerprintMismatch
    var showDangerConfirm by remember { mutableStateOf(false) }

    val borderColor = when {
        fingerprintMismatch -> DsBotColors.Danger
        approval.isDangerous -> DsBotColors.Danger.copy(alpha = 0.85f)
        else -> DsSurfaces.border
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (handled) DsSurfaces.card.copy(alpha = 0.55f) else DsSurfaces.card,
        border = BorderStroke(1.5.dp, if (handled) DsSurfaces.border else borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (approval.isDangerous || fingerprintMismatch) Icons.Filled.Warning else Icons.Filled.Security,
                    contentDescription = null,
                    tint = if (approval.isDangerous || fingerprintMismatch) DsBotColors.Danger else DsBotColors.Accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (handled) "已处理的审批" else "需要你批准的操作",
                    style = MaterialTheme.typography.titleMedium,
                    color = DsSurfaces.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (handled) {
                    DsBadge(text = handledLabel ?: "已处理", color = DsBotColors.Success)
                } else if (approval.isDangerous) {
                    DsBadge(text = "危险", color = DsBotColors.Danger)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = relativeTime(approval.receivedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
            }

            Spacer(Modifier.height(10.dp))
            KeyValueLine(label = "工具", value = approval.toolName, mono = true)
            KeyValueLine(label = "调用 ID", value = approval.callId, mono = true)
            val reason = approval.displayReasonZh ?: approval.reason
            if (!reason.isNullOrBlank()) {
                KeyValueLine(label = "Bot 说法", value = reason, mono = false)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "完整参数（原样展示，请逐字核对）",
                style = MaterialTheme.typography.labelSmall,
                color = DsSurfaces.textSecondary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DsSurfaces.canvas,
                border = BorderStroke(1.dp, DsSurfaces.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier
                        .heightIn(max = 190.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = approval.argumentsJson?.takeIf { it.isNotBlank() } ?: "（无参数）",
                            style = MonoStyle,
                            color = DsSurfaces.textPrimary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "请求指纹 ",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
                Text(
                    text = fingerprintShort(shownFingerprint, 16),
                    style = MonoStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = if (fingerprintMismatch) DsBotColors.Danger else DsBotColors.Accent,
                )
            }
            Text(
                text = "这是你正在批准的请求指纹。Host 以 eventId 为准，指纹用于核对请求没有被替换。",
                style = MaterialTheme.typography.labelSmall,
                color = DsSurfaces.textSecondary,
                lineHeight = 15.sp,
            )

            if (approval.dangerHints.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DsBotColors.Danger.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            text = "危险提示",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsBotColors.Danger,
                            fontWeight = FontWeight.SemiBold,
                        )
                        approval.dangerHints.forEach { hint ->
                            Text(
                                text = "· $hint",
                                style = MaterialTheme.typography.labelSmall,
                                color = DsBotColors.Danger,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }

            if (fingerprintMismatch) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "⚠ 请求指纹已变化：这条审批可能被替换，已禁止批准。请让 Bot 重新发起请求后刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = DsBotColors.Danger,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (handled) {
                    Text(
                        text = "已提交「${handledLabel ?: "已处理"}」，等待电脑端确认。",
                        style = MaterialTheme.typography.bodySmall,
                        color = DsSurfaces.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(
                        onClick = { onDecision(ApprovalDecision.REJECT) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DsSurfaces.border),
                    ) {
                        Text("拒绝", color = DsSurfaces.textPrimary, fontWeight = FontWeight.Medium)
                    }
                    if (!fingerprintMismatch) {
                        if (dangerous) {
                            TextButton(
                                onClick = { showDangerConfirm = true },
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, DsBotColors.Danger),
                            ) {
                                Text(
                                    text = "确认执行危险命令",
                                    color = DsBotColors.Danger,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            TextButton(
                                onClick = { onDecision(ApprovalDecision.ALLOW_ONCE) },
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            ) {
                                Text(
                                    text = "批准一次",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "只有「批准一次」和「拒绝」两种选择；App 端没有「始终允许」。",
                style = MaterialTheme.typography.labelSmall,
                color = DsSurfaces.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (showDangerConfirm) {
        DangerApprovalDialog(
            approval = approval,
            onConfirm = {
                showDangerConfirm = false
                onDecision(ApprovalDecision.ALLOW_ONCE)
            },
            onReject = {
                showDangerConfirm = false
                onDecision(ApprovalDecision.REJECT)
            },
            onDismiss = { showDangerConfirm = false },
        )
    }
}

@Composable
private fun KeyValueLine(label: String, value: String, mono: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = DsSurfaces.textSecondary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodySmall,
            color = DsSurfaces.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Second confirmation for dangerous approvals. The complete command is shown
 * again and the default focus lands on "拒绝" (docs/SECURITY.md §1.5).
 */
@Composable
private fun DangerApprovalDialog(
    approval: ApprovalRequest,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rejectFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rejectFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = DsSurfaces.floating,
        titleContentColor = DsSurfaces.textPrimary,
        textContentColor = DsSurfaces.textSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = DsBotColors.Danger,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("确认执行危险命令？", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column {
                Text(
                    text = "工具 ${approval.toolName} 将在这台手机上被批准执行一次。请再次核对完整命令：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DsSurfaces.canvas,
                    border = BorderStroke(1.dp, DsBotColors.Danger.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = approval.argumentsJson?.takeIf { it.isNotBlank() } ?: "（无参数）",
                                style = MonoStyle,
                                color = DsSurfaces.textPrimary,
                            )
                        }
                    }
                }
                if (approval.dangerHints.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    approval.dangerHints.forEach { hint ->
                        Text(
                            text = "· $hint",
                            style = MaterialTheme.typography.labelSmall,
                            color = DsBotColors.Danger,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "指纹 ${fingerprintShort(approval.fingerprint, 16)} · 默认选择是拒绝",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认执行危险命令", color = DsBotColors.Danger, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onReject,
                modifier = Modifier.focusRequester(rejectFocus),
            ) {
                Text("拒绝", color = DsSurfaces.textPrimary, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

@Composable
private fun Composer(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    isGroup: Boolean,
    working: Boolean,
    mentionCandidates: List<AuthorRef>,
    onPickMention: (AuthorRef) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DsSurfaces.canvas,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (mentionCandidates.isNotEmpty()) {
                Text(
                    text = "选择要 @ 的成员",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mentionCandidates, key = { it.id }) { author ->
                        Surface(
                            shape = CircleShape,
                            color = DsSurfaces.card,
                            border = BorderStroke(1.dp, DsSurfaces.border),
                            modifier = Modifier.clickable { onPickMention(author) },
                        ) {
                            Row(
                                Modifier.padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DsAvatar(
                                    emoji = author.emoji,
                                    colorIndex = author.colorIndex,
                                    size = 22.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = author.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DsSurfaces.textPrimary,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = DsSurfaces.card,
                    border = BorderStroke(1.dp, DsSurfaces.border),
                    modifier = Modifier.weight(1f),
                ) {
                    TextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = if (isGroup) "发消息给群成员…  用 @ 指定成员" else "发消息…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DsSurfaces.textSecondary,
                            )
                        },
                        minLines = 1,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedTextColor = DsSurfaces.textPrimary,
                            unfocusedTextColor = DsSurfaces.textPrimary,
                        ),
                        shape = RoundedCornerShape(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (working) {
                    Surface(
                        shape = CircleShape,
                        color = DsBotColors.Danger.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, DsBotColors.Danger.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable(onClick = onStop),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止",
                                tint = DsBotColors.Danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "停止",
                                style = MaterialTheme.typography.labelSmall,
                                color = DsBotColors.Danger,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                } else {
                    val canSend = input.text.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (canSend) MaterialTheme.colorScheme.primary else DsSurfaces.card,
                        modifier = Modifier
                            .size(46.dp)
                            .clickable(enabled = canSend, onClick = onSend),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else DsSurfaces.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        working -> "Bot 回复中… 可以点「停止」中断。"
                        isGroup -> "群聊里 Bot 会按顺序回答；管理员优先。"
                        else -> "消息会发到电脑上的 DSH 会话。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                if (input.text.isNotEmpty()) {
                    DsTextButton(
                        text = "清空",
                        onClick = { onInputChange(TextFieldValue("")) },
                        color = DsSurfaces.textSecondary,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mention helpers
// ---------------------------------------------------------------------------

private fun mentionQueryOf(input: TextFieldValue, isGroup: Boolean): String? {
    if (!isGroup) return null
    val text = input.text
    val at = text.lastIndexOf('@')
    if (at < 0) return null
    val query = text.substring(at + 1)
    if (query.contains(' ') || query.contains('\n')) return null
    return query
}

private fun insertMention(input: TextFieldValue, name: String): TextFieldValue {
    val text = input.text
    val at = text.lastIndexOf('@')
    if (at < 0) return input
    val newText = text.substring(0, at) + "@" + name + " "
    return TextFieldValue(text = newText, selection = androidx.compose.ui.text.TextRange(newText.length))
}

private fun decisionLabel(decision: ApprovalDecision): String = when (decision) {
    ApprovalDecision.ALLOW_ONCE -> "批准一次"
    ApprovalDecision.REJECT -> "拒绝"
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "1:1 聊天 · 流式 + 工具", showBackground = true, backgroundColor = 0xFF151517, heightDp = 950)
@Composable
private fun ChatScreenBotPreview() {
    DsBotTheme(darkTheme = true) {
        ChatScreen(
            conversation = DemoData.botConversation(),
            approvals = listOf(DemoData.safeApproval),
            catalog = DemoData.catalog,
            team = DemoData.team,
            onBack = {},
            onSend = {},
            onStop = {},
            onApproval = { _, _ -> },
            onClear = {},
            onEditBot = {},
        )
    }
}

@Preview(name = "群聊 · 危险审批", showBackground = true, backgroundColor = 0xFF151517, heightDp = 1000)
@Composable
private fun ChatScreenGroupApprovalPreview() {
    DsBotTheme(darkTheme = true) {
        ChatScreen(
            conversation = DemoData.groupConversationWithApproval(),
            approvals = listOf(DemoData.dangerousApproval),
            catalog = DemoData.catalog,
            team = DemoData.team,
            onBack = {},
            onSend = {},
            onStop = {},
            onApproval = { _, _ -> },
            onClear = {},
        )
    }
}

@Preview(name = "群聊 · 深色", showBackground = true, backgroundColor = 0xFF151517, heightDp = 950)
@Composable
private fun ChatScreenGroupPreview() {
    DsBotTheme(darkTheme = true) {
        ChatScreen(
            conversation = DemoData.groupConversation(),
            approvals = emptyList(),
            catalog = DemoData.catalog,
            team = DemoData.team,
            onBack = {},
            onSend = {},
            onStop = {},
            onApproval = { _, _ -> },
            onClear = {},
        )
    }
}

@Preview(name = "聊天 · 浅色空态", showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 800)
@Composable
private fun ChatScreenEmptyLightPreview() {
    DsBotTheme(darkTheme = false) {
        ChatScreen(
            conversation = DemoData.emptyConversation(),
            approvals = emptyList(),
            catalog = null,
            team = DemoData.team,
            onBack = {},
            onSend = {},
            onStop = {},
            onApproval = { _, _ -> },
            onClear = {},
            onEditBot = {},
        )
    }
}
