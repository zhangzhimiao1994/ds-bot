package com.dsbot.mobile.bots

import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.ModelSelection
import kotlinx.coroutines.flow.StateFlow

/**
 * DS Bot team contract (owned by the Lead).
 *
 * A Bot is one long-lived DSH session on the paired computer plus a phone-side
 * personality (name / emoji / persona / model). The phone keeps the roster, the
 * group membership and the transcripts, and orchestrates the turns itself
 * (including bot-to-bot `@mention` relays). Implementation:
 * `bots/internal/` (owner: android-core); UI consumes only this file.
 */
interface BotTeam {

    /** Roster + per-conversation summaries for the team screen. */
    val state: StateFlow<TeamState>

    /** Approvals pending on any bot session (pass-through from the gateway). */
    val approvals: StateFlow<List<ApprovalRequest>>

    /** Load the persisted team from disk (idempotent). */
    suspend fun load(): Result<Unit>

    suspend fun createBot(draft: BotDraft): Result<BotProfile>

    suspend fun updateBot(profile: BotProfile): Result<BotProfile>

    /** Remove a Bot from the team; its DSH session stays on the computer. */
    suspend fun deleteBot(botId: String): Result<Unit>

    suspend fun createGroup(draft: GroupDraft): Result<BotGroup>

    suspend fun updateGroup(group: BotGroup): Result<BotGroup>

    suspend fun deleteGroup(groupId: String): Result<Unit>

    /** Live state for `bot:<id>` or `group:<id>`; always returns a flow. */
    fun conversation(conversationId: String): StateFlow<ConversationState>

    /** Load the transcript from disk and attach session streams. */
    suspend fun openConversation(conversationId: String): Result<Unit>

    fun closeConversation(conversationId: String)

    /** Send a user message to a bot or to a group (orchestrated turn). */
    suspend fun send(conversationId: String, text: String): Result<Unit>

    /** Stop every running turn in the conversation. */
    suspend fun stop(conversationId: String): Result<Unit>

    /** Clear the local transcript (DSH sessions keep their history). */
    suspend fun clear(conversationId: String): Result<Unit>

    /** Update a bot's model and its bound session. */
    suspend fun setBotModel(botId: String, selection: ModelSelection?): Result<Unit>

    /** Answer an approval (forwarded to the gateway, exactly as displayed). */
    suspend fun respondApproval(approval: ApprovalRequest, decision: ApprovalDecision): Result<Unit>

    /** Release streams and the persistence scope. */
    fun close()
}

// ---------------------------------------------------------------------------
// Roster
// ---------------------------------------------------------------------------

data class BotProfile(
    val id: String,
    val name: String,
    val emoji: String = "🤖",
    /** Index into the UI palette (0..[BotTemplates.PALETTE_SIZE]). */
    val colorIndex: Int = 0,
    /** Role instructions injected on creation and into group turns. */
    val persona: String = "",
    /** Preferred model; null = host default. */
    val model: ModelSelection? = null,
    /** Working folder on the host; null = session default. */
    val cwd: String? = null,
    /** Bound DSH session; null until provisioned (offline creation). */
    val sessionId: String? = null,
    /** Main bot: coordinates groups it belongs to. */
    val isMain: Boolean = false,
    val createdAtMillis: Long = 0L,
    val lastActiveAtMillis: Long = 0L,
) {
    val shortPersona: String
        get() = persona.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(48).orEmpty()
}

data class BotGroup(
    val id: String,
    val name: String,
    val emoji: String = "👥",
    val memberIds: List<String> = emptyList(),
    /** Answers first and may delegate with `@name`; null = every member answers. */
    val adminBotId: String? = null,
    val createdAtMillis: Long = 0L,
)

data class BotDraft(
    val name: String,
    val persona: String,
    val emoji: String = "🤖",
    val colorIndex: Int = 0,
    val model: ModelSelection? = null,
    val cwd: String? = null,
    val isMain: Boolean = false,
)

data class GroupDraft(
    val name: String,
    val memberIds: List<String>,
    val adminBotId: String? = null,
    val emoji: String = "👥",
)

// ---------------------------------------------------------------------------
// Conversations and transcript
// ---------------------------------------------------------------------------

enum class ConversationKind { BOT, GROUP }

object ConversationIds {
    const val BOT_PREFIX = "bot:"
    const val GROUP_PREFIX = "group:"

    fun bot(botId: String) = BOT_PREFIX + botId
    fun group(groupId: String) = GROUP_PREFIX + groupId
    fun isBot(id: String) = id.startsWith(BOT_PREFIX)
    fun isGroup(id: String) = id.startsWith(GROUP_PREFIX)
    fun targetId(id: String) = when {
        id.startsWith(BOT_PREFIX) -> id.removePrefix(BOT_PREFIX)
        id.startsWith(GROUP_PREFIX) -> id.removePrefix(GROUP_PREFIX)
        else -> id
    }
}

