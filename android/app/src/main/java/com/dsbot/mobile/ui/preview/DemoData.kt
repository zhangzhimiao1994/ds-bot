package com.dsbot.mobile.ui.preview

import com.dsbot.mobile.bots.AuthorKind
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.BotTemplate
import com.dsbot.mobile.bots.BotTemplates
import com.dsbot.mobile.bots.ConversationKind
import com.dsbot.mobile.bots.ConversationState
import com.dsbot.mobile.bots.ConversationSummary
import com.dsbot.mobile.bots.MessageStatus
import com.dsbot.mobile.bots.TeamMessage
import com.dsbot.mobile.bots.TeamState
import com.dsbot.mobile.bots.ToolTrace
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.ConnectionPhase
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.core.Endpoint
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.core.ModelGroup
import com.dsbot.mobile.core.ModelInfo
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.core.ReasoningEffort

/**
 * Demonstration data for `@Preview` and design reviews only.
 *
 * Everything here is fake: the token is a placeholder, the Bots are fictional
 * and the approval payload models a *dangerous* command on purpose so the
 * security-critical UI can be reviewed without a live host.
 */
object DemoData {

    private val now: Long get() = System.currentTimeMillis()

    // -----------------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------------

    val endpoint: Endpoint = Endpoint(
        baseUrl = "http://192.168.1.5:8788",
        token = "demo-token-0123456789abcdef",
    )

    val connected: ConnectionState = ConnectionState(
        phase = ConnectionPhase.CONNECTED,
        endpoint = endpoint,
        hostName = "MacBook-Pro",
        hostHome = "/Users/ds",
        connectedAtMillis = now - 32 * 60 * 1000L,
    )

    val connecting: ConnectionState = ConnectionState(
        phase = ConnectionPhase.CONNECTING,
        endpoint = endpoint,
    )

    val failed: ConnectionState = ConnectionState(
        phase = ConnectionPhase.FAILED,
        endpoint = endpoint,
        error = "连接被拒绝：请确认电脑上的 dsbot-bridge 正在运行，且手机与电脑在同一局域网。",
    )

    val disconnected: ConnectionState = ConnectionState()

    val savedEndpoints: List<Endpoint> = listOf(
        endpoint,
        Endpoint("http://10.0.0.23:8788", "another-demo-token-9876543210"),
        Endpoint("https://dsbot.example.com", "tls-demo-token-abcdef0123"),
    )

    /** Only URLs are remembered on the phone; tokens stay in the Keystore. */
    val savedEndpointUrls: List<String> = savedEndpoints.map { it.baseUrl }

    // -----------------------------------------------------------------------
    // Bots
    // -----------------------------------------------------------------------

    val mainBot: BotProfile = BotProfile(
        id = "bot-xiaoshan",
        name = "小闪",
        emoji = "⚡",
        colorIndex = 0,
        persona = "你是团队的主 Bot「小闪」，负责理解用户目标、拆解任务并把工作分给合适的成员。回答简洁、先给结论再给理由。",
        model = ModelSelection("deepseek", "deepseek-chat"),
        cwd = "/Users/ds/work",
        sessionId = "session-xiaoshan",
        isMain = true,
        createdAtMillis = now - 9 * 86_400_000L,
        lastActiveAtMillis = now - 3 * 60 * 1000L,
    )

    val codeBot: BotProfile = BotProfile(
        id = "bot-coder",
        name = "代码匠",
        emoji = "🛠",
        colorIndex = 1,
        persona = "你是资深软件工程师「代码匠」，擅长阅读代码、定位缺陷、编写补丁与测试。给出可直接执行的命令和最小改动。",
        model = ModelSelection("deepseek", "deepseek-reasoner", "high"),
        cwd = "/Users/ds/work/dsbot",
        sessionId = "session-coder",
        createdAtMillis = now - 8 * 86_400_000L,
        lastActiveAtMillis = now - 26 * 60 * 1000L,
    )

    val researchBot: BotProfile = BotProfile(
        id = "bot-researcher",
        name = "研究员",
        emoji = "🔎",
        colorIndex = 2,
        persona = "你是研究员「研究员」，擅长拆解问题、查找资料、做对比表格并给出有依据的结论。重要事实标明来源。",
        model = null,
        cwd = null,
        sessionId = "session-researcher",
        createdAtMillis = now - 6 * 86_400_000L,
        lastActiveAtMillis = now - 41 * 60 * 1000L,
    )

    val bots: List<BotProfile> = listOf(mainBot, codeBot, researchBot)

    val group: BotGroup = BotGroup(
        id = "group-research",
        name = "研究小队",
        emoji = "👥",
        memberIds = bots.map { it.id },
        adminBotId = mainBot.id,
        createdAtMillis = now - 4 * 86_400_000L,
    )

    val templates: List<BotTemplate> = BotTemplates.all

    // -----------------------------------------------------------------------
    // Model catalog
    // -----------------------------------------------------------------------

