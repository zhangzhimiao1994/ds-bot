package com.dsbot.mobile.bots.internal

import com.dsbot.mobile.bots.AuthorKind
import com.dsbot.mobile.bots.BotGroup
import com.dsbot.mobile.bots.BotProfile
import com.dsbot.mobile.bots.MessageStatus
import com.dsbot.mobile.bots.TeamMessage
import com.dsbot.mobile.bots.ToolTrace
import com.dsbot.mobile.core.ModelSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 每个会话最多保留的消息条数（内存与磁盘一致）。 */
internal const val MAX_MESSAGES_PER_CONVERSATION = 400

/**
 * 团队数据的 JSON 持久化（owner: android-core）。
 *
 * - 落盘位置由构造参数 [dir] 决定；生产环境是 `context.filesDir/botteam`，
 *   文件为 `botteam/team.json`（测试用临时目录，避免依赖 Android Context）。
 * - **原子写**：先写 `team.json.tmp` 并 fsync，再原子 rename 覆盖 `team.json`，
 *   进程在写一半时被杀也不会留下半截 JSON。
 * - **200ms 防抖**：流式输出期间每帧都会触发保存，防抖把写盘合并到一次。
 * - **损坏恢复**：JSON 解析失败时把坏文件挪成 `team.json.bak`，以空团队启动并
 *   返回中文告警（UI 用 `TeamState.lastError` 展示）。
 * - 内部 DTO 与 `bots/` 领域模型分离：core 的 [ModelSelection] 等类型不会直接
 *   出现在磁盘格式里，未来改模型不会直接破坏旧数据。
 */
