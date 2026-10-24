package com.dsbot.mobile.bots

import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.ConnectionPhase
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.core.DshGateway
import com.dsbot.mobile.core.Endpoint
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.core.SessionEvent
import com.dsbot.mobile.core.SessionFeed
import com.dsbot.mobile.core.SessionSummary
import com.dsbot.mobile.core.StreamFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 自写 FakeDshGateway（owner: android-core 测试）。
 *
 * 能力：
 * - `createSession` 按调用次数自动返回 `session-1`、`session-2`…，也可用
 *   [createSessionHandler] 覆盖（模拟离线失败 / 固定 id）；
 * - [prompts] / [renames] / [modelSelections] / [cancelledTurns] 记录所有调用，
 *   供断言「初始化提示词」「群前缀」「setBotModel」等；
 * - [followHandler] 按 sessionId 返回可编排的 `SessionFeed` 帧序列
 *   （Snapshot / Stream / Event / Ended，见 [Feeds]）；
 * - 审批调用原样记录，用于验证「只透传」。
 */
internal class FakeDshGateway : DshGateway {

    override val connection = MutableStateFlow(ConnectionState(phase = ConnectionPhase.CONNECTED))
    override val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    override val hostHome = MutableStateFlow<String?>("C:\\Users\\tester")

    data class PromptCall(val sessionId: String, val text: String)
    data class RenameCall(val sessionId: String, val title: String)
    data class ModelCall(val sessionId: String, val selection: ModelSelection)

    val createdSessionCwds = mutableListOf<String?>()
    val prompts = mutableListOf<PromptCall>()
    val renames = mutableListOf<RenameCall>()
    val modelSelections = mutableListOf<ModelCall>()
    val cancelledTurns = mutableListOf<String>()
    val approvalResponses = mutableListOf<Pair<ApprovalRequest, ApprovalDecision>>()

    var createSessionHandler: ((String?) -> Result<String>)? = null
    var promptHandler: ((String, String) -> Result<Unit>)? = null
    var selectModelHandler: ((String, ModelSelection) -> Result<Unit>)? = null
    var followHandler: ((String) -> Flow<SessionFeed>)? = null

    /**
     * 真实 DSH 在 turn 开始时会记录一条 `user/message`（内容就是 prompt 原文），
     * 引擎靠它对齐「本轮」。默认把该回显放在 follow 流最前面；特殊测试可关掉。
     */
    var prependUserEcho: Boolean = true

    private var sessionCounter = 0

    /** 不含角色初始化提示词的 prompt 列表（群聊/单聊用户消息）。 */
    val userPrompts: List<PromptCall>
        get() = prompts.filterNot { it.text.startsWith("[团队初始化]") }

    override suspend fun connect(endpoint: Endpoint): Result<Unit> = Result.success(Unit)

    override fun disconnect() = Unit

    override suspend fun refreshSessions(): Result<Unit> = Result.success(Unit)

    override suspend fun modelCatalog(): Result<ModelCatalog> = Result.success(ModelCatalog())

    override suspend fun createSession(cwd: String?): Result<String> {
        createdSessionCwds += cwd
        createSessionHandler?.let { return it(cwd) }
        sessionCounter++
        return Result.success("session-$sessionCounter")
    }

    override suspend fun renameSession(sessionId: String, title: String): Result<Unit> {
        renames += RenameCall(sessionId, title)
        return Result.success(Unit)
    }

    override suspend fun selectModel(sessionId: String, selection: ModelSelection): Result<Unit> {
        modelSelections += ModelCall(sessionId, selection)
        return selectModelHandler?.invoke(sessionId, selection) ?: Result.success(Unit)
    }

    override suspend fun prompt(sessionId: String, text: String): Result<Unit> {
        prompts += PromptCall(sessionId, text)
        return promptHandler?.invoke(sessionId, text) ?: Result.success(Unit)
    }

    override suspend fun cancelTurn(sessionId: String): Result<Unit> {
        cancelledTurns += sessionId
        return Result.success(Unit)
    }

    override fun follow(sessionId: String): Flow<SessionFeed> {
        val base = followHandler?.invoke(sessionId) ?: Feeds.completedTurn("（fake 默认回复）")
        if (!prependUserEcho) return base
        // 模仿真实主机：先回显本轮 prompt 的 user/message，再发 assistant 流。
        val echo = prompts.lastOrNull { it.sessionId == sessionId }?.text ?: return base
        return flow {
            emit(SessionFeed.Event(Feeds.userMessageEvent(echo)))
            emitAll(base)
        }
    }

    override suspend fun respondApproval(
        approval: ApprovalRequest,
        decision: ApprovalDecision,
    ): Result<Unit> {
        approvalResponses += approval to decision
        return Result.success(Unit)
    }

    override suspend fun close() = Unit
}

/**
 * 构造真实 DSH 形状的 follow 帧序列（参考 work/probe/LIVE-SAMPLES.md）：
 * Snapshot → assistant-stream(Start/Delta/BlockEnd) → assistant/message → turn/end。
 */
internal object Feeds {