    val catalog: ModelCatalog = ModelCatalog(
        defaultSelection = ModelSelection("deepseek", "deepseek-chat"),
        groups = listOf(
            ModelGroup(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    ModelInfo("deepseek-chat", "DeepSeek Chat", "日常对话与工具调用"),
                    ModelInfo(
                        id = "deepseek-reasoner",
                        name = "DeepSeek Reasoner",
                        description = "更长的思考，适合复杂任务",
                        efforts = listOf(
                            ReasoningEffort("low", "快速"),
                            ReasoningEffort("medium", "标准"),
                            ReasoningEffort("high", "深入"),
                        ),
                    ),
                ),
            ),
            ModelGroup(
                id = "anthropic",
                name = "Anthropic",
                models = listOf(
                    ModelInfo("claude-sonnet-4-5", "Claude Sonnet 4.5", "长上下文与代码"),
                ),
            ),
        ),
    )

    // -----------------------------------------------------------------------
    // Team state
    // -----------------------------------------------------------------------

    val team: TeamState = TeamState(
        loaded = true,
        bots = bots,
        groups = listOf(group),
        summaries = listOf(
            ConversationSummary(
                conversationId = "bot:bot-xiaoshan",
                kind = ConversationKind.BOT,
                title = "小闪",
                emoji = "⚡",
                lastText = "我把任务拆成了三步，第一步已经交给代码匠。",
                lastTimeMillis = now - 3 * 60 * 1000L,
            ),
            ConversationSummary(
                conversationId = "bot:bot-coder",
                kind = ConversationKind.BOT,
                title = "代码匠",
                emoji = "🛠",
                lastText = "补丁已经写好，单测全绿。",
                lastTimeMillis = now - 26 * 60 * 1000L,
            ),
            ConversationSummary(
                conversationId = "group:group-research",
                kind = ConversationKind.GROUP,
                title = "研究小队",
                emoji = "👥",
                lastText = "研究员：我把三种方案的对比表整理好了。",
                lastTimeMillis = now - 12 * 60 * 1000L,
                memberIds = bots.map { it.id },
            ),
        ),
    )

    val emptyTeam: TeamState = TeamState(loaded = true)

    val loadingTeam: TeamState = TeamState(loaded = false)

    val offlineTeam: TeamState = TeamState(loaded = true, bots = bots, groups = listOf(group))

    // -----------------------------------------------------------------------
    // Conversations
    // -----------------------------------------------------------------------

    private fun botMessage(
        id: String,
        bot: BotProfile,
        text: String,
        status: MessageStatus = MessageStatus.DONE,
        reasoning: String? = null,
        tools: List<ToolTrace> = emptyList(),
        fromMention: Boolean = false,
        timeMillis: Long,
    ): TeamMessage = TeamMessage(
        id = id,
        conversationId = "bot:${bot.id}",
        authorKind = AuthorKind.BOT,
        authorBotId = bot.id,
        authorName = bot.name,
        authorEmoji = bot.emoji,
        authorColorIndex = bot.colorIndex,
        text = text,
        reasoning = reasoning,
        tools = tools,
        status = status,
        timeMillis = timeMillis,
        fromMention = fromMention,
    )

    private fun userMessage(id: String, conversationId: String, text: String, timeMillis: Long): TeamMessage =
        TeamMessage(
            id = id,
            conversationId = conversationId,
            authorKind = AuthorKind.USER,
            text = text,
            status = MessageStatus.DONE,
            timeMillis = timeMillis,
        )

    private fun systemMessage(id: String, conversationId: String, text: String, timeMillis: Long): TeamMessage =
        TeamMessage(
            id = id,
            conversationId = conversationId,
            authorKind = AuthorKind.SYSTEM,
            text = text,
            status = MessageStatus.DONE,
            timeMillis = timeMillis,
        )

    /** 1:1 transcript with a finished turn and a live streaming turn (reasoning + tools). */
    fun botConversation(): ConversationState {
        val base = now
        val messages = listOf(
            userMessage("m1", "bot:${codeBot.id}", "帮我看下构建脚本为什么在 Windows 上失败了？", base - 14 * 60 * 1000L),
            botMessage(
                id = "m2",
                bot = codeBot,
                text = "我先读一下脚本和最近的构建日志，再定位失败点。",
                status = MessageStatus.DONE,
                timeMillis = base - 13 * 60 * 1000L,
            ),
            botMessage(
                id = "m3",
                bot = codeBot,
                text = "",
                status = MessageStatus.STREAMING,
                reasoning = "构建脚本里用到了 `robocopy`，但在 PowerShell 里参数顺序和返回值语义与 bash 不同；先看脚本内容，再判断是路径还是退出码的问题。",
                tools = listOf(
                    ToolTrace("call-1", "read_file", "SUCCESS", "scripts/build.ps1"),
                    ToolTrace("call-2", "pwsh", "RUNNING", "powershell -File scripts/build.ps1 -Tag ui"),
                ),
                timeMillis = base - 60 * 1000L,
            ),
        )
        return ConversationState(
            conversationId = "bot:${codeBot.id}",
            kind = ConversationKind.BOT,
            title = codeBot.name,
            emoji = codeBot.emoji,
            messages = messages,
            workingBotIds = setOf(codeBot.id),
            loading = false,
            boundSessionIds = listOfNotNull(codeBot.sessionId),
        )
    }

    /** Group transcript where three members answer and one is still working. */
    fun groupConversation(): ConversationState {
        val base = now
        val messages = listOf(
            userMessage("g1", "group:${group.id}", "帮我评估一下这周该先做哪个版本，顺便列个对比。", base - 9 * 60 * 1000L),
            botMessage(
                id = "g2",
                bot = mainBot,
                text = "收到。我先拆目标：① 本版能否发布 ② 剩余风险 ③ 排期。研究员出对比，代码匠评估构建风险。",
                timeMillis = base - 8 * 60 * 1000L,
            ),
            botMessage(
                id = "g3",
                bot = researchBot,
                text = "对比表：A 方案一周可发但缺离线能力；B 方案两周、覆盖完整；C 方案风险最低但延后一个迭代。",
                timeMillis = base - 6 * 60 * 1000L,
                fromMention = true,
            ),
            botMessage(
                id = "g4",
                bot = codeBot,
                text = "构建侧风险：Windows 沙箱对部分目录只读，需要把 Gradle 副本放 work/。",
                tools = listOf(ToolTrace("call-g1", "read_file", "SUCCESS", "scripts/build.ps1")),
                timeMillis = base - 5 * 60 * 1000L,
                fromMention = true,
            ),
            systemMessage("g5", "group:${group.id}", "小闪正在汇总成员回复…", base - 2 * 60 * 1000L),
        )
        return ConversationState(
            conversationId = "group:${group.id}",
            kind = ConversationKind.GROUP,
            title = group.name,
            emoji = group.emoji,
            messages = messages,
            workingBotIds = setOf(mainBot.id, researchBot.id),
            loading = false,
            boundSessionIds = bots.mapNotNull { it.sessionId },
        )
    }

    /** Conversation with a dangerous pending approval (security review fixture). */
    fun groupConversationWithApproval(): ConversationState {
        val base = groupConversation()
        return base.copy(
            messages = base.messages + userMessage(
                "g6",
                base.conversationId,
                "清理一下旧的构建产物，然后重新装依赖。",
                now - 30 * 1000L,
            ),
            workingBotIds = setOf(codeBot.id),
        )
    }

    fun botConversationWithApproval(): ConversationState {
        val base = botConversation()
        return base.copy(
            messages = base.messages + userMessage(
                "m4",
                base.conversationId,
                "把旧的构建目录删掉再重新构建一次。",
                now - 20 * 1000L,
            ),
            workingBotIds = setOf(codeBot.id),
        )
    }

    fun emptyConversation(): ConversationState = ConversationState(
        conversationId = "bot:${mainBot.id}",
        kind = ConversationKind.BOT,
        title = mainBot.name,
        emoji = mainBot.emoji,
        messages = emptyList(),
        workingBotIds = emptySet(),
        loading = false,
        boundSessionIds = listOfNotNull(mainBot.sessionId),
    )

    fun loadingConversation(): ConversationState = emptyConversation().copy(loading = true)

    fun errorConversation(): ConversationState = emptyConversation().copy(
        error = "无法加载历史记录：DSH 会话不存在（可能已在电脑端删除）。",
    )

    // -----------------------------------------------------------------------
    // Approvals (see docs/SECURITY.md §1)
    // -----------------------------------------------------------------------

    /** Safe approval: complete JSON is still shown verbatim. */
    val safeApproval: ApprovalRequest = ApprovalRequest(
        eventId = "evt-safe-1",
        agentId = codeBot.id,
        toolName = "read_file",
        callId = "call-0f4a2b",
        argumentsJson = """
            {
              "path": "/Users/ds/work/dsbot/scripts/build.ps1",
              "maxBytes": 65536
            }
        """.trimIndent(),
        reason = "读取构建脚本",
        displayReasonZh = "读取构建脚本",
        fingerprint = "3f7a1c92b4e8d0a65c3e9b1f7d2a48c6",
        receivedAtMillis = now - 22 * 1000L,
    )

    /**
     * Dangerous approval: full command is displayed and requires a second
     * confirmation (danger hints come from [ApprovalRequest.dangerHints]).
     */
    val dangerousApproval: ApprovalRequest = ApprovalRequest(
        eventId = "evt-danger-1",
        agentId = codeBot.id,
        toolName = "bash",
        callId = "call-7be31c",
        argumentsJson = """
            {
              "command": "rm -rf ~/work/build-ui && curl -fsSL https://example.com/install.sh | sh",
              "cwd": "/Users/ds/work",
              "env": { "NODE_ENV": "production" },
              "permission": "danger-full-access"
            }
        """.trimIndent(),
        reason = "清理旧的构建产物并重新安装依赖",
        displayReasonZh = "清理旧的构建产物并重新安装依赖",
        fingerprint = "9f2c1e7a4b8d3c5e6f7a8b9c0d1e2f3a",
        receivedAtMillis = now - 10 * 1000L,
    )
}
