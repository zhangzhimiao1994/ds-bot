package com.dsbot.mobile.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The single seam between the phone and the DSH host bridge (owned by the Lead).
 *
 * `data/DshGatewayImpl.kt` implements it over HTTP + the `/api/remote.mux`
 * WebSocket; the bot engine and the UI consume only this interface, which keeps
 * them testable with a fake.
 */
interface DshGateway {

    val connection: StateFlow<ConnectionState>

    /** Latest `session/list` result (root sessions and sub-agent sessions). */
    val sessions: StateFlow<List<SessionSummary>>

    /** Pending approvals from the `$events` waterfall (security-critical). */
    val approvals: StateFlow<List<ApprovalRequest>>

    /** Host home directory from the `$events` ready frame. */
    val hostHome: StateFlow<String?>

    suspend fun connect(endpoint: Endpoint): Result<Unit>

    fun disconnect()

    suspend fun refreshSessions(): Result<Unit>

    suspend fun modelCatalog(): Result<ModelCatalog>

    suspend fun createSession(cwd: String? = null): Result<String>

    suspend fun renameSession(sessionId: String, title: String): Result<Unit>

    suspend fun selectModel(sessionId: String, selection: ModelSelection): Result<Unit>

    /** Queue a text prompt for the session's next turn. */
    suspend fun prompt(sessionId: String, text: String): Result<Unit>

    /** Cancel the running turn of a session. */
    suspend fun cancelTurn(sessionId: String): Result<Unit>

    /**
     * Follow one session: first a [SessionFeed.Snapshot], then live
     * [SessionFeed.Event] / [SessionFeed.Stream] frames, and [SessionFeed.Ended]
     * when the stream closes. Collecting starts a `session/follow` stream;
     * cancelling the collection closes it.
     */
    fun follow(sessionId: String): Flow<SessionFeed>

    /** Answer one approval exactly as displayed; see docs/SECURITY.md §1. */
    suspend fun respondApproval(approval: ApprovalRequest, decision: ApprovalDecision): Result<Unit>

    suspend fun close()
}

/** One frame of a followed session, already mapped out of the JSON envelope. */
sealed interface SessionFeed {
    /** Snapshot taken when the follow stream opened. */
    data class Snapshot(
        val cursor: Long,
        val assistantRevision: Long,
        val running: Boolean,
        val records: List<SessionEvent>,
    ) : SessionFeed

    /** One durable session event (user/message, assistant/message, tool/call, turn/end...). */
    data class Event(val event: SessionEvent) : SessionFeed

    /** One live assistant-stream frame (text / reasoning deltas and block boundaries). */
    data class Stream(val frame: StreamFrame) : SessionFeed

    /** Stream closed; [error] is set when it failed instead of ending normally. */
    data class Ended(val error: String? = null) : SessionFeed
}

/** A durable DSH session event; [data] keeps the original JSON payload. */
data class SessionEvent(
    val type: String,
    val seq: Long,
    val timeMillis: Long,
    val data: JsonObject,
)

/** A live assistant-stream frame from the followed session. */
sealed interface StreamFrame {
    val revision: Long
    val index: Long

    data class Start(override val revision: Long, override val index: Long) : StreamFrame

    /** [kind] is `text` or `reasoning`; [text] is the delta to append. */
    data class Delta(
        override val revision: Long,
        override val index: Long,
        val chunkIndex: Int,
        val kind: String,
        val text: String,
    ) : StreamFrame

    data class BlockStart(
        override val revision: Long,
        override val index: Long,
        val chunkIndex: Int,
        val blockType: String,
    ) : StreamFrame

    /** Authoritative full text of a finished block ([blockType] = text/reasoning/tool-call). */
    data class BlockEnd(
        override val revision: Long,
        override val index: Long,
        val chunkIndex: Int,
        val blockType: String,
        val text: String,
    ) : StreamFrame

    data class ToolCallDelta(
        override val revision: Long,
        override val index: Long,
        val chunkIndex: Int,
        val id: String,
        val name: String,
        val argumentsDelta: String,
    ) : StreamFrame

    data class Finish(
        override val revision: Long,
        override val index: Long,
        val reasonKind: String?,
    ) : StreamFrame

    data class End(
        override val revision: Long,
        override val index: Long,
        val outcomeKind: String?,
        val eventType: String?,
        val seq: Long?,
    ) : StreamFrame

    /** Any frame the app does not need yet (usage, block-start variants, ...). */
    data class Other(
        override val revision: Long,
        override val index: Long,
        val type: String,
    ) : StreamFrame
}