enum class AuthorKind { USER, BOT, SYSTEM }

data class ToolTrace(
    val callId: String,
    val name: String,
    /** `RUNNING` | `SUCCESS` | `ERROR` | `ABORTED` */
    val status: String = "SUCCESS",
    val summary: String? = null,
)

enum class MessageStatus { SENDING, STREAMING, DONE, ERROR, INTERRUPTED }

data class TeamMessage(
    val id: String,
    val conversationId: String,
    val authorKind: AuthorKind,
    val authorBotId: String? = null,
    val authorName: String? = null,
    val authorEmoji: String? = null,
    val authorColorIndex: Int = 0,
    val text: String = "",
    val reasoning: String? = null,
    val tools: List<ToolTrace> = emptyList(),
    val status: MessageStatus = MessageStatus.DONE,
    val error: String? = null,
    val timeMillis: Long = 0L,
    /** True when the turn came from another bot's `@mention` relay. */
    val fromMention: Boolean = false,
) {
    val isStreaming: Boolean get() = status == MessageStatus.STREAMING
}

data class ConversationState(
    val conversationId: String,
    val kind: ConversationKind,
    val title: String,
    val emoji: String,
    val messages: List<TeamMessage> = emptyList(),
    val workingBotIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    /** DSH sessions of the members; approvals are filtered by these ids. */
    val boundSessionIds: List<String> = emptyList(),
) {
    val isWorking: Boolean get() = workingBotIds.isNotEmpty()
}

data class ConversationSummary(
    val conversationId: String,
    val kind: ConversationKind,
    val title: String,
    val emoji: String,
    val lastText: String? = null,
    val lastTimeMillis: Long = 0L,
    val workingBotIds: Set<String> = emptySet(),
    val memberIds: List<String> = emptyList(),
)

data class TeamState(
    val loaded: Boolean = false,
    val bots: List<BotProfile> = emptyList(),
    val groups: List<BotGroup> = emptyList(),
    val summaries: List<ConversationSummary> = emptyList(),
    val lastError: String? = null,
) {
    fun bot(id: String): BotProfile? = bots.firstOrNull { it.id == id }
    fun group(id: String): BotGroup? = groups.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------
// Persona presets (create-bot quick picks)
// ---------------------------------------------------------------------------

data class BotTemplate(
    val name: String,
    val emoji: String,
    val tagline: String,
    val persona: String,
    val colorIndex: Int,
)

object BotTemplates {
    const val PALETTE_SIZE = 6

    val all: List<BotTemplate> = listOf(
        BotTemplate(
            name = "小闪", emoji = "⚡", tagline = "主 Bot · 调度与汇总", colorIndex = 0,
            persona = "你是团队的主 Bot「小闪」，负责理解用户目标、拆解任务并把工作分给合适的成员。" +
                "回答简洁、先给结论再给理由；需要其他成员接手时，在回复里用 @成员名 提及并说明要他做什么。",
        ),
        BotTemplate(
            name = "代码匠", emoji = "🛠", tagline = "工程师 · 读写代码与修复", colorIndex = 1,
            persona = "你是资深软件工程师「代码匠」，擅长阅读代码、定位缺陷、编写补丁与测试。给出可直接执行的命令和最小改动；不确定时先说明假设。",
        ),
        BotTemplate(
            name = "研究员", emoji = "🔎", tagline = "调研 · 查资料与对比", colorIndex = 2,
            persona = "你是研究员「研究员」，擅长拆解问题、查找资料、做对比表格并给出有依据的结论。重要事实标明来源；不确定的信息要明确说明。",
        ),
        BotTemplate(
            name = "笔杆子", emoji = "✍️", tagline = "写作 · 文案与润色", colorIndex = 3,
            persona = "你是中文写作助手「笔杆子」，擅长结构清晰的说明文、周报、公告与润色。默认使用简洁口语化的中文，保留原文事实，不编造细节。",
        ),
        BotTemplate(
            name = "计划官", emoji = "📋", tagline = "项目管理 · 拆解与排期", colorIndex = 4,
            persona = "你是项目管理者「计划官」，擅长把模糊目标拆成可交付的步骤、估算工作量、指出风险。输出用清单和优先级表达，每项都可执行。",
        ),
        BotTemplate(
            name = "审稿人", emoji = "🧐", tagline = "评审 · 找问题与把关", colorIndex = 5,
            persona = "你是严格的审稿人「审稿人」，负责挑错、找漏洞、验证结论是否站得住脚。先列问题清单（按严重程度排序），再给出可执行的修改建议。",
        ),
    )
}
