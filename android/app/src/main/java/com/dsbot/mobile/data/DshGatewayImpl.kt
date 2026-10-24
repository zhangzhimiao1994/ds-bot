package com.dsbot.mobile.data

import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.ConnectionPhase
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.core.DshException
import com.dsbot.mobile.core.DshGateway
import com.dsbot.mobile.core.Endpoint
import com.dsbot.mobile.core.ModelCatalog
import com.dsbot.mobile.core.ModelGroup
import com.dsbot.mobile.core.ModelInfo
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.core.ReasoningEffort
import com.dsbot.mobile.core.SessionEvent
import com.dsbot.mobile.core.SessionFeed
import com.dsbot.mobile.core.SessionSummary
import com.dsbot.mobile.core.StreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [DshGateway] over the host bridge (HTTP RPC + `/api/remote.mux` WebSocket).
 *
 * Wire contract is documented in `docs/PROTOCOL.md`. Everything that touches
 * DSH JSON lives here; the rest of the app sees typed models only.
 */
class DshGatewayImpl(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DshGateway {

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MAX_BODY_BYTES = 18 * 1024 * 1024L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _connection = MutableStateFlow(ConnectionState())
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    override val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    override val approvals: StateFlow<List<ApprovalRequest>> = _approvals.asStateFlow()

    private val _hostHome = MutableStateFlow<String?>(null)
    override val hostHome: StateFlow<String?> = _hostHome.asStateFlow()

    /** One live mux WebSocket (the app only needs one connection). */
    private var mux: MuxSocket? = null
    private var eventsClientId: String? = null

    // -----------------------------------------------------------------------
    // Connect / disconnect
    // -----------------------------------------------------------------------

    override suspend fun connect(endpoint: Endpoint): Result<Unit> = runCatching {
        disconnectInternal()
        _connection.value = ConnectionState(
            phase = ConnectionPhase.CONNECTING,
            endpoint = endpoint,
            hostName = endpoint.displayHost.substringBefore(':'),
        )
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val muxSocket = MuxSocket(endpoint)
            muxSocket.connect()
            mux = muxSocket
            // The `$events` stream is the source of approvals + host.home.
            muxSocket.openEvents { value -> handleEventsFrame(value) }
        }
        // Fail fast when the bridge/token is wrong.
        refreshSessions().getOrThrow()
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.CONNECTED,
            connectedAtMillis = clock(),
            error = null,
        )
    }.onFailure { error ->
        mux?.close()
        mux = null
        _connection.value = _connection.value.copy(
            phase = ConnectionPhase.FAILED,
            error = error.userMessage(),
        )
    }

    override fun disconnect() {
        disconnectInternal()
        _connection.value = ConnectionState(phase = ConnectionPhase.DISCONNECTED)
    }

    private fun disconnectInternal() {
        mux?.close()
        mux = null
        eventsClientId = null
        _approvals.value = emptyList()
        _hostHome.value = null
    }

    override suspend fun close() {
        disconnectInternal()
    }

    // -----------------------------------------------------------------------
    // Unary RPC
    // -----------------------------------------------------------------------

    private suspend fun rpc(method: String, args: JsonObject): JsonObject = withConnection(method) { endpoint ->
        val rpcId = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", method)
            put("payload", buildJsonObject { put("args", args) })
        }
        val request = Request.Builder()
            .url(endpoint.apiUrl(method))
            .header("Authorization", "Bearer ${endpoint.token}")
            .header("Content-Type", "application/json")
            .post(envelope.toString().toRequestBody(JSON_MEDIA))
            .build()
        val response = http.newCall(request).execute()
        response.use { res ->
            val body = res.body?.string().orEmpty()
            if (res.code == 401) throw DshException("bridge/unauthorized", "设备未授权：请在电脑端重新配对")
            if (res.code == 403) throw DshException("bridge/forbidden", "该操作被桥接安全策略拒绝")
            if (res.code == 429) throw DshException("bridge/rate-limited", "请求过于频繁，请稍后再试")
            if (!res.isSuccessful) throw DshException("http/${res.code}", "请求失败（HTTP ${res.code}）")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw DshException("protocol/invalid-json", "主机返回了无法解析的响应")
            }
            val result = root["result"]?.jsonObject
                ?: throw DshException("protocol/invalid-envelope", "主机响应缺少 result")
            val ok = result["ok"]?.jsonPrimitive?.contentOrNull == "true"
            if (!ok) {
                val error = result["error"]?.jsonObject
                throw DshException(
                    error?.get("code")?.jsonPrimitive?.contentOrNull ?: "dsh/failed",
                    error?.get("message")?.jsonPrimitive?.contentOrNull ?: "主机返回失败",
                )
            }
            result["value"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    override suspend fun refreshSessions(): Result<Unit> = runCatching {
        val value = rpc("session/list", buildJsonObject { put("_request", buildJsonObject { }) })
        val items = value["items"]?.jsonArrayOrNull() ?: JsonArray(emptyList())
        _sessions.value = items.mapNotNull { it.jsonObjectOrNull()?.toSessionSummary() }
    }

    override suspend fun modelCatalog(): Result<ModelCatalog> = runCatching {
        val value = rpc("session/modelCatalog", JsonObject(emptyMap()))
        val default = value["default"]?.jsonObjectOrNull()?.toSelection()
        val groups = value["groups"]?.jsonArrayOrNull()?.mapNotNull { element ->
            val group = element.jsonObjectOrNull() ?: return@mapNotNull null
            ModelGroup(
                id = group.str("id") ?: return@mapNotNull null,
                name = group.str("name") ?: group.str("id") ?: "",
                models = group["models"]?.jsonArrayOrNull()?.mapNotNull { m ->
                    val model = m.jsonObjectOrNull() ?: return@mapNotNull null
                    ModelInfo(
                        id = model.str("id") ?: return@mapNotNull null,
                        name = model.str("name") ?: model.str("id") ?: "",
                        description = model.str("description"),
                        efforts = model["reasoning"]?.jsonObjectOrNull()?.get("efforts")?.jsonArrayOrNull()
                            ?.mapNotNull { e ->
                                val effort = e.jsonObjectOrNull() ?: return@mapNotNull null
                                ReasoningEffort(
                                    id = effort.str("id") ?: return@mapNotNull null,
                                    name = effort.str("name") ?: effort.str("id") ?: "",
                                    description = effort.str("description"),
                                )
                            }.orEmpty(),
                    )
                }.orEmpty(),
            )
        }.orEmpty()
        ModelCatalog(default, groups)
    }

    override suspend fun createSession(cwd: String?): Result<String> = runCatching {
        val request = buildJsonObject {
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        val value = rpc("session/create", buildJsonObject { put("request", request) })
        value.str("sessionId") ?: throw DshException("session/create-failed", "创建会话失败：缺少 sessionId")
    }

    override suspend fun renameSession(sessionId: String, title: String): Result<Unit> = runCatching {
        rpc(
            "session/rename",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("title", title)
                })
            },
        )
        Unit
    }

    override suspend fun selectModel(sessionId: String, selection: ModelSelection): Result<Unit> = runCatching {
        rpc(
            "session/selectModel",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("sessionId", sessionId)
                    put("provider", selection.provider)
                    put("model", selection.model)
                    selection.reasoningEffort?.let { put("reasoningEffort", it) }
                })
            },
        )
        Unit
    }

    override suspend fun prompt(sessionId: String, text: String): Result<Unit> = runCatching {
        val requestId = UUID.randomUUID().toString()
        rpc(
            "session/prompt",
            buildJsonObject {
                put("request", buildJsonObject {
                    put("requestId", requestId)
                    put("sessionId", sessionId)
                    put("mode", "queue")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        })
                    })
                })
            },
        )
        Unit
    }

    override suspend fun cancelTurn(sessionId: String): Result<Unit> = runCatching {
        rpc("session/cancel", buildJsonObject { put("request", buildJsonObject { put("sessionId", sessionId) }) })
        Unit
    }

    // -----------------------------------------------------------------------
    // Approvals
    // -----------------------------------------------------------------------

    override suspend fun respondApproval(approval: ApprovalRequest, decision: ApprovalDecision): Result<Unit> =
        runCatching {
            val clientId = eventsClientId ?: throw DshException("events/not-ready", "审批通道尚未就绪，请稍后重试")
            val outcomeKind = when (decision) {
                ApprovalDecision.ALLOW_ONCE -> "allowed-once"
                ApprovalDecision.REJECT -> "rejected"
            }
            rpc(
                "\$events/result",
                buildJsonObject {
                    put("clientId", clientId)
                    put("eventId", approval.eventId)
                    put("outcome", buildJsonObject { put("kind", outcomeKind) })
                },
            )
            _approvals.update { list -> list.filterNot { it.eventId == approval.eventId } }
            Unit
        }

    // -----------------------------------------------------------------------
    // $events stream
    // -----------------------------------------------------------------------

    private fun handleEventsFrame(value: JsonObject) {
        when (value.str("type")) {
            "ready" -> {
                eventsClientId = value.str("clientId")
                value["host"]?.jsonObjectOrNull()?.str("home")?.let { _hostHome.value = it }
            }
            "waterfall" -> {
                val eventKind = value.str("event")
                if (eventKind != "approval/request") return
                val eventId = value.str("eventId") ?: return
                val agentId = value.str("agentId")
                val request = value["request"]?.jsonObjectOrNull() ?: return
                val toolName = request.str("toolName") ?: return
                val callId = request.str("callId") ?: ""
                val argumentsJson = request["arguments"]?.toString()
                val reason = request.str("reason")
                val displayReason = request["displayReason"]?.jsonObjectOrNull()?.str("zh")
                val approval = ApprovalRequest(
                    eventId = eventId,
                    agentId = agentId,
                    toolName = toolName,
                    callId = callId,
                    argumentsJson = argumentsJson,
                    reason = reason,
                    displayReasonZh = displayReason,
                    fingerprint = fingerprintOf(toolName, callId, argumentsJson),
                    receivedAtMillis = clock(),
                )
                _approvals.update { list -> (list.filterNot { it.eventId == eventId } + approval).sortedBy { it.receivedAtMillis } }
            }
            "cancel" -> {
                val eventId = value.str("eventId") ?: return
                _approvals.update { list -> list.filterNot { it.eventId == eventId } }
            }
            "emit" -> {
                // Session-list notifications: refresh lazily (cheap, rate-limited by events).
                val event = value.str("event")
                if (event?.startsWith("api-session/") == true) {
                    scope.launch { runCatching { refreshSessions() } }
                }
            }
        }
    }

    /** sha256(toolName + "\n" + callId + "\n" + arguments), base64url, first 16 chars. */
    private fun fingerprintOf(toolName: String, callId: String, argumentsJson: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((toolName + "\n" + callId + "\n" + argumentsJson.orEmpty()).toByteArray())
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
        return encoded.take(16)
    }

    // -----------------------------------------------------------------------
    // session/follow stream
    // -----------------------------------------------------------------------

    override fun follow(sessionId: String): Flow<SessionFeed> = callbackFlow {
        val socket = mux
        if (socket == null) {
            trySend(SessionFeed.Ended("尚未连接电脑"))
            close()
            return@callbackFlow
        }
        val streamId = "follow-" + UUID.randomUUID().toString().take(8)
        val consumer = StreamConsumer(
            onFrame = { value ->
                val feeds = parseFollowValue(value)
                for (feed in feeds) trySend(feed)
            },
            onEnd = { error ->
                trySend(SessionFeed.Ended(error))
                close()
            },
            onError = { error ->
                trySend(SessionFeed.Ended(error))
                close()
            },
        )
        socket.openStream(streamId, "session/follow", buildJsonObject {
            put("request", buildJsonObject {
                put("address", buildJsonObject {
                    put("kind", "session")
                    put("sessionId", sessionId)
                })
                put("maxMessages", 20)
                put("assistantStream", true)
            })
        }, consumer)
        awaitClose {
            socket.closeStream(streamId)
        }
    }.flowOn(Dispatchers.IO)

    private fun parseFollowValue(value: JsonObject): List<SessionFeed> = when (value.str("type")) {
        "snapshot" -> {
            val cursor = value["cursor"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val revision = value["assistantStream"]?.jsonObjectOrNull()?.get("revision")
                ?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val running = value["projections"]?.jsonObjectOrNull()?.get("values")?.jsonObjectOrNull()
                ?.get("inbox")?.jsonObjectOrNull() != null // presence is not reliable; default false
            val records = value["records"]?.jsonArrayOrNull()?.mapNotNull { r ->
                r.jsonObjectOrNull()?.get("event")?.jsonObjectOrNull()?.toSessionEvent()
            }.orEmpty()
            listOf(SessionFeed.Snapshot(cursor, revision, running = false, records = records))
        }
        "event" -> {
            val event = value["event"]?.jsonObjectOrNull()?.toSessionEvent() ?: return emptyList()
            listOf(SessionFeed.Event(event))
        }
        "assistant-stream" -> {
            val frame = value["frame"]?.jsonObjectOrNull()?.toStreamFrame() ?: return emptyList()
            listOf(SessionFeed.Stream(frame))
        }
        else -> emptyList()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun <T> withConnection(method: String, block: suspend (Endpoint) -> T): T {
        val endpoint = _connection.value.endpoint
            ?: throw DshException("bridge/disconnected", "尚未连接电脑")
        if (_connection.value.phase == ConnectionPhase.FAILED) {
            throw DshException("bridge/disconnected", "连接失败，请重新连接（$method）")
        }
        return block(endpoint)
    }

    private fun Throwable.userMessage(): String = when (this) {
        is DshException -> message
        else -> message ?: "网络请求失败"
    }

    private fun JsonObject.toSessionSummary(): SessionSummary? {
        val id = str("sessionId") ?: return null
        val projections = this["projections"]?.jsonObjectOrNull()?.get("values")?.jsonObjectOrNull()
        return SessionSummary(
            sessionId = id,
            title = projections?.str("title"),
            updatedAtMillis = this["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            running = this["running"]?.jsonPrimitive?.contentOrNull == "true",
            blank = this["blank"]?.jsonPrimitive?.contentOrNull == "true",
            cwd = this["cwd"]?.jsonPrimitive?.contentOrNull,
            parentSessionId = this["parentSessionId"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun JsonObject.toSelection(): ModelSelection? {
        val provider = str("provider") ?: return null
        val model = str("model") ?: return null
        return ModelSelection(provider, model, str("reasoningEffort"))
    }

    private fun JsonObject.toSessionEvent(): SessionEvent = SessionEvent(
        type = str("type") ?: "unknown",
        seq = this["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L,
        timeMillis = this["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        data = this["data"]?.jsonObjectOrNull() ?: JsonObject(emptyMap()),
    )

    private fun JsonObject.toStreamFrame(): StreamFrame {
        val revision = this["revision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
        val index = this["index"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
        val chunk = this["chunk"]?.jsonObjectOrNull()
        return when (str("type")) {
            "start" -> StreamFrame.Start(revision, index)
            "end" -> StreamFrame.End(
                revision = revision,
                index = index,
                outcomeKind = this["outcome"]?.jsonObjectOrNull()?.str("kind"),
                eventType = this["outcome"]?.jsonObjectOrNull()?.str("eventType"),
                seq = this["outcome"]?.jsonObjectOrNull()?.get("seq")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            )
            "chunk" -> when (chunk?.str("type")) {
                "text-delta" -> StreamFrame.Delta(
                    revision, index,
                    chunkIndex = chunk["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    kind = "text",
                    text = chunk.str("text").orEmpty(),
                )
                "reasoning-delta" -> StreamFrame.Delta(
                    revision, index,
                    chunkIndex = chunk["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    kind = "reasoning",
                    text = chunk.str("text").orEmpty(),
                )
                "block-start" -> StreamFrame.BlockStart(
                    revision, index,
                    chunkIndex = chunk["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    blockType = chunk.str("blockType").orEmpty(),
                )
                "block-end" -> StreamFrame.BlockEnd(
                    revision, index,
                    chunkIndex = chunk["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    blockType = chunk["block"]?.jsonObjectOrNull()?.str("type").orEmpty(),
                    text = chunk["block"]?.jsonObjectOrNull()?.str("text").orEmpty(),
                )
                "tool-call-delta" -> StreamFrame.ToolCallDelta(
                    revision, index,
                    chunkIndex = chunk["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    id = chunk.str("id").orEmpty(),
                    name = chunk.str("name").orEmpty(),
                    argumentsDelta = chunk.str("argumentsDelta").orEmpty(),
                )
                "finish" -> StreamFrame.Finish(
                    revision, index,
                    reasonKind = chunk["reason"]?.jsonObjectOrNull()?.str("kind"),
                )
                else -> StreamFrame.Other(revision, index, chunk?.str("type") ?: "chunk")
            }
            else -> StreamFrame.Other(revision, index, str("type") ?: "unknown")
        }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { element -> (element as? JsonPrimitive)?.contentOrNull }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    // -----------------------------------------------------------------------
    // Mux WebSocket
    // -----------------------------------------------------------------------

    private class StreamConsumer(
        val onFrame: (JsonObject) -> Unit,
        val onEnd: (String?) -> Unit,
        val onError: (String) -> Unit,
    )

    /**
     * The multiplexed stream socket: one WS, many logical streams, frame router.
     * Reconnects lazily on the next [connect]; stream failures surface as
     * [SessionFeed.Ended] so callers can restart.
     */
    private inner class MuxSocket(private val endpoint: Endpoint) {

        private val consumers = ConcurrentHashMap<String, StreamConsumer>()
        private var socket: WebSocket? = null
        private val opened = MutableStateFlow(false)

        fun connect() {
            val request = Request.Builder()
                .url(endpoint.muxUrl)
                .header("Authorization", "Bearer ${endpoint.token}")
                .build()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.value = true
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.value = false
                    consumers.forEach { (_, consumer) ->
                        consumer.onError(t.message ?: "连接中断")
                    }
                    consumers.clear()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    opened.value = false
                }
            }
            val ws = http.newWebSocket(request, listener)
            socket = ws
            // Wait briefly for the handshake so connect() can fail fast.
            val deadline = System.currentTimeMillis() + 10_000
            while (!opened.value && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (!opened.value) throw DshException("bridge/ws-failed", "无法建立实时通道（请检查地址与设备授权）")
        }

        fun openEvents(onValue: (JsonObject) -> Unit) {
            openStream(
                streamId = "events",
                endpointName = "\$events",
                args = JsonObject(emptyMap()),
                consumer = StreamConsumer(
                    onFrame = { value -> onValue(value) },
                    onEnd = { /* events stream ending is not fatal; approvals refill on reconnect */ },
                    onError = { },
                ),
            )
        }

        fun openStream(streamId: String, endpointName: String, args: JsonObject, consumer: StreamConsumer) {
            consumers[streamId] = consumer
            send(buildJsonObject {
                put("type", "open")
                put("streamId", streamId)
                put("endpoint", endpointName)
                put("payload", buildJsonObject { put("args", args) })
            })
        }

        fun closeStream(streamId: String) {
            consumers.remove(streamId)
            send(buildJsonObject {
                put("type", "cancel")
                put("streamId", streamId)
            })
        }

        fun close() {
            consumers.clear()
            runCatching { socket?.close(1000, "bye") }
            socket = null
            opened.value = false
        }

        private fun send(frame: JsonObject) {
            val ws = socket ?: throw DshException("bridge/ws-failed", "实时通道未建立")
            ws.send(frame.toString())
        }

        private fun handleFrame(text: String) {
            val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val type = frame.str("type") ?: return
            val streamId = frame.str("streamId")
            when (type) {
                "item" -> {
                    val value = frame["value"]?.jsonObjectOrNull() ?: return
                    val consumer = streamId?.let { consumers[it] }
                    if (consumer != null) {
                        consumer.onFrame(value)
                    } else if (streamId == "events") {
                        handleEventsFrame(value)
                    }
                }
                "end" -> {
                    val consumer = streamId?.let { consumers.remove(it) }
                    consumer?.onEnd(null)
                }
                "error" -> {
                    val message = frame["error"]?.jsonObjectOrNull()?.str("message") ?: "流式请求失败"
                    val consumer = streamId?.let { consumers.remove(it) }
                    consumer?.onError(message)
                }
            }
        }
    }
}
