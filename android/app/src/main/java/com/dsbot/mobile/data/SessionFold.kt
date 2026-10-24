package com.dsbot.mobile.data

import com.dsbot.mobile.core.SessionFeed
import com.dsbot.mobile.core.SessionEvent
import com.dsbot.mobile.core.StreamFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Folds one followed DSH session into the assistant turn currently being
 * rendered (owned by the Lead). The bot engine drives it from
 * [com.dsbot.mobile.core.DshGateway.follow] and maps [FoldState] onto
 * `TeamMessage`.
 *
 * Behaviour derived from real captures (see work/probe/LIVE-SAMPLES.md):
 *  - `assistant-stream` `text-delta`/`reasoning-delta` chunks append;
 *  - `block-end` carries the authoritative block text (replaces the buffer);
 *  - `assistant/message` carries the authoritative final message;
 *  - `tool/call` then `tool/result` describe one tool trace;
 *  - `turn/end` (or a follow `Ended`) completes the turn.
 */
class SessionFold(val sessionId: String) {

    data class FoldTool(
        val callId: String,
        val name: String,
        val status: String = "RUNNING",
        val summary: String? = null,
    )

    data class FoldState(
        val text: String = "",
        val reasoning: String = "",
        val tools: List<FoldTool> = emptyList(),
        val running: Boolean = false,
        val completed: Boolean = false,
        val error: String? = null,
        val lastRevision: Long = -1L,
    )

    private var text = StringBuilder()
    private var reasoning = StringBuilder()
    private val tools = LinkedHashMap<String, FoldTool>()
    private var revision = -1L
    private var running = false
    private var completed = false
    private var error: String? = null

    fun state(): FoldState = FoldState(
        text = text.toString(),
        reasoning = reasoning.toString(),
        tools = tools.values.toList(),
        running = running,
        completed = completed,
        error = error,
        lastRevision = revision,
    )

    fun apply(feed: SessionFeed): FoldState {
        when (feed) {
            is SessionFeed.Snapshot -> {
                revision = feed.assistantRevision
                running = feed.running
            }
            is SessionFeed.Event -> applyEvent(feed.event)
            is SessionFeed.Stream -> applyFrame(feed.frame)
            is SessionFeed.Ended -> {
                running = false
                completed = true
                if (feed.error != null) error = feed.error
            }
        }
        return state()
    }

    private fun applyFrame(frame: StreamFrame) {
        if (frame.revision > 0 && frame.revision < revision) return // stale frame
        revision = frame.revision
        when (frame) {
            is StreamFrame.Start -> {
                running = true
                completed = false
            }
            is StreamFrame.Delta -> {
                running = true
                if (frame.kind == "reasoning") reasoning.append(frame.text) else text.append(frame.text)
            }
            is StreamFrame.BlockStart -> {
                running = true
            }
            is StreamFrame.BlockEnd -> when (frame.blockType) {
                "text" -> text = StringBuilder(frame.text)
                "reasoning" -> reasoning = StringBuilder(frame.text)
                else -> Unit
            }
            is StreamFrame.ToolCallDelta -> {
                if (frame.id.isNotBlank()) {
                    val existing = tools[frame.id]
                    tools[frame.id] = FoldTool(
                        callId = frame.id,
                        name = frame.name.ifBlank { existing?.name ?: "tool" },
                        status = existing?.status ?: "RUNNING",
                        summary = existing?.summary,
                    )
                }
            }
            is StreamFrame.Finish -> {
                // The turn is complete once `turn/end` arrives; keep running=true
                // so the UI keeps the streaming cursor through the final message.
            }
            is StreamFrame.End -> {
                if (frame.outcomeKind == null || frame.outcomeKind == "committed") {
                    running = false
                    completed = true
                } else {
                    error = "本轮生成失败：${frame.outcomeKind}"
                    running = false
                }
            }
            is StreamFrame.Other -> Unit
        }
    }

    private fun applyEvent(event: SessionEvent) {
        when (event.type) {
            "assistant/message" -> {
                val content = event.data["message"]?.jsonObjectOrNull()?.get("content")?.jsonArrayOrNull() ?: return
                val textParts = mutableListOf<String>()
                val reasoningParts = mutableListOf<String>()
                for (block in content) {
                    val obj = block.jsonObjectOrNull() ?: continue
                    when (obj.str("type")) {
                        "text" -> obj.str("text")?.let(textParts::add)
                        "reasoning" -> obj.str("text")?.let(reasoningParts::add)
                        "tool-call" -> {
                            val id = obj.str("id") ?: continue
                            val name = obj.str("name") ?: "tool"
                            val args = obj.str("arguments").orEmpty()
                            val existing = tools[id]
                            tools[id] = FoldTool(
                                callId = id,
                                name = name,
                                status = existing?.status ?: "RUNNING",
                                summary = existing?.summary,
                            )
                            if (args.isNotBlank()) {
                                // Keep the argument preview bounded; full arguments
                                // are only shown for approvals.
                                tools[id] = tools[id]!!.copy(summary = existing?.summary)
                            }
                        }
                    }
                }
                if (textParts.isNotEmpty()) text = StringBuilder(textParts.joinToString("\n"))
                if (reasoningParts.isNotEmpty()) reasoning = StringBuilder(reasoningParts.joinToString("\n\n"))
            }
            "tool/call" -> {
                val id = event.data.str("callId") ?: return
                val name = event.data.str("name") ?: "tool"
                tools[id] = FoldTool(callId = id, name = name, status = "RUNNING")
            }
            "tool/result" -> {
                val id = event.data["message"]?.jsonObjectOrNull()?.get("toolCallId")?.jsonPrimitive?.contentOrNull
                    ?: event.data.str("callId")
                    ?: return
                val isError = event.data["message"]?.jsonObjectOrNull()?.get("isError")?.jsonPrimitive?.contentOrNull == "true"
                val summary = event.data["message"]?.jsonObjectOrNull()
                    ?.get("content")?.jsonArrayOrNull()
                    ?.mapNotNull { it.jsonObjectOrNull()?.str("text") }
                    ?.joinToString("\n")
                    ?.take(400)
                val existing = tools[id]
                tools[id] = FoldTool(
                    callId = id,
                    name = existing?.name ?: (event.data.str("name") ?: "tool"),
                    status = if (isError) "ERROR" else "SUCCESS",
                    summary = summary,
                )
            }
            "turn/end" -> {
                running = false
                completed = true
                val reasonKind = event.data["reason"]?.jsonObjectOrNull()?.str("kind")
                if (reasonKind == "aborted" || reasonKind == "error") {
                    error = if (reasonKind == "error") "本轮出错" else null
                }
            }
            "turn/start" -> {
                running = true
                completed = false
                error = null
            }
            else -> Unit
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        (this as? JsonObject)

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? =
        (this as? JsonArray)
}
