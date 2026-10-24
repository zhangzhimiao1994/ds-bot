package com.dsbot.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dsbot.mobile.bots.BotDraft
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.BotTeam
import com.dsbot.mobile.bots.ConversationState
import com.dsbot.mobile.bots.GroupDraft
import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.DshGateway
import com.dsbot.mobile.core.Endpoint
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.core.PairingLink
import com.dsbot.mobile.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-level navigation graph (hand-rolled: the app has four destinations). */
sealed interface Screen {
    data object Connect : Screen
    data object Team : Screen
    data object Settings : Screen
    data class Chat(val conversationId: String) : Screen
}

/**
 * Owns navigation, the connection lifecycle, the bot-team bridge and the
 * transient state of the currently open conversation. Screen-local state
 * (dialogs, drafts) lives in the composables.
 */
class AppViewModel(
    private val gateway: DshGateway,
    private val botTeam: BotTeam,
    private val settings: SettingsStore,
) : ViewModel() {

    val connection = gateway.connection
    val team = botTeam.state
    val sessions = gateway.sessions

    private val _catalog = MutableStateFlow<ModelCatalog?>(null)
    val catalog: StateFlow<ModelCatalog?> = _catalog.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _banner = MutableStateFlow<String?>(null)
    val banner: StateFlow<String?> = _banner.asStateFlow()

    private val _screens = MutableStateFlow<List<Screen>>(
        listOf(if (settings.currentEndpoint() != null) Screen.Team else Screen.Connect),
    )
    val screens: StateFlow<List<Screen>> = _screens.asStateFlow()

    private val _conversation = MutableStateFlow<ConversationState?>(null)
    val conversation: StateFlow<ConversationState?> = _conversation.asStateFlow()

    private var conversationJob: Job? = null
    private var autoConnectStarted = false

    /** Approvals that belong to the currently open conversation's sessions. */
    val chatApprovals: StateFlow<List<ApprovalRequest>> =
        combine(gateway.approvals, _conversation) { approvals, conv ->
            if (conv == null) emptyList()
            else approvals.filter { it.agentId != null && it.agentId in conv.boundSessionIds }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val savedEndpointUrls: List<String> get() = settings.knownEndpointUrls()

    val tokenFingerprint: String? get() = settings.tokenFingerprint(connection.value.endpoint)

    val hostHome: StateFlow<String?> get() = gateway.hostHome

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    fun autoConnectIfNeeded() {
        if (autoConnectStarted) return
        autoConnectStarted = true
        val endpoint = settings.currentEndpoint() ?: return
        connectInternal(endpoint, remember = false)
    }

    fun connect(endpoint: Endpoint) = connectInternal(endpoint, remember = true)

    fun connectFromLink(raw: String) {
        val link = PairingLink.parse(raw)
        if (link == null) {
            _connectError.value = "配对链接格式不正确，应为 dsbot://pair?url=...&token=..."
            return
        }
        connect(link.endpoint)
    }

    private fun connectInternal(endpoint: Endpoint, remember: Boolean) {
        viewModelScope.launch {
            _connectError.value = null
            gateway.connect(endpoint)
                .onSuccess {
                    if (remember) settings.rememberEndpoint(endpoint)
                    botTeam.load()
                    _catalog.value = gateway.modelCatalog().getOrNull()
                    navigateRoot(Screen.Team)
                }
                .onFailure { error ->
                    _connectError.value = error.message ?: "连接失败"
                }
        }
    }

    fun disconnect() {
        gateway.disconnect()
        (_screens.value.lastOrNull() as? Screen.Chat)?.let { botTeam.closeConversation(it.conversationId) }
        _conversation.value = null
        _screens.value = listOf(Screen.Connect)
    }

    fun forgetAll() {
        settings.forgetAll()
        disconnect()
        _connectError.value = null
    }

    fun dismissConnectError() {
        _connectError.value = null
    }

    fun dismissActionError() {
        _actionError.value = null
        _banner.value = null
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    fun selectTab(screen: Screen) {
        closeConversationInternal()
        _screens.value = listOf(screen)
    }

    fun navigateRoot(screen: Screen) {
        closeConversationInternal()
        _screens.value = listOf(screen)
    }

    fun back(): Boolean {
        val stack = _screens.value
        if (stack.size <= 1) return false
        if (stack.lastOrNull() is Screen.Chat) closeConversationInternal()
        _screens.value = stack.dropLast(1)
        return true
    }

    fun openConversation(conversationId: String) {
        closeConversationInternal()
        _conversation.value = null
        conversationJob = viewModelScope.launch {
            botTeam.conversation(conversationId).collect { _conversation.value = it }
        }
        viewModelScope.launch {
            botTeam.openConversation(conversationId)
                .onFailure { _actionError.value = it.message ?: "无法打开对话" }
        }
        _screens.value = _screens.value + Screen.Chat(conversationId)
    }

    private fun closeConversationInternal() {
        conversationJob?.cancel()
        conversationJob = null
        (_screens.value.lastOrNull() as? Screen.Chat)?.let { botTeam.closeConversation(it.conversationId) }
        _conversation.value = null
    }

    // -----------------------------------------------------------------------
    // Conversation actions
    // -----------------------------------------------------------------------

    fun send(text: String) {
        val conversationId = (_screens.value.lastOrNull() as? Screen.Chat)?.conversationId ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            botTeam.send(conversationId, text)
                .onFailure { _actionError.value = it.message ?: "发送失败" }
        }
    }

    fun stop() {
        val conversationId = (_screens.value.lastOrNull() as? Screen.Chat)?.conversationId ?: return
        viewModelScope.launch {
            botTeam.stop(conversationId)
        }
    }

    fun clearConversation() {
        val conversationId = (_screens.value.lastOrNull() as? Screen.Chat)?.conversationId ?: return
        viewModelScope.launch {
            botTeam.clear(conversationId)
                .onFailure { _actionError.value = it.message ?: "清空失败" }
        }
    }

    fun respondApproval(approval: ApprovalRequest, decision: ApprovalDecision) {
        viewModelScope.launch {
            botTeam.respondApproval(approval, decision)
                .onFailure { _actionError.value = it.message ?: "提交审批失败" }
        }
    }

    // -----------------------------------------------------------------------
    // Roster actions
    // -----------------------------------------------------------------------

    fun createBot(draft: BotDraft, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = botTeam.createBot(draft)
            result.onFailure { _actionError.value = it.message ?: "创建 Bot 失败" }
            onResult(result.isSuccess)
        }
    }

    fun updateBot(profile: BotProfile, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = botTeam.updateBot(profile)
            result.onFailure { _actionError.value = it.message ?: "保存 Bot 失败" }
            onResult(result.isSuccess)
        }
    }

    fun deleteBot(botId: String) {
        viewModelScope.launch {
            botTeam.deleteBot(botId).onFailure { _actionError.value = it.message ?: "删除 Bot 失败" }
        }
    }

    fun createGroup(draft: GroupDraft, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = botTeam.createGroup(draft)
            result.onFailure { _actionError.value = it.message ?: "创建群组失败" }
            onResult(result.isSuccess)
        }
    }

    fun updateGroup(group: BotGroup, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = botTeam.updateGroup(group)
            result.onFailure { _actionError.value = it.message ?: "保存群组失败" }
            onResult(result.isSuccess)
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            botTeam.deleteGroup(groupId).onFailure { _actionError.value = it.message ?: "删除群组失败" }
        }
    }

    fun setBotModel(botId: String, selection: ModelSelection?) {
        viewModelScope.launch {
            botTeam.setBotModel(botId, selection).onFailure { _actionError.value = it.message ?: "切换模型失败" }
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch { _catalog.value = gateway.modelCatalog().getOrNull() }
    }

    override fun onCleared() {
        botTeam.close()
        viewModelScope.launch { gateway.close() }
        super.onCleared()
    }

    companion object {
        fun factory(context: Context, gateway: DshGateway, botTeam: BotTeam): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AppViewModel(gateway, botTeam, SettingsStore(context.applicationContext))
                }
            }
    }
}