    fun completedTurn(text: String, reasoning: String? = null): Flow<SessionFeed> = flow {
        emit(snapshot())
        emit(SessionFeed.Stream(StreamFrame.Start(revision = 1, index = 0)))
        if (reasoning != null) {
            emit(
                SessionFeed.Stream(
                    StreamFrame.Delta(revision = 2, index = 1, chunkIndex = 0, kind = "reasoning", text = reasoning),
                ),
            )
        }
        emit(
            SessionFeed.Stream(
                StreamFrame.Delta(revision = 3, index = 2, chunkIndex = 0, kind = "text", text = "流式片段"),
            ),
        )
        // block-end 携带权威文本：折叠器应以它覆盖增量缓冲。
        emit(SessionFeed.Stream(StreamFrame.BlockEnd(revision = 4, index = 3, chunkIndex = 0, blockType = "text", text = text)))
        emit(SessionFeed.Event(assistantMessageEvent(text, reasoning)))
        emit(SessionFeed.Event(turnEndEvent("completed")))
        emit(SessionFeed.Stream(StreamFrame.End(revision = 5, index = 4, outcomeKind = "committed", eventType = "assistant/message", seq = 6)))
        emit(SessionFeed.Ended())
    }

    fun completedTurnWithTools(text: String, toolName: String = "pwsh", toolOutput: String = "命令输出"): Flow<SessionFeed> = flow {
        emit(snapshot())
        emit(SessionFeed.Stream(StreamFrame.Start(revision = 1, index = 0)))
        emit(SessionFeed.Event(toolCallEvent("call-1", toolName)))
        emit(
            SessionFeed.Stream(
                StreamFrame.ToolCallDelta(revision = 2, index = 1, chunkIndex = 0, id = "call-1", name = toolName, argumentsDelta = ""),
            ),
        )
        emit(SessionFeed.Event(toolResultEvent("call-1", toolOutput)))
        emit(SessionFeed.Event(assistantMessageEvent(text, null)))
        emit(SessionFeed.Event(turnEndEvent("completed")))
    }

    fun abortedTurn(partial: String = "说到一半"): Flow<SessionFeed> = flow {
        emit(snapshot())
        emit(SessionFeed.Stream(StreamFrame.Start(revision = 1, index = 0)))
        emit(SessionFeed.Stream(StreamFrame.Delta(revision = 2, index = 1, chunkIndex = 0, kind = "text", text = partial)))
        emit(SessionFeed.Event(turnEndEvent("aborted")))
    }

    fun erroredTurn(): Flow<SessionFeed> = flow {
        emit(snapshot())
        emit(SessionFeed.Stream(StreamFrame.Start(revision = 1, index = 0)))
        emit(SessionFeed.Stream(StreamFrame.Delta(revision = 2, index = 1, chunkIndex = 0, kind = "text", text = "回复到一半")))
        emit(SessionFeed.Ended(error = "主机连接中断"))
    }

    /** 主机会话已被删除：follow 直接以 error 关闭。 */
    fun notFound(): Flow<SessionFeed> = flow {
        emit(SessionFeed.Ended(error = "session/not-found: 会话不存在"))
    }

    /** 永远不结束的流：模拟正在生成，供 stop() 测试。 */
    fun hangingTurn(partial: String = "正在输入"): Flow<SessionFeed> = flow {
        emit(snapshot())
        emit(SessionFeed.Stream(StreamFrame.Start(revision = 1, index = 0)))
        emit(SessionFeed.Stream(StreamFrame.Delta(revision = 2, index = 1, chunkIndex = 0, kind = "text", text = partial)))
        awaitCancellation()
    }

    private fun snapshot() = SessionFeed.Snapshot(
        cursor = 1,
        assistantRevision = 0,
        running = true,
        records = emptyList(),
    )

    private fun assistantMessageEvent(text: String, reasoning: String?): SessionEvent {
        val content = buildJsonArray {
            if (!reasoning.isNullOrBlank()) {
                add(buildJsonObject { put("type", "reasoning"); put("text", reasoning) })
            }
            add(buildJsonObject { put("type", "text"); put("text", text) })
        }
        return SessionEvent(
            type = "assistant/message",
            seq = 5,
            timeMillis = 0,
            data = buildJsonObject {
                put(
                    "message",
                    buildJsonObject { put("content", content) },
                )
            },
        )
    }

    /** turn 开始时主机记录的 `user/message`（内容与 prompt 原文一致）。 */
    fun userMessageEvent(text: String): SessionEvent = SessionEvent(
        type = "user/message",
        seq = 1,
        timeMillis = 0,
        data = buildJsonObject {
            put("role", "user")
            put(
                "content",
                buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) },
            )
        },
    )

    /** 供 snapshot.records 场景直接构造 assistant/message。 */
    fun assistantMessage(text: String, reasoning: String? = null): SessionEvent =
        assistantMessageEvent(text, reasoning)

    /** 供 snapshot.records 场景直接构造 turn/end。 */
    fun turnEnd(kind: String): SessionEvent = turnEndEvent(kind)

    private fun turnEndEvent(kind: String): SessionEvent = SessionEvent(
        type = "turn/end",
        seq = 6,
        timeMillis = 0,
        data = buildJsonObject {
            put("reason", buildJsonObject { put("kind", kind) })
        },
    )

    private fun toolCallEvent(callId: String, name: String): SessionEvent = SessionEvent(
        type = "tool/call",
        seq = 2,
        timeMillis = 0,
        data = buildJsonObject {
            put("callId", callId)
            put("name", name)
        },
    )

    private fun toolResultEvent(callId: String, output: String): SessionEvent = SessionEvent(
        type = "tool/result",
        seq = 4,
        timeMillis = 0,
        data = buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put("toolCallId", callId)
                    put("isError", false)
                    put(
                        "content",
                        buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", output) }) },
                    )
                },
            )
        },
    )
}
