package com.dsbot.mobile.core

/**
 * Domain model shared by the whole app (owned by the Lead).
 *
 * Wire DTOs never leak into this package: `data/` maps DSH JSON into these
 * types so the UI and the bot engine compile against a stable vocabulary.
 */

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

/** Host bridge address + device token. [baseUrl] has no trailing slash. */
data class Endpoint(
    val baseUrl: String,
    val token: String,
) {
    val displayHost: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

    /** `ws://host:port/api/remote.mux` */
    val muxUrl: String
        get() {
            val scheme = if (baseUrl.startsWith("https://")) "wss://" else "ws://"
            val rest = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
            return scheme + rest + "/api/remote.mux"
        }

    /** `http://host:port/api` */
    val apiBase: String get() = baseUrl.trimEnd('/') + "/api"

    /** `http://host:port/api/<endpoint>` */
    fun apiUrl(endpoint: String): String = "$apiBase/$endpoint"

    companion object {
        fun normalize(rawUrl: String, rawToken: String): Endpoint? {
            val url = rawUrl.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            if (rawToken.isBlank()) return null
            return Endpoint(url, rawToken.trim())
        }
    }
}

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val endpoint: Endpoint? = null,
    /** Host name displayed in the UI; currently derived from the bridge address. */
    val hostName: String? = null,
    /** Host home directory reported by the `$events` ready frame (display only). */
    val hostHome: String? = null,
    val error: String? = null,
    val connectedAtMillis: Long? = null,
) {
    val isConnected: Boolean get() = phase == ConnectionPhase.CONNECTED

    val displayTarget: String?
        get() = hostName?.takeIf { it.isNotBlank() } ?: endpoint?.displayHost
}

// ---------------------------------------------------------------------------
// Sessions and models
// ---------------------------------------------------------------------------

data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

data class ReasoningEffort(
    val id: String,
    val name: String,
    val description: String? = null,
)

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String? = null,
    val efforts: List<ReasoningEffort> = emptyList(),
)

data class ModelGroup(
    val id: String,
    val name: String,
    val models: List<ModelInfo> = emptyList(),
)

data class ModelCatalog(
    val defaultSelection: ModelSelection? = null,
    val groups: List<ModelGroup> = emptyList(),
) {
    fun selectionName(selection: ModelSelection?): String? {
        if (selection == null) return null
        return groups.firstOrNull { it.id == selection.provider }
            ?.models?.firstOrNull { it.id == selection.model }?.name
            ?: selection.model
    }
}

/** One row of `session/list` reduced to what the app needs. */
data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val updatedAtMillis: Long,
    val running: Boolean,
    val blank: Boolean,
    val cwd: String?,
    val parentSessionId: String? = null,
)

// ---------------------------------------------------------------------------
// Approvals (security-critical, see docs/SECURITY.md §1)
// ---------------------------------------------------------------------------

enum class ApprovalDecision { ALLOW_ONCE, REJECT }

/**
 * A pending approval waterfall forwarded by the host.
 *
 * [argumentsJson] is the **complete** tool arguments payload; the UI must show
 * it verbatim (never a Bot-authored summary) and [fingerprint] is what the user
 * is actually approving.
 */
data class ApprovalRequest(
    val eventId: String,
    val agentId: String?,
    val toolName: String,
    val callId: String,
    val argumentsJson: String?,
    val reason: String?,
    val displayReasonZh: String?,
    val fingerprint: String,
    val receivedAtMillis: Long = System.currentTimeMillis(),
) {
    /** Heuristic list of dangerous tools / arguments (docs/SECURITY.md §1.5). */
    val dangerHints: List<String>
        get() {
            val hints = mutableListOf<String>()
            val args = argumentsJson.orEmpty()
            val tool = toolName.lowercase()
            if (tool in setOf("pwsh", "bash", "shell", "cmd", "computer", "browser")) hints += "可执行系统命令"
            if (args.contains("danger-full-access")) hints += "请求提升到完全权限"
            if (args.contains("rm -rf") || args.contains("del /f") || args.contains("format ")) hints += "可能删除文件"
            if (args.contains("sudo") || args.contains("runas")) hints += "提权执行"
            if (args.contains("credentials") || args.contains("SECRET") || args.contains(".credentials")) hints += "涉及凭据文件"
            if (args.contains("curl") && (args.contains("| sh") || args.contains("| bash"))) hints += "下载并执行脚本"
            return hints
        }

    val isDangerous: Boolean get() = dangerHints.isNotEmpty()
}

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

/** `dsbot://pair?url=http://192.168.1.5:8788&token=...` copied from the PC terminal. */
data class PairingLink(
    val endpoint: Endpoint,
    val raw: String,
) {
    companion object {
        fun parse(raw: String): PairingLink? {
            val text = raw.trim()
            if (!text.startsWith("dsbot://pair", ignoreCase = true)) return null
            val queryStart = text.indexOf('?')
            if (queryStart < 0) return null
            val params = text.substring(queryStart + 1)
                .split('&')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else {
                        val key = part.substring(0, i)
                        val value = java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
                        key to value
                    }
                }
                .toMap()
            val endpoint = Endpoint.normalize(params["url"] ?: return null, params["token"] ?: return null) ?: return null
            return PairingLink(endpoint, text)
        }
    }
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** Failure surfaced to the UI with a human-readable Chinese message. */
class DshException(
    val code: String,
    override val message: String,
    val detailsJson: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
