package com.dsbot.mobile.bots.internal

import com.dsbot.mobile.bots.AuthorKind
import com.dsbot.mobile.bots.BotDraft
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.BotTeam
import com.dsbot.mobile.bots.BotTemplates
import com.dsbot.mobile.bots.ConversationIds
import com.dsbot.mobile.bots.ConversationKind
import com.dsbot.mobile.bots.ConversationState
import com.dsbot.mobile.bots.ConversationSummary
import com.dsbot.mobile.bots.GroupDraft
import com.dsbot.mobile.bots.MessageStatus
import com.dsbot.mobile.bots.TeamMessage
import com.dsbot.mobile.bots.TeamState
import com.dsbot.mobile.bots.ToolTrace
import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.DshException
import com.dsbot.mobile.core.DshGateway
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.core.SessionEvent
import com.dsbot.mobile.core.SessionFeed
import com.dsbot.mobile.core.StreamFrame
import com.dsbot.mobile.data.SessionFold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `BotTeam` 的真实实现（owner: android-core）。
 *
 * 职责：
 * - 维护名单/群/本地对话记录，经 [BotStore] 防抖落盘；
 * - 1:1 与群聊的会话编排：`gateway.prompt` 发消息、`gateway.follow` 拉流、
 *   [SessionFold] 折叠成一条流式 `TeamMessage`；
 * - 群聊 `@` 定向、管理员优先、Bot 之间的 `@` 接力（≤2 跳、单次 ≤4 条 Bot 消息）；
 * - `stop` 取消本机会话任务并让主机取消当前 turn；`clear` 清本地记录并立即落盘；
 * - **审批只透传**：本类永远不自动批准任何请求，`respondApproval` 原样转发
 *   调用方展示过的那一条 `ApprovalRequest`（docs/SECURITY.md §1）。
 *
 * 线程模型：所有对外方法都可以从主线程调用；文件 IO 在 [scope] 的 IO 调度器上执行
 * （生产环境由 `createBotTeam` 注入 `Dispatchers.IO`）；名单/记录用 [stateMutex] 串行化，
 * 不同会话的 turn 之间互不阻塞。
 */