internal class BotStore(
    private val dir: File,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    /** 读盘用的调度器；生产环境为 Dispatchers.IO，单测注入 test dispatcher。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 一份完整的团队快照：名单 + 群 + 每个会话的最近消息。 */
    data class Snapshot(
        val bots: List<BotProfile> = emptyList(),
        val groups: List<BotGroup> = emptyList(),
        val transcripts: Map<String, List<TeamMessage>> = emptyMap(),
    )

    /** [load] 的结果；[warning] 非空表示数据被恢复且需要提示用户。 */
    data class LoadResult(
        val snapshot: Snapshot,
        val warning: String? = null,
    )

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 守护 [pending] / [saveJob]。 */
    private val lock = Any()

    /** 串行化真正的文件写入（防抖任务与 close() 的同步 flush 可能并发）。 */
    private val writeLock = Any()

    private var pending: Snapshot? = null
    private var saveJob: Job? = null

    /** close() 之后禁止后台任务再写盘（避免旧快照覆盖 close 时的最终快照）。 */
    @Volatile
    private var shuttingDown = false

    /** 最近一次写盘失败的原因（成功一次即清空），供引擎展示告警。 */
    @Volatile
    var lastPersistError: String? = null
        private set

    private val file: File get() = File(dir, FILE_NAME)
    private val backupFile: File get() = File(dir, FILE_NAME + BACKUP_SUFFIX)

    // -----------------------------------------------------------------------
    // 读
    // -----------------------------------------------------------------------

    /** 读取磁盘上的团队数据；损坏时备份 `.bak` 并返回空团队 + 中文告警。 */
    suspend fun load(): LoadResult = withContext(ioDispatcher) {
        dir.mkdirs()
        val f = file
        if (!f.exists()) return@withContext LoadResult(Snapshot())
        val raw = try {
            f.readText(Charsets.UTF_8)
        } catch (t: Exception) {
            return@withContext LoadResult(Snapshot(), warning = backupAndWarn(t.chineseMessage()))
        }
        if (raw.isBlank()) {
            return@withContext LoadResult(Snapshot(), warning = backupAndWarn("文件为空"))
        }
        try {
            val dto = json.decodeFromString(TeamFileDto.serializer(), raw)
            LoadResult(dto.toSnapshot())
        } catch (t: Exception) {
            LoadResult(Snapshot(), warning = backupAndWarn(t.chineseMessage()))
        }
    }

    private fun backupAndWarn(reason: String): String {
        val backedUp = runCatching {
            backupFile.delete()
            Files.move(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        return when {
            backedUp -> "团队数据损坏（$reason），已备份为 ${backupFile.name}，以空团队启动。"
            else -> "团队数据损坏（$reason）且备份失败，已以空团队启动。"
        }
    }

    // -----------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------

    /** 防抖保存：200ms 内的多次调用合并成一次落盘。 */
    fun scheduleSave(snapshot: Snapshot) {
        synchronized(lock) {
            if (shuttingDown) return
            pending = snapshot
            if (saveJob?.isActive == true) return
            saveJob = scope.launch { runSaveLoop() }
        }
    }

    /** 立即写掉所有待保存数据（suspend；供 clear()/测试使用）。 */
    suspend fun flush() {
        while (true) {
            val snapshot = synchronized(lock) {
                val snapshot = pending ?: return
                pending = null
                snapshot
            }
            writeSnapshot(snapshot, force = true)
        }
    }

    /** 立即同步写掉待保存数据（非 suspend；供 close() 在进程退出前调用）。 */
    fun flushNow() {
        shuttingDown = true
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
        }
        while (true) {
            val snapshot = synchronized(lock) {
                val snapshot = pending ?: return
                pending = null
                snapshot
            }
            writeSnapshot(snapshot, force = true)
        }
    }

    private suspend fun runSaveLoop() {
        try {
            while (true) {
                delay(debounceMillis)
                val snapshot = synchronized(lock) {
                    val snapshot = pending ?: return
                    pending = null
                    snapshot
                }
                writeSnapshot(snapshot, force = false)
            }
        } finally {
            // 竞态收尾：如果刚好在“任务即将结束”时安排了新的快照，重新起一个任务。
            var reschedule = false
            synchronized(lock) {
                saveJob = null
                reschedule = pending != null
            }
            if (reschedule) {
                savedPending()?.let(::scheduleSave)
            }
        }
    }

    private fun savedPending(): Snapshot? = synchronized(lock) { pending }

    private fun writeSnapshot(snapshot: Snapshot, force: Boolean) {
        synchronized(writeLock) {
            // close() 之后，后台防抖任务不再写盘；close() 自己的 flushNow 用 force=true。
            if (shuttingDown && !force) return
            try {
                dir.mkdirs()
                val text = json.encodeToString(TeamFileDto.serializer(), snapshot.toDto())
                val tmp = File(dir, FILE_NAME + TEMP_SUFFIX)
                FileChannel.open(
                    tmp.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                moveAtomically(tmp, file)
                lastPersistError = null
            } catch (t: Exception) {
                lastPersistError = t.chineseMessage()
            }
        }
    }

    private fun moveAtomically(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (!tmp.renameTo(target)) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    // -----------------------------------------------------------------------
    // DTO <-> 领域模型
    // -----------------------------------------------------------------------

    private fun Snapshot.toDto(): TeamFileDto = TeamFileDto(
        version = FILE_VERSION,
        bots = bots.map { it.toDto() },
        groups = groups.map { it.toDto() },
        transcripts = transcripts.entries.associate { (conversationId, messages) ->
            conversationId to messages.takeLast(MAX_MESSAGES_PER_CONVERSATION).map { it.toDto() }
        },
    )

    private fun TeamFileDto.toSnapshot(): Snapshot = Snapshot(
        bots = bots.mapNotNull { it.toModel() },
        groups = groups.mapNotNull { it.toModel() },
        transcripts = transcripts.entries.associate { (conversationId, messages) ->
            conversationId to messages
                .mapNotNull { it.toModel(conversationId) }
                .takeLast(MAX_MESSAGES_PER_CONVERSATION)
        },
    )

    private fun BotProfile.toDto(): BotDto = BotDto(
        id = id,
        name = name,
        emoji = emoji,
        colorIndex = colorIndex,
        persona = persona,
        model = model?.toDto(),
        cwd = cwd,
        sessionId = sessionId,
        isMain = isMain,
        createdAtMillis = createdAtMillis,
        lastActiveAtMillis = lastActiveAtMillis,
    )

    private fun BotDto.toModel(): BotProfile? {
        if (id.isBlank() || name.isBlank()) return null
        return BotProfile(
            id = id,
            name = name,
            emoji = emoji.ifBlank { "🤖" },
            colorIndex = colorIndex.coerceAtLeast(0),
            persona = persona,
            model = model?.toModel(),
            cwd = cwd?.takeIf { it.isNotBlank() },
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            isMain = isMain,
            createdAtMillis = createdAtMillis,
            lastActiveAtMillis = lastActiveAtMillis,
        )
    }

    private fun BotGroup.toDto(): GroupDto = GroupDto(
        id = id,
        name = name,
        emoji = emoji,
        memberIds = memberIds,
        adminBotId = adminBotId,
        createdAtMillis = createdAtMillis,
    )

    private fun GroupDto.toModel(): BotGroup? {
        if (id.isBlank() || name.isBlank()) return null
        return BotGroup(
            id = id,
            name = name,
            emoji = emoji.ifBlank { "👥" },
            memberIds = memberIds.filter { it.isNotBlank() }.distinct(),
            adminBotId = adminBotId?.takeIf { it.isNotBlank() && it in memberIds },
            createdAtMillis = createdAtMillis,
        )
    }

    private fun TeamMessage.toDto(): MessageDto = MessageDto(
        id = id,
        conversationId = conversationId,
        authorKind = authorKind.name,
        authorBotId = authorBotId,
        authorName = authorName,
        authorEmoji = authorEmoji,
        authorColorIndex = authorColorIndex,
        text = text,
        reasoning = reasoning,
        tools = tools.map { ToolTraceDto(it.callId, it.name, it.status, it.summary) },
        status = status.name,
        error = error,
        timeMillis = timeMillis,
        fromMention = fromMention,
    )

    private fun MessageDto.toModel(fallbackConversationId: String): TeamMessage? {
        if (id.isBlank()) return null
        return TeamMessage(
            id = id,
            conversationId = conversationId.ifBlank { fallbackConversationId },
            authorKind = parseEnum(authorKind, AuthorKind.BOT),
            authorBotId = authorBotId?.takeIf { it.isNotBlank() },
            authorName = authorName,
            authorEmoji = authorEmoji,
            authorColorIndex = authorColorIndex.coerceAtLeast(0),
            text = text,
            reasoning = reasoning?.takeIf { it.isNotBlank() },
            tools = tools.map { ToolTrace(it.callId, it.name, it.status, it.summary) },
            status = parseEnum(status, MessageStatus.DONE),
            error = error,
            timeMillis = timeMillis,
            fromMention = fromMention,
        )
    }

    private fun ModelSelection.toDto(): ModelSelectionDto = ModelSelectionDto(provider, model, reasoningEffort)

    private fun ModelSelectionDto.toModel(): ModelSelection? =
        if (provider.isBlank() || model.isBlank()) null else ModelSelection(provider, model, reasoningEffort)

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    internal companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 200L
        const val FILE_NAME = "team.json"
        const val TEMP_SUFFIX = ".tmp"
        const val BACKUP_SUFFIX = ".bak"
        const val FILE_VERSION = 1
    }
}

/** 把异常转成可读中文（日志/UI 用），避免把 Kotlin 默认英文类名直接抛给用户。 */
internal fun Throwable.chineseMessage(): String =
    (this as? com.dsbot.mobile.core.DshException)?.message
        ?: message?.takeIf { it.isNotBlank() }
        ?: javaClass.simpleName

// ---------------------------------------------------------------------------
// 磁盘格式（只在本文件可见；与领域模型解耦，便于未来演进）
// ---------------------------------------------------------------------------

@Serializable
private data class TeamFileDto(
    val version: Int = 1,
    val bots: List<BotDto> = emptyList(),
    val groups: List<GroupDto> = emptyList(),
    val transcripts: Map<String, List<MessageDto>> = emptyMap(),
)

@Serializable
private data class ModelSelectionDto(
    val provider: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
)

@Serializable
private data class BotDto(
    val id: String = "",
    val name: String = "",
    val emoji: String = "🤖",
    val colorIndex: Int = 0,
    val persona: String = "",
    val model: ModelSelectionDto? = null,
    val cwd: String? = null,
    val sessionId: String? = null,
    val isMain: Boolean = false,
    val createdAtMillis: Long = 0L,
    val lastActiveAtMillis: Long = 0L,
)

@Serializable
private data class GroupDto(
    val id: String = "",
    val name: String = "",
    val emoji: String = "👥",
    val memberIds: List<String> = emptyList(),
    val adminBotId: String? = null,
    val createdAtMillis: Long = 0L,
)

@Serializable
private data class ToolTraceDto(
    val callId: String = "",
    val name: String = "",
    val status: String = "SUCCESS",
    val summary: String? = null,
)

@Serializable
private data class MessageDto(
    val id: String = "",
    val conversationId: String = "",
    val authorKind: String = "BOT",
    val authorBotId: String? = null,
    val authorName: String? = null,
    val authorEmoji: String? = null,
    val authorColorIndex: Int = 0,
    val text: String = "",
    val reasoning: String? = null,
    val tools: List<ToolTraceDto> = emptyList(),
    val status: String = "DONE",
    val error: String? = null,
    val timeMillis: Long = 0L,
    val fromMention: Boolean = false,
)
