package com.dsbot.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.ConversationIds
import com.dsbot.mobile.ui.screens.BotEditorSheet
import com.dsbot.mobile.ui.screens.ChatScreen
import com.dsbot.mobile.ui.screens.ConnectScreen
import com.dsbot.mobile.ui.screens.GroupEditorSheet
import com.dsbot.mobile.ui.screens.SettingsScreen
import com.dsbot.mobile.ui.screens.TeamScreen
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp

/**
 * Application root: two bottom tabs (Bot 团队 / 设置) plus the full-screen chat
 * destination and the editor sheets. All screen composables live in
 * `ui/screens/` and follow docs/UI-CONTRACT.md.
 */
@Composable
fun AppRoot(
    viewModel: AppViewModel,
    pendingPairingLink: State<String?>,
    onPairingLinkConsumed: () -> Unit,
) {
    val screens by viewModel.screens.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val team by viewModel.team.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val approvals by viewModel.chatApprovals.collectAsStateWithLifecycle()
    val connectError by viewModel.connectError.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val current = screens.lastOrNull() ?: Screen.Connect

    var createBotVisible by remember { mutableStateOf(false) }
    var editingBot by remember { mutableStateOf<BotProfile?>(null) }
    var createGroupVisible by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<BotGroup?>(null) }

    LaunchedEffect(Unit) { viewModel.autoConnectIfNeeded() }

    // `dsbot://pair?...` deep link from the host bridge terminal.
    LaunchedEffect(pendingPairingLink.value) {
        val raw = pendingPairingLink.value ?: return@LaunchedEffect
        viewModel.connectFromLink(raw)
        onPairingLinkConsumed()
    }

    BackHandler(enabled = screens.size > 1) { viewModel.back() }

    Box(Modifier.fillMaxSize()) {
        when (val screen = current) {
            Screen.Connect -> ConnectScreen(
                connection = connection,
                error = connectError,
                savedUrls = viewModel.savedEndpointUrls,
                onConnect = viewModel::connect,
                onForgetAll = viewModel::forgetAll,
                modifier = Modifier.fillMaxSize(),
            )

            Screen.Team -> Scaffold(
                bottomBar = {
                    AppBottomBar(
                        selectedTab = 0,
                        onSelectTeam = { viewModel.selectTab(Screen.Team) },
                        onSelectSettings = { viewModel.selectTab(Screen.Settings) },
                    )
                },
            ) { padding ->
                TeamScreen(
                    team = team,
                    connection = connection,
                    catalog = catalog,
                    onOpenConversation = viewModel::openConversation,
                    onCreateBot = { createBotVisible = true },
                    onCreateGroup = { createGroupVisible = true },
                    onEditBot = { editingBot = it },
                    onDeleteBot = { viewModel.deleteBot(it.id) },
                    onOpenSettings = { viewModel.selectTab(Screen.Settings) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }

            Screen.Settings -> Scaffold(
                bottomBar = {
                    AppBottomBar(
                        selectedTab = 1,
                        onSelectTeam = { viewModel.selectTab(Screen.Team) },
                        onSelectSettings = { viewModel.selectTab(Screen.Settings) },
                    )
                },
            ) { padding ->
                SettingsScreen(
                    connection = connection,
                    team = team,
                    tokenFingerprint = viewModel.tokenFingerprint,
                    onDisconnect = viewModel::disconnect,
                    onForgetAll = viewModel::forgetAll,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }

            is Screen.Chat -> ChatScreen(
                conversation = conversation ?: com.dsbot.mobile.bots.ConversationState(
                    conversationId = screen.conversationId,
                    kind = if (ConversationIds.isGroup(screen.conversationId)) {
                        com.dsbot.mobile.bots.ConversationKind.GROUP
                    } else {
                        com.dsbot.mobile.bots.ConversationKind.BOT
                    },
                    title = "",
                    emoji = "🤖",
                    loading = true,
                ),
                approvals = approvals,
                catalog = catalog,
                team = team,
                onBack = { viewModel.back() },
                onSend = viewModel::send,
                onStop = viewModel::stop,
                onApproval = viewModel::respondApproval,
                onClear = viewModel::clearConversation,
                onEditBot = {
                    if (ConversationIds.isBot(screen.conversationId)) {
                        team.bot(ConversationIds.targetId(screen.conversationId))?.let { editingBot = it }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        actionError?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                tonalElevation = 2.dp,
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = { viewModel.dismissActionError() }) {
                        Text("知道了")
                    }
                }
            }
        }
    }

    if (createBotVisible || editingBot != null) {
        BotEditorSheet(
            initial = editingBot,
            catalog = catalog,
            onSave = { draft ->
                val target = editingBot
                if (target == null) {
                    viewModel.createBot(draft) { ok -> if (ok) createBotVisible = false }
                } else {
                    viewModel.updateBot(target.copy(
                        name = draft.name,
                        emoji = draft.emoji,
                        colorIndex = draft.colorIndex,
                        persona = draft.persona,
                        model = draft.model,
                        cwd = draft.cwd,
                        isMain = draft.isMain,
                    )) { ok -> if (ok) editingBot = null }
                }
            },
            onDelete = editingBot?.let { bot -> { viewModel.deleteBot(bot.id); editingBot = null } },
            onDismiss = {
                createBotVisible = false
                editingBot = null
            },
        )
    }

    if (createGroupVisible || editingGroup != null) {
        GroupEditorSheet(
            bots = team.bots,
            initial = editingGroup,
            onSave = { draft ->
                val target = editingGroup
                if (target == null) {
                    viewModel.createGroup(draft) { ok -> if (ok) createGroupVisible = false }
                } else {
                    viewModel.updateGroup(
                        target.copy(
                            name = draft.name,
                            emoji = draft.emoji,
                            memberIds = draft.memberIds,
                            adminBotId = draft.adminBotId,
                        ),
                    ) { ok -> if (ok) editingGroup = null }
                }
            },
            onDelete = editingGroup?.let { group -> { viewModel.deleteGroup(group.id); editingGroup = null } },
            onDismiss = {
                createGroupVisible = false
                editingGroup = null
            },
        )
    }

    LaunchedEffect(actionError) {
        if (actionError != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.dismissActionError()
        }
    }
}

@Composable
private fun AppBottomBar(selectedTab: Int, onSelectTeam: () -> Unit, onSelectSettings: () -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = onSelectTeam,
            icon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
            label = { Text("Bot 团队") },
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = onSelectSettings,
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
    }
}