internal class BotEngine(
    private val store: BotStore,
    private val gateway: DshGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ownsScope: Boolean = false,
) : BotTeam {

    // -----------------------------------------------------------------------
    // 对外状态
    // -----------------------------------------------------------------------

    private val teamState = MutableStateFlow(TeamState())

    override val state: StateFlow<TeamState> = teamState.asStateFlow()

    /** 审批列表原样透传（不缓存、不过滤、不改写）。 */
    override val approvals: StateFlow<List<ApprovalRequest>> get() = gateway.approvals

    // -----------------------------------------------------------------------
    // 内部状态
    // -----------------------------------------------------------------------

    private val stateMutex = Mutex()
    private val bots = mutableListOf<BotProfile>()
    private val groups = mutableListOf<BotGroup>()
    private val transcripts = mutableMapOf<String, MutableList<TeamMessage>>()

    /** 每个会话的消息快照（不可变，供非 suspend 的 conversation() 读取）。 */
    private val messageSnapshots = ConcurrentHashMap<String, List<TeamMessage>>()

    /** conversationId -> 正在回复的 botIds。 */
    private val working = ConcurrentHashMap<String, Set<String>>()
    private val conversations = ConcurrentHashMap<String, MutableStateFlow<ConversationState>>()

    /** conversationId -> 本次发送/编排的任务（stop() 取消它们）。 */
    private val activeJobs = ConcurrentHashMap<String, MutableSet<Job>>()

    /** 正在处理 send 的会话（同一会话不允许并发发送）。 */
    private val sending = ConcurrentHashMap.newKeySet<String>()

    private var loaded = false
    private var lastError: String? = null
    private val closed = AtomicBoolean(false)

    // -----------------------------------------------------------------------
    // BotTeam：加载与名单
    // -----------------------------------------------------------------------

    override suspend fun load(): Result<Unit> = guarded("加载团队数据失败") {
        stateMutex.withLock {
            if (loaded) return@withLock
            val result = store.load()
            bots.clear()
            bots += result.snapshot.bots
            groups.clear()
            groups += result.snapshot.groups
            transcripts.clear()
            result.snapshot.transcripts.forEach { (conversationId, messages) ->
                transcripts[conversationId] = messages.takeLast(MAX_MESSAGES_PER_CONVERSATION).toMutableList()
            }
            messageSnapshots.clear()
            transcripts.keys.forEach { syncSnapshotLocked(it) }
            loaded = true
            lastError = result.warning
            refreshLocked()
            // UI 可能先 conversation() 后 load()：把已有 flow 补上标题与记录。
            bots.forEach { publishConversationLocked(ConversationIds.bot(it.id)) }
            groups.forEach { publishConversationLocked(ConversationIds.group(it.id)) }
        }
        Unit
    }

    override suspend fun createBot(draft: BotDraft): Result<BotProfile> = guarded("创建机器人失败") {
        ensureLoaded()
        val name = draft.name.trim()
        if (name.isEmpty()) throw DshException("invalid-argument", "机器人名称不能为空")
        var profile = BotProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            emoji = draft.emoji.trim().ifBlank { "🤖" },
            colorIndex = draft.colorIndex.coerceIn(0, BotTemplates.PALETTE_SIZE - 1),
            persona = draft.persona.trim(),
            model = draft.model,
            cwd = draft.cwd?.trim()?.takeIf { it.isNotEmpty() },
            sessionId = null,
            isMain = draft.isMain,
            createdAtMillis = clock(),
            lastActiveAtMillis = 0L,
        )
        stateMutex.withLock {
            if (bots.any { it.name.equals(name, ignoreCase = true) }) {
                throw DshException("invalid-argument", "已存在同名机器人「$name」，@ 提及会混淆，请换一个名称")
            }
            if (profile.isMain) clearOtherMainsLocked(profile.id)
            bots += profile
            refreshLocked()
            schedulePersistLocked()
        }
        // 离线也允许建档：建会话失败时 sessionId=null，首次发送前自动补建。
        val provisioned = provisionSession(profile, sendInitPrompt = true)
        if (provisioned.isSuccess) {
            profile = provisioned.getOrThrow()
        } else {
            val reason = provisioned.exceptionOrNull()?.chineseMessage().orEmpty()
            stateMutex.withLock {
                lastError = buildString {
                    append("已离线创建「")
                    append(name)
                    append("」，联网后首次发送会自动补建会话。")
                    if (reason.isNotBlank()) {
                        append("（")
                        append(reason)
                        append("）")
                    }
                }
                refreshLocked()
                schedulePersistLocked()
            }
        }
        profile
    }

    override suspend fun updateBot(profile: BotProfile): Result<BotProfile> = guarded("更新机器人失败") {
        ensureLoaded()
        val existing = teamState.value.bot(profile.id)
            ?: throw DshException("not-found", "找不到要更新的机器人")
        val name = profile.name.trim()
        if (name.isEmpty()) throw DshException("invalid-argument", "机器人名称不能为空")
        if (bots.any { it.id != profile.id && it.name.equals(name, ignoreCase = true) }) {
            throw DshException("invalid-argument", "已存在同名机器人「$name」，@ 提及会混淆，请换一个名称")
        }
        val updated = profile.copy(
            name = name,
            emoji = profile.emoji.trim().ifBlank { "🤖" },
            colorIndex = profile.colorIndex.coerceIn(0, BotTemplates.PALETTE_SIZE - 1),
            persona = profile.persona.trim(),
            cwd = profile.cwd?.trim()?.takeIf { it.isNotEmpty() },
            // 会话绑定不允许被 UI 覆盖成空：离线阶段还没建会话时才保留 null。
            sessionId = profile.sessionId ?: existing.sessionId,
            createdAtMillis = existing.createdAtMillis,
            lastActiveAtMillis = existing.lastActiveAtMillis,
        )
        stateMutex.withLock {
            if (updated.isMain) clearOtherMainsLocked(updated.id)
            replaceBotLocked(updated)
            refreshLocked()
            publishConversationLocked(ConversationIds.bot(updated.id))
            schedulePersistLocked()
        }
        // 主机侧同步：失败不阻塞本地编辑（离线也应该能改名字/模型）。
        updated.sessionId?.let { sessionId ->
            if (updated.name != existing.name || updated.emoji != existing.emoji) {
                bestEffort { gateway.renameSession(sessionId, sessionTitle(updated)) }
            }
            if (updated.model != existing.model && updated.model != null) {
                bestEffort { gateway.selectModel(sessionId, updated.model!!) }
            }
        }
        updated
    }

    override suspend fun deleteBot(botId: String): Result<Unit> = guarded("删除机器人失败") {
        ensureLoaded()
        stateMutex.withLock {
            val removed = bots.removeAll { it.id == botId }
            if (!removed) throw DshException("not-found", "找不到要删除的机器人")
            groups.replaceAll { group ->
                if (botId in group.memberIds) {
                    group.copy(
                        memberIds = group.memberIds - botId,
                        adminBotId = group.adminBotId.takeIf { it != botId },
                    )
                } else {
                    group
                }
            }
            val conversationId = ConversationIds.bot(botId)
            transcripts.remove(conversationId)
            messageSnapshots.remove(conversationId)
            working.remove(conversationId)
            conversations.remove(conversationId)
            activeJobs.remove(conversationId)?.forEach { it.cancel() }
            refreshLocked()
            schedulePersistLocked()
        }
        store.flush()
        Unit
    }

    // -----------------------------------------------------------------------
    // BotTeam：群
    // -----------------------------------------------------------------------

    override suspend fun createGroup(draft: GroupDraft): Result<BotGroup> = guarded("创建群聊失败") {
        ensureLoaded()
        val name = draft.name.trim()
        if (name.isEmpty()) throw DshException("invalid-argument", "群聊名称不能为空")
        val memberIds = draft.memberIds.filter { it.isNotBlank() }.distinct()
        if (memberIds.isEmpty()) throw DshException("invalid-argument", "群聊至少需要一个成员")
        val missing = memberIds.filter { teamState.value.bot(it) == null }
        if (missing.isNotEmpty()) throw DshException("invalid-argument", "群成员不存在：${missing.joinToString("、")}")
        val admin = draft.adminBotId?.takeIf { it.isNotBlank() }
        if (admin != null && admin !in memberIds) throw DshException("invalid-argument", "管理员必须是群成员")
        val group = BotGroup(
            id = UUID.randomUUID().toString(),
            name = name,
            emoji = draft.emoji.trim().ifBlank { "👥" },
            memberIds = memberIds,
            adminBotId = admin,
            createdAtMillis = clock(),
        )
        stateMutex.withLock {
            groups += group
            refreshLocked()
            schedulePersistLocked()
        }
        group
    }

    override suspend fun updateGroup(group: BotGroup): Result<BotGroup> = guarded("更新群聊失败") {
        ensureLoaded()
        val name = group.name.trim()
        if (name.isEmpty()) throw DshException("invalid-argument", "群聊名称不能为空")
        val existing = teamState.value.group(group.id)
            ?: throw DshException("not-found", "找不到要更新的群聊")
        val memberIds = group.memberIds.filter { it.isNotBlank() }.distinct()
        if (memberIds.isEmpty()) throw DshException("invalid-argument", "群聊至少需要一个成员")
        val missing = memberIds.filter { teamState.value.bot(it) == null }
        if (missing.isNotEmpty()) throw DshException("invalid-argument", "群成员不存在：${missing.joinToString("、")}")
        val admin = group.adminBotId?.takeIf { it.isNotBlank() }
        if (admin != null && admin !in memberIds) throw DshException("invalid-argument", "管理员必须是群成员")
        val updated = group.copy(
            name = name,
            emoji = group.emoji.trim().ifBlank { "👥" },
            memberIds = memberIds,
            adminBotId = admin,
            createdAtMillis = existing.createdAtMillis,
        )
        stateMutex.withLock {
            replaceGroupLocked(updated)
            refreshLocked()
            publishConversationLocked(ConversationIds.group(updated.id))
            schedulePersistLocked()
        }
        updated
    }

    override suspend fun deleteGroup(groupId: String): Result<Unit> = guarded("删除群聊失败") {
        ensureLoaded()
        stateMutex.withLock {
            val removed = groups.removeAll { it.id == groupId }
            if (!removed) throw DshException("not-found", "找不到要删除的群聊")
            val conversationId = ConversationIds.group(groupId)
            transcripts.remove(conversationId)
            messageSnapshots.remove(conversationId)
            working.remove(conversationId)
            conversations.remove(conversationId)
            activeJobs.remove(conversationId)?.forEach { it.cancel() }
            refreshLocked()
            schedulePersistLocked()
        }
        store.flush()
        Unit
    }

    // -----------------------------------------------------------------------
    // BotTeam：会话
    // -----------------------------------------------------------------------

    override fun conversation(conversationId: String): StateFlow<ConversationState> =
        conversations.computeIfAbsent(conversationId) { MutableStateFlow(buildConversationState(conversationId)) }

    override suspend fun openConversation(conversationId: String): Result<Unit> = guarded("打开会话失败") {
        ensureLoaded()
        stateMutex.withLock {
            val kind = conversationKind(conversationId)
                ?: throw DshException("invalid-argument", "无法识别的会话：$conversationId")
            val targetId = ConversationIds.targetId(conversationId)
            when (kind) {
                ConversationKind.BOT -> if (teamState.value.bot(targetId) == null) {
                    throw DshException("not-found", "找不到该机器人")
                }
                ConversationKind.GROUP -> if (teamState.value.group(targetId) == null) {
                    throw DshException("not-found", "找不到该群聊")
                }
            }
            val flow = conversations.computeIfAbsent(conversationId) {
                MutableStateFlow(buildConversationState(conversationId))
            }
            // 本地记录来自 store.load() 的缓存；boundSessionIds 让 UI 只展示
            // 属于本会话成员的审批（审批数据本身由网关透传，不在此过滤/改写）。
            flow.value = buildConversationState(conversationId).copy(loading = false)
        }
        Unit
    }

    override fun closeConversation(conversationId: String) {
        // 不取消正在进行的回复（离开页面不等于停止生成），只把 loading 复位。
        conversations[conversationId]?.update { it.copy(workingBotIds = working[conversationId].orEmpty(), loading = false) }
    }

    // -----------------------------------------------------------------------
    // BotTeam：发送 / 停止 / 清空
    // -----------------------------------------------------------------------

    override suspend fun send(conversationId: String, text: String): Result<Unit> {
        return try {
            ensureLoaded()
            if (closed.get()) throw DshException("closed", "团队已关闭，无法发送消息")
            val content = text.trim()
            if (content.isEmpty()) throw DshException("invalid-argument", "消息内容不能为空")
            val kind = conversationKind(conversationId)
                ?: throw DshException("invalid-argument", "无法识别的会话：$conversationId")
            val targetId = ConversationIds.targetId(conversationId)
            val targetBot = if (kind == ConversationKind.BOT) {
                teamState.value.bot(targetId) ?: throw DshException("not-found", "找不到该机器人")
            } else {
                null
            }
            val targetGroup = if (kind == ConversationKind.GROUP) {
                teamState.value.group(targetId) ?: throw DshException("not-found", "找不到该群聊")
            } else {
                null
            }
            if (!sending.add(conversationId)) {
                throw DshException("busy", "该会话正在回复中，请稍候再发")
            }
            val result = CompletableDeferred<Result<Unit>>()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var outcome: Result<Unit>
                try {
                    outcome = when (kind) {
                        ConversationKind.BOT -> runBotConversationTurn(conversationId, targetBot!!, content)
                        ConversationKind.GROUP -> runGroupConversationTurn(conversationId, targetGroup!!, content)
                    }
                } catch (ce: CancellationException) {
                    // stop() 取消了本次编排：把残留的流式消息标成「已中断」，不向上抛。
                    finalizeCancelledTurn(conversationId)
                    outcome = Result.success(Unit)
                } catch (t: Throwable) {
                    outcome = Result.failure(t.toDsh("发送消息失败"))
                } finally {
                    val selfJob = coroutineContext[Job]
                    activeJobs[conversationId]?.let { jobs ->
                        if (selfJob != null) jobs.remove(selfJob)
                        if (jobs.isEmpty()) activeJobs.remove(conversationId, jobs)
                    }
                    sending.remove(conversationId)
                }
                result.complete(outcome)
            }
            val jobs = activeJobs.computeIfAbsent(conversationId) { ConcurrentHashMap.newKeySet() }
            jobs.add(job)
            job.start()
            if (job.isCancelled) {
                jobs.remove(job)
                sending.remove(conversationId)
                throw DshException("closed", "团队已关闭，无法发送消息")
            }
            result.await()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t.toDsh("发送消息失败"))
        }
    }

    override suspend fun stop(conversationId: String): Result<Unit> = guarded("停止回复失败") {
        ensureLoaded()
        if (conversationKind(conversationId) == null) {
            throw DshException("invalid-argument", "无法识别的会话：$conversationId")
        }
        // 先取消本地收集任务，再让主机取消 turn（两边的取消都幂等）。
        val workingBefore = working[conversationId].orEmpty().toList()
        val roster = teamState.value.bots
        val jobs = activeJobs[conversationId]?.toList().orEmpty()
        jobs.forEach { it.cancel() }
        withContext(NonCancellable) {
            workingBefore.forEach { botId ->
                val sessionId = roster.firstOrNull { it.id == botId }?.sessionId
                if (sessionId != null) bestEffort { gateway.cancelTurn(sessionId) }
            }
            jobs.forEach { it.join() }
            finalizeCancelledTurn(conversationId)
        }
        Unit
    }

    override suspend fun clear(conversationId: String): Result<Unit> = guarded("清空记录失败") {
        ensureLoaded()
        if (conversationKind(conversationId) == null) {
            throw DshException("invalid-argument", "无法识别的会话：$conversationId")
        }
        stateMutex.withLock {
            // DSH 会话本身的历史保留在电脑上；这里只清手机端本地记录。
            transcripts.remove(conversationId)
            messageSnapshots[conversationId] = emptyList()
            publishConversationLocked(conversationId)
            refreshLocked()
            schedulePersistLocked()
        }
        store.flush()
        Unit
    }

    override suspend fun setBotModel(botId: String, selection: ModelSelection?): Result<Unit> = guarded("设置模型失败") {
        ensureLoaded()
        val bot = teamState.value.bot(botId) ?: throw DshException("not-found", "找不到该机器人")
        stateMutex.withLock {
            replaceBotLocked(bot.copy(model = selection))
            refreshLocked()
            schedulePersistLocked()
        }
        // null = 交回主机默认模型，不需要调用 selectModel。
        val sessionId = bot.sessionId
        if (selection != null && sessionId != null) {
            gateway.selectModel(sessionId, selection).onFailure { cause ->
                throw DshException("model-select-failed", "电脑端切换模型失败：${cause.chineseMessage()}", cause = cause)
            }
        }
        Unit
    }

    override suspend fun respondApproval(
        approval: ApprovalRequest,
        decision: ApprovalDecision,
    ): Result<Unit> = guarded("提交审批结果失败") {
        // SECURITY（docs/SECURITY.md §1）：引擎只做透传——同一个 eventId、同一份
        // arguments/fingerprint、同一个 decision，原样交给网关。绝不自动批准、
        // 不缓存、不重放、不改写请求。审批是否展示完整由 UI 负责。
        gateway.respondApproval(approval, decision).onFailure { cause ->
            throw DshException("approval-forward-failed", "提交审批结果失败：${cause.chineseMessage()}", cause = cause)
        }
        Unit
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 先把待写数据同步落盘，再取消后台任务（顺序保证不丢最近一次快照）。
        runCatching { store.flushNow() }
        activeJobs.values.forEach { jobs -> jobs.toList().forEach { it.cancel() } }
        activeJobs.clear()
        sending.clear()
        working.clear()
        conversations.values.forEach { flow ->
            flow.update { it.copy(workingBotIds = emptySet(), loading = false) }
        }
        if (ownsScope) scope.cancel()
    }

    // -----------------------------------------------------------------------
    // 1:1 回合
    // -----------------------------------------------------------------------

    private suspend fun runBotConversationTurn(
        conversationId: String,
        bot: BotProfile,
        content: String,
    ): Result<Unit> {
        appendMessage(conversationId, userMessage(conversationId, content))
        return runBotTurn(conversationId, bot, content, fromMention = false)
    }

    /**
     * 单个机器人跑一轮：落一条 STREAMING 的 BOT 消息，折叠 follow 流，最后置
     * DONE / ERROR / INTERRUPTED。调用方负责决定失败是否阻断（群聊不阻断）。
     */
    private suspend fun runBotTurn(
        conversationId: String,
        initialBot: BotProfile,
        promptBody: String,
        fromMention: Boolean,
    ): Result<Unit> {
        setWorking(conversationId, initialBot.id, true)
        val messageId = UUID.randomUUID().toString()
        try {
            appendMessage(
                conversationId,
                TeamMessage(
                    id = messageId,
                    conversationId = conversationId,
                    authorKind = AuthorKind.BOT,
                    authorBotId = initialBot.id,
                    authorName = initialBot.name,
                    authorEmoji = initialBot.emoji,
                    authorColorIndex = initialBot.colorIndex,
                    text = "",
                    status = MessageStatus.STREAMING,
                    timeMillis = clock(),
                    fromMention = fromMention,
                ),
            )
            var bot = initialBot
            var includeInitialization = false
            if (bot.sessionId == null) {
                // 离线建档的机器人在首次发送前补建会话；初始化提示词与用户消息合并成
                // 同一轮，避免主机上多出一个与用户无关的 init turn。
                val provisioned = provisionSession(bot, sendInitPrompt = false)
                if (provisioned.isFailure) {
                    val cause = provisioned.exceptionOrNull()
                    return failTurn(
                        conversationId,
                        messageId,
                        bot.id,
                        "无法创建会话：${cause?.chineseMessage() ?: "未知错误"}",
                        cause,
                    )
                }
                bot = provisioned.getOrThrow()
                includeInitialization = true
            }
            val result = executePromptTurn(
                conversationId = conversationId,
                botIn = bot,
                messageId = messageId,
                userBody = promptBody,
                firstPromptIncludesInitialization = includeInitialization,
            )
            setWorking(conversationId, bot.id, false)
            if (result.isSuccess) touchBot(bot.id)
            return result
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                updateMessage(conversationId, messageId, persist = true) { message ->
                    message.copy(
                        status = MessageStatus.INTERRUPTED,
                        text = message.text.ifBlank { "已停止生成" },
                        error = null,
                    )
                }
                setWorking(conversationId, initialBot.id, false)
            }
            throw ce
        } catch (t: Throwable) {
            return failTurn(
                conversationId,
                messageId,
                initialBot.id,
                "机器人回复失败：${t.chineseMessage()}",
                t,
            )
        }
    }

    /**
     * 发送 prompt 并跟随一轮输出；遇到 `session/not-found` 时重建会话并重试一次。
     */
    private suspend fun executePromptTurn(
        conversationId: String,
        botIn: BotProfile,
        messageId: String,
        userBody: String,
        firstPromptIncludesInitialization: Boolean,
    ): Result<Unit> {
        var bot = botIn
        var includeInitialization = firstPromptIncludesInitialization
        var attempt = 0
        while (attempt <= MAX_SESSION_RETRIES) {
            attempt++
            val sessionId = bot.sessionId
                ?: return failTurn(conversationId, messageId, bot.id, "会话尚未建立，请先连接电脑。", null)
            val outgoing = buildPromptText(bot, userBody, includeInitialization)
            val prompted = gateway.prompt(sessionId, outgoing)
            if (prompted.isFailure) {
                val cause = prompted.exceptionOrNull()!!
                if (attempt == 1 && isSessionNotFound(cause)) {
                    val rebuilt = recreateSession(bot)
                    if (rebuilt.isFailure) {
                        return failTurn(
                            conversationId,
                            messageId,
                            bot.id,
                            "会话已失效，自动重建失败：${rebuilt.exceptionOrNull()?.chineseMessage() ?: "未知错误"}",
                            cause,
                        )
                    }
                    bot = rebuilt.getOrThrow()
                    includeInitialization = true
                    continue
                }
                return failTurn(
                    conversationId,
                    messageId,
                    bot.id,
                    "发送到电脑失败：${cause.chineseMessage()}",
                    cause,
                )
            }
            when (val outcome = followTurn(conversationId, messageId, sessionId, outgoing)) {
                is TurnOutcome.Completed -> {
                    updateMessage(conversationId, messageId, persist = true) { message ->
                        message.copy(status = outcome.status, error = outcome.error)
                    }
                    return if (outcome.status == MessageStatus.ERROR) {
                        Result.failure(DshException("turn-failed", outcome.error ?: "本轮生成失败"))
                    } else {
                        Result.success(Unit)
                    }
                }
                is TurnOutcome.NotFound -> {
                    if (attempt == 1) {
                        val rebuilt = recreateSession(bot)
                        if (rebuilt.isFailure) {
                            return failTurn(
                                conversationId,
                                messageId,
                                bot.id,
                                "会话已失效，自动重建失败：${rebuilt.exceptionOrNull()?.chineseMessage() ?: "未知错误"}",
                                outcome.cause,
                            )
                        }
                        bot = rebuilt.getOrThrow()
                        includeInitialization = true
                        continue
                    }
                    return failTurn(
                        conversationId,
                        messageId,
                        bot.id,
                        "会话已失效，重建后仍未恢复：${outcome.cause?.chineseMessage() ?: "session/not-found"}",
                        outcome.cause,
                    )
                }
                is TurnOutcome.Failed -> return failTurn(
                    conversationId,
                    messageId,
                    bot.id,
                    outcome.message,
                    outcome.cause,
                )
            }
        }
        return failTurn(conversationId, messageId, bot.id, "会话重试次数已用尽。", null)
    }

    /**
     * 跟随一个会话流，直到**本轮**结束；把翻页结果同步到流式消息。
     *
     * 为什么先等用户消息回显：`createBot` 会立刻发一条角色初始化提示词，主机上
     * 随即跑一轮与用户无关的 turn；用户若在那一轮没结束时发首条消息，follow 会先
     * 收到 init 轮的事件。这里用「等本轮 prompt 的 user/message 回显」来对齐 turn，
     * 回显之前的任何事件（包括完成事件）都属于旧 turn，必须忽略；回显也可能已经
     * 出现在 Snapshot.records 里（prompt 已开始甚至已跑完的竞态），两条路径都覆盖。
     */
    private suspend fun followTurn(
        conversationId: String,
        messageId: String,
        sessionId: String,
        expectedUserPrompt: String,
    ): TurnOutcome {
        val promptNeedle = expectedUserPrompt.trim()
        var fold = SessionFold(sessionId)
        var armed = false
        var aborted = false
        var streamEndedError: String? = null
        var failure: Throwable? = null
        try {
            gateway.follow(sessionId).collect { feed ->
                when (feed) {
                    is SessionFeed.Snapshot -> {
                        if (!armed) {
                            val startIndex = feed.records.indexOfFirst { event ->
                                isUserPromptEcho(event, promptNeedle)
                            }
                            if (startIndex >= 0) {
                                // 本轮在 snapshot 里已经开始了：从回显那条记录起重放。
                                armed = true
                                fold = SessionFold(sessionId)
                                for (index in startIndex until feed.records.size) {
                                    val event = feed.records[index]
                                    if (event.type == "turn/end" && reasonKindOf(event) == "aborted") aborted = true
                                    val state = fold.apply(SessionFeed.Event(event))
                                    publishFoldState(conversationId, messageId, state)
                                    if (state.completed || state.error != null) throw FoldFinished()
                                }
                            }
                        }
                    }
                    is SessionFeed.Event -> {
                        if (!armed && isUserPromptEcho(feed.event, promptNeedle)) {
                            armed = true
                            fold = SessionFold(sessionId)
                        }
                        if (armed) {
                            if (feed.event.type == "turn/end" && reasonKindOf(feed.event) == "aborted") {
                                aborted = true
                            }
                            val state = fold.apply(feed)
                            publishFoldState(conversationId, messageId, state)
                            if (state.completed || state.error != null) throw FoldFinished()
                        }
                    }
                    is SessionFeed.Stream -> {
                        if (armed) {
                            val frame = feed.frame
                            if (frame is StreamFrame.End && frame.outcomeKind in ABORTED_OUTCOME_KINDS) {
                                aborted = true
                            }
                            val state = fold.apply(feed)
                            publishFoldState(conversationId, messageId, state)
                            if (state.completed || state.error != null) throw FoldFinished()
                        }
                    }
                    is SessionFeed.Ended -> {
                        if (feed.error != null) streamEndedError = feed.error
                        if (armed) {
                            val state = fold.apply(feed)
                            publishFoldState(conversationId, messageId, state)
                            if (state.completed || state.error != null) throw FoldFinished()
                        }
                    }
                }
            }
        } catch (_: FoldFinished) {
            // 正常收束：主动关闭 follow 流（取消订阅）。
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            failure = t
        }
        val foldState = fold.state()
        if (streamEndedError != null && isSessionNotFoundText(streamEndedError!!)) {
            return TurnOutcome.NotFound(null)
        }
        failure?.let { cause ->
            if (isSessionNotFound(cause)) return TurnOutcome.NotFound(cause)
            return TurnOutcome.Failed("接收会话流失败：${cause.chineseMessage()}", cause)
        }
        if (!armed) {
            // 没等到本轮回显：流被关掉（没有错误）说明主机没接这轮 prompt。
            return if (streamEndedError != null) {
                TurnOutcome.Completed(MessageStatus.ERROR, streamEndedError)
            } else {
                TurnOutcome.Failed("会话流在本轮开始前关闭，请重试。", null)
            }
        }
        return when {
            aborted -> TurnOutcome.Completed(MessageStatus.INTERRUPTED)
            foldState.error != null -> TurnOutcome.Completed(MessageStatus.ERROR, foldState.error)
            else -> TurnOutcome.Completed(MessageStatus.DONE)
        }
    }

    private suspend fun publishFoldState(
        conversationId: String,
        messageId: String,
        foldState: SessionFold.FoldState,
    ) {
        updateMessage(conversationId, messageId) { message ->
            message.copy(
                text = foldState.text,
                reasoning = foldState.reasoning.ifBlank { null },
                tools = foldState.tools.map { ToolTrace(it.callId, it.name, it.status, it.summary) },
                status = when {
                    !foldState.completed -> MessageStatus.STREAMING
                    foldState.error != null -> MessageStatus.ERROR
                    else -> MessageStatus.DONE
                },
                error = foldState.error,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 群聊回合
    // -----------------------------------------------------------------------

    private data class RelayTask(
        val bot: BotProfile,
        val promptText: String,
        val fromMention: Boolean,
        val hop: Int,
    )

    private suspend fun runGroupConversationTurn(
        conversationId: String,
        group: BotGroup,
        content: String,
    ): Result<Unit> {
        appendMessage(conversationId, userMessage(conversationId, content))
        val roster = teamState.value
        val members = group.memberIds.mapNotNull { roster.bot(it) }
        val missingMemberIds = group.memberIds.filter { roster.bot(it) == null }
        if (missingMemberIds.isNotEmpty()) {
            appendMessage(
                conversationId,
                systemMessage(
                    conversationId,
                    "群聊「${group.name}」有成员已不在团队（${missingMemberIds.joinToString("、")}），本轮已跳过。",
                ),
            )
        }
        if (members.isEmpty()) {
            appendMessage(
                conversationId,
                systemMessage(conversationId, "群聊「${group.name}」还没有可用成员，请先在群设置里添加机器人。"),
            )
            return Result.success(Unit)
        }
        val adminInGroup = group.adminBotId?.takeIf { adminBotId -> members.any { it.id == adminBotId } }
        if (group.adminBotId != null && adminInGroup == null) {
            appendMessage(
                conversationId,
                systemMessage(conversationId, "群聊「${group.name}」的管理员已不在群里，本轮改为全员回复。"),
            )
        }
        val memberNames = members.map { it.name }
        val explicitMentions = MentionParser.mentionsIn(content, members)
        val initialTargets = when {
            explicitMentions.isNotEmpty() -> explicitMentions
            adminInGroup != null -> members.filter { it.id == adminInGroup }.ifEmpty { members }
            else -> members
        }
        val queue = ArrayDeque<RelayTask>()
        initialTargets.forEach { queue.addLast(RelayTask(it, content, fromMention = false, hop = 0)) }
        val replied = mutableSetOf<String>()
        var botMessages = 0
        while (queue.isNotEmpty() && botMessages < MAX_BOT_MESSAGES_PER_GROUP_TURN) {
            val task = queue.removeFirst()
            if (!replied.add(task.bot.id)) continue
            // 使用最新的 profile（同一次发送里前面的机器人可能刚补建过会话）。
            val current = teamState.value.bot(task.bot.id) ?: task.bot
            val prefix = groupPromptPrefix(group.name, task.bot.name, memberNames)
            val promptBody = prefix + "\n" + task.promptText
            val turnResult = runBotTurn(conversationId, current, promptBody, task.fromMention)
            botMessages++
            if (turnResult.isFailure) {
                // 群聊里单人失败不阻断：落一条 SYSTEM 提示后继续下一位。
                val reason = turnResult.exceptionOrNull()?.chineseMessage().orEmpty()
                appendMessage(
                    conversationId,
                    systemMessage(conversationId, "「${task.bot.name}」本轮未完成：${reason.ifBlank { "未知错误" }}"),
                )
                continue
            }
            if (task.hop >= MAX_MENTION_HOPS) continue
            val replyText = lastBotReplyText(conversationId, task.bot.id) ?: continue
            MentionParser.mentionsIn(replyText, members)
                .filter { it.id != task.bot.id && it.id !in replied }
                .forEach { target ->
                    queue.addLast(
                        RelayTask(
                            bot = target,
                            promptText = relayPrompt(task.bot, replyText),
                            fromMention = true,
                            hop = task.hop + 1,
                        ),
                    )
                }
        }
        return Result.success(Unit)
    }

    // -----------------------------------------------------------------------
    // 消息 / 状态维护
    // -----------------------------------------------------------------------

    private suspend fun appendMessage(
        conversationId: String,
        message: TeamMessage,
        persist: Boolean = true,
    ) {
        stateMutex.withLock {
            val list = transcripts.getOrPut(conversationId) { mutableListOf() }
            list += message
            trimLocked(list)
            syncSnapshotLocked(conversationId)
            publishConversationLocked(conversationId)
            if (persist) {
                refreshLocked()
                schedulePersistLocked()
            }
        }
    }

    private suspend fun updateMessage(
        conversationId: String,
        messageId: String,
        persist: Boolean = false,
        transform: (TeamMessage) -> TeamMessage,
    ) {
        stateMutex.withLock {
            val list = transcripts[conversationId] ?: return@withLock
            val index = list.indexOfFirst { it.id == messageId }
            if (index < 0) return@withLock
            list[index] = transform(list[index])
            syncSnapshotLocked(conversationId)
            publishConversationLocked(conversationId)
            if (persist) {
                refreshLocked()
                schedulePersistLocked()
            }
        }
    }

    private suspend fun setWorking(conversationId: String, botId: String, workingNow: Boolean) {
        if (workingNow) {
            working.compute(conversationId) { _, current -> (current ?: emptySet()) + botId }
        } else {
            working.compute(conversationId) { _, current -> (current ?: emptySet()) - botId }
        }
        stateMutex.withLock {
            publishConversationLocked(conversationId)
            refreshLocked()
        }
    }

    private suspend fun touchBot(botId: String) {
        stateMutex.withLock {
            val index = bots.indexOfFirst { it.id == botId }
            if (index >= 0) {
                bots[index] = bots[index].copy(lastActiveAtMillis = clock())
                refreshLocked()
                schedulePersistLocked()
            }
        }
    }

    /** 把当前会话里仍处于 STREAMING 的消息收尾成「已中断」并清理 working。 */
    private suspend fun finalizeCancelledTurn(conversationId: String) {
        withContext(NonCancellable) {
            stateMutex.withLock {
                var changed = false
                val list = transcripts[conversationId]
                if (list != null) {
                    for (index in list.indices) {
                        val message = list[index]
                        if (message.status == MessageStatus.STREAMING || message.status == MessageStatus.SENDING) {
                            list[index] = message.copy(
                                status = MessageStatus.INTERRUPTED,
                                text = message.text.ifBlank { "已停止生成" },
                                error = null,
                            )
                            changed = true
                        }
                    }
                }
                if (working.remove(conversationId) != null) changed = true
                if (changed) {
                    syncSnapshotLocked(conversationId)
                    publishConversationLocked(conversationId)
                    refreshLocked()
                    schedulePersistLocked()
                }
            }
        }
    }

    private suspend fun failTurn(
        conversationId: String,
        messageId: String,
        botId: String,
        message: String,
        cause: Throwable?,
    ): Result<Unit> {
        withContext(NonCancellable) {
            updateMessage(conversationId, messageId, persist = true) { current ->
                current.copy(status = MessageStatus.ERROR, error = message)
            }
            setWorking(conversationId, botId, false)
        }
        return Result.failure(DshException("bot-turn-failed", message, cause = cause))
    }

    private fun userMessage(conversationId: String, text: String): TeamMessage = TeamMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        authorKind = AuthorKind.USER,
        text = text,
        status = MessageStatus.DONE,
        timeMillis = clock(),
    )

    private fun systemMessage(conversationId: String, text: String): TeamMessage = TeamMessage(
        id = UUID.randomUUID().toString(),
        conversationId = conversationId,
        authorKind = AuthorKind.SYSTEM,
        text = text,
        status = MessageStatus.DONE,
        timeMillis = clock(),
    )

    private suspend fun lastBotReplyText(conversationId: String, botId: String): String? = stateMutex.withLock {
        transcripts[conversationId]
            ?.asReversed()
            ?.firstOrNull { message ->
                message.authorKind == AuthorKind.BOT &&
                    message.authorBotId == botId &&
                    message.status == MessageStatus.DONE &&
                    message.text.isNotBlank()
            }
            ?.text
    }

    // -----------------------------------------------------------------------
    // 会话供应（建会话 / 角色初始化 / 失效重建）
    // -----------------------------------------------------------------------

    /**
     * 建一个 DSH 会话并绑定到 [bot]。`sendInitPrompt=true` 时立刻发角色初始化
     * 提示词（createBot 用）；离线建档后的首次发送用 false，由调用方把初始化
     * 提示词并入用户消息，避免多出一轮无关 turn。
     */
    private suspend fun provisionSession(bot: BotProfile, sendInitPrompt: Boolean): Result<BotProfile> {
        val created = gateway.createSession(bot.cwd)
        val sessionId = created.getOrElse { cause ->
            return Result.failure(cause.toDsh("无法创建会话"))
        }
        // 重命名 / 选模型是尽力而为：失败不影响会话可用，不阻塞建档。
        bestEffort { gateway.renameSession(sessionId, sessionTitle(bot)) }
        bot.model?.let { selection -> bestEffort { gateway.selectModel(sessionId, selection) } }
        if (sendInitPrompt) {
            val initialized = gateway.prompt(sessionId, initializationPrompt(bot))
            if (initialized.isFailure) {
                return Result.failure(initialized.exceptionOrNull()!!.toDsh("角色初始化失败"))
            }
        }
        val updated = bot.copy(sessionId = sessionId)
        stateMutex.withLock {
            replaceBotLocked(updated)
            refreshLocked()
            schedulePersistLocked()
        }
        return Result.success(updated)
    }

    /** 会话被主机删除（session/not-found）时重建一次并换绑。 */
    private suspend fun recreateSession(bot: BotProfile): Result<BotProfile> {
        val created = gateway.createSession(bot.cwd)
        val sessionId = created.getOrElse { cause ->
            return Result.failure(cause.toDsh("重建会话失败"))
        }
        bestEffort { gateway.renameSession(sessionId, sessionTitle(bot)) }
        bot.model?.let { selection -> bestEffort { gateway.selectModel(sessionId, selection) } }
        val updated = bot.copy(sessionId = sessionId)
        stateMutex.withLock {
            replaceBotLocked(updated)
            refreshLocked()
            schedulePersistLocked()
        }
        return Result.success(updated)
    }

    // -----------------------------------------------------------------------
    // 状态快照 / 持久化
    // -----------------------------------------------------------------------

    private fun refreshLocked() {
        val summaries = ArrayList<ConversationSummary>(bots.size + groups.size)
        bots.forEach { bot ->
            val conversationId = ConversationIds.bot(bot.id)
            summaries += summaryLocked(
                conversationId = conversationId,
                kind = ConversationKind.BOT,
                title = bot.name,
                emoji = bot.emoji,
                memberIds = listOf(bot.id),
            )
        }
        groups.forEach { group ->
            summaries += summaryLocked(
                conversationId = ConversationIds.group(group.id),
                kind = ConversationKind.GROUP,
                title = group.name,
                emoji = group.emoji,
                memberIds = group.memberIds,
            )
        }
        teamState.value = TeamState(
            loaded = loaded,
            bots = bots.toList(),
            groups = groups.toList(),
            summaries = summaries,
            lastError = lastError,
        )
    }

    private fun summaryLocked(
        conversationId: String,
        kind: ConversationKind,
        title: String,
        emoji: String,
        memberIds: List<String>,
    ): ConversationSummary {
        val messages = messageSnapshots[conversationId].orEmpty()
        val last = messages.lastOrNull()
        val lastText = messages.lastOrNull { it.text.isNotBlank() }?.text?.trim()?.take(SUMMARY_TEXT_LIMIT)
            ?: last?.text
        return ConversationSummary(
            conversationId = conversationId,
            kind = kind,
            title = title,
            emoji = emoji,
            lastText = lastText,
            lastTimeMillis = last?.timeMillis ?: 0L,
            workingBotIds = working[conversationId].orEmpty(),
            memberIds = memberIds,
        )
    }

    private fun buildConversationState(conversationId: String): ConversationState {
        val roster = teamState.value
        val kind = conversationKind(conversationId) ?: ConversationKind.BOT
        val targetId = ConversationIds.targetId(conversationId)
        val messages = messageSnapshots[conversationId].orEmpty()
        val workingBotIds = working[conversationId].orEmpty()
        return when (kind) {
            ConversationKind.BOT -> {
                val bot = roster.bot(targetId)
                ConversationState(
                    conversationId = conversationId,
                    kind = ConversationKind.BOT,
                    title = bot?.name ?: "机器人",
                    emoji = bot?.emoji ?: "🤖",
                    messages = messages,
                    workingBotIds = workingBotIds,
                    boundSessionIds = listOfNotNull(bot?.sessionId),
                )
            }
            ConversationKind.GROUP -> {
                val group = roster.group(targetId)
                ConversationState(
                    conversationId = conversationId,
                    kind = ConversationKind.GROUP,
                    title = group?.name ?: "群聊",
                    emoji = group?.emoji ?: "👥",
                    messages = messages,
                    workingBotIds = workingBotIds,
                    boundSessionIds = group?.memberIds?.mapNotNull { botId -> roster.bot(botId)?.sessionId }
                        ?: emptyList(),
                )
            }
        }
    }

    private fun publishConversationLocked(conversationId: String) {
        conversations[conversationId]?.value = buildConversationState(conversationId)
    }

    private fun syncSnapshotLocked(conversationId: String) {
        messageSnapshots[conversationId] = transcripts[conversationId]?.toList() ?: emptyList()
    }

    private fun trimLocked(messages: MutableList<TeamMessage>) {
        if (messages.size > MAX_MESSAGES_PER_CONVERSATION) {
            messages.subList(0, messages.size - MAX_MESSAGES_PER_CONVERSATION).clear()
        }
    }

    private fun schedulePersistLocked() {
        store.scheduleSave(
            BotStore.Snapshot(
                bots = bots.toList(),
                groups = groups.toList(),
                transcripts = transcripts.mapValues { (_, messages) -> messages.toList() },
            ),
        )
    }

    private fun replaceBotLocked(profile: BotProfile) {
        val index = bots.indexOfFirst { it.id == profile.id }
        if (index >= 0) bots[index] = profile else bots += profile
    }

    private fun replaceGroupLocked(group: BotGroup) {
        val index = groups.indexOfFirst { it.id == group.id }
        if (index >= 0) groups[index] = group else groups += group
    }

    private fun clearOtherMainsLocked(exceptBotId: String) {
        for (index in bots.indices) {
            if (bots[index].id != exceptBotId && bots[index].isMain) {
                bots[index] = bots[index].copy(isMain = false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------------

    private suspend fun <T> guarded(failureMessage: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t.toDsh(failureMessage))
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        load().exceptionOrNull()?.let { throw it }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // 主机侧同步失败不阻塞手机端操作。
        }
    }

    private fun buildPromptText(bot: BotProfile, userBody: String, includeInitialization: Boolean): String =
        if (includeInitialization) {
            initializationPrompt(bot) + "\n\n" + userBody
        } else {
            userBody
        }

    private fun relayPrompt(from: BotProfile, replyText: String): String =
        "「${from.name}」在群聊里 @了你，并说：\n" + replyText.trim().take(MAX_RELAY_TEXT_LENGTH)

    private fun reasonKindOf(event: SessionEvent): String? {
        val reason = event.data["reason"] as? JsonObject ?: return null
        return (reason["kind"] as? JsonPrimitive)?.contentOrNull
    }

    /** 判断一条事件是否是「本轮 prompt」的 user/message 回显（用于对齐 turn）。 */
    private fun isUserPromptEcho(event: SessionEvent, expectedPrompt: String): Boolean {
        if (event.type != "user/message" || expectedPrompt.isBlank()) return false
        val content = event.data["content"] as? JsonArray ?: return false
        val text = content.mapNotNull { block ->
            val objectBlock = block as? JsonObject ?: return@mapNotNull null
            val type = (objectBlock["type"] as? JsonPrimitive)?.contentOrNull
            if (type != "text") return@mapNotNull null
            (objectBlock["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("")
        return text.trim() == expectedPrompt.trim()
    }

    private fun isSessionNotFound(cause: Throwable): Boolean {
        val dsh = cause as? DshException
        return isSessionNotFoundText(dsh?.code.orEmpty()) || isSessionNotFoundText(dsh?.message.orEmpty()) ||
            isSessionNotFoundText(cause.message.orEmpty())
    }

    private fun isSessionNotFoundText(raw: String): Boolean {
        if (raw.isBlank()) return false
        val text = raw.lowercase()
        return text.contains("session/not-found") ||
            text.contains("session-not-found") ||
            text.contains("session_not_found") ||
            text.contains("session not found") ||
            (text.contains("not-found") && text.contains("session")) ||
            text.contains("会话不存在") ||
            text.contains("会话未找到") ||
            text.contains("会话已失效")
    }

    private fun Throwable.toDsh(fallback: String): DshException = when (this) {
        is DshException -> this
        else -> DshException("bot-team/internal", "$fallback：${chineseMessage()}", cause = this)
    }

    private fun conversationKind(conversationId: String): ConversationKind? = when {
        ConversationIds.isBot(conversationId) -> ConversationKind.BOT
        ConversationIds.isGroup(conversationId) -> ConversationKind.GROUP
        else -> null
    }

    private fun sessionTitle(bot: BotProfile): String = "${bot.emoji} ${bot.name}".trim()

    // -----------------------------------------------------------------------
    // 回合结果
    // -----------------------------------------------------------------------

    private sealed interface TurnOutcome {
        data class Completed(val status: MessageStatus, val error: String? = null) : TurnOutcome
        data class NotFound(val cause: Throwable? = null) : TurnOutcome
        data class Failed(val message: String, val cause: Throwable? = null) : TurnOutcome
    }

    private class FoldFinished : RuntimeException("follow fold finished")

    private companion object {
        const val MAX_SESSION_RETRIES = 1
        const val MAX_MENTION_HOPS = 2
        const val MAX_BOT_MESSAGES_PER_GROUP_TURN = 4
        const val MAX_RELAY_TEXT_LENGTH = 1500
        const val SUMMARY_TEXT_LIMIT = 80
        val ABORTED_OUTCOME_KINDS = setOf("aborted", "cancelled", "interrupted")
    }
}

// ---------------------------------------------------------------------------
// 提示词模板（包内可见，便于单测直接断言）
// ---------------------------------------------------------------------------

/** `[团队初始化]` 角色初始化提示词：创建 / 首次补建会话时注入。 */
internal fun initializationPrompt(bot: BotProfile): String {
    val persona = bot.persona.trim().ifBlank { "保持友善、专业，乐于提供帮助。" }
    return "[团队初始化] 你是「${bot.name}」。\n" +
        "角色设定：$persona\n" +
        "规则：始终以该角色对话；使用简体中文；先结论后细节。"
}

/** 群聊里对单个 Bot 隐藏的前缀：告诉它群名、成员和「你是谁」。 */
internal fun groupPromptPrefix(groupName: String, botName: String, memberNames: List<String>): String =
    "[群聊「$groupName」｜成员：${memberNames.joinToString("、")}｜你是 $botName]"
