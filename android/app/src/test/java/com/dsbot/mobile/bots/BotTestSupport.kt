package com.dsbot.mobile.bots

import com.dsbot.mobile.bots.internal.BotEngine
import com.dsbot.mobile.bots.internal.BotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.io.File

/**
 * 单测脚手架：把 BotStore/BotEngine 接到 test scheduler 上，数据目录用临时目录，
 * 完全不碰 Android Context / 网络 / 真实 DSH。
 */
internal class BotHarness(
    val dir: File,
    val gateway: FakeDshGateway,
    val store: BotStore,
    val engine: BotEngine,
    val scope: CoroutineScope,
) {
    suspend fun loaded(): BotHarness {
        engine.load().getOrThrow()
        return this
    }

    /** 关闭引擎（会同步落盘并取消 scope）。 */
    fun close() {
        engine.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.createHarness(dir: File): BotHarness {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val gateway = FakeDshGateway()
    val store = BotStore(
        dir = dir,
        scope = scope,
        debounceMillis = 0L,
        ioDispatcher = dispatcher,
    )
    val engine = BotEngine(
        store = store,
        gateway = gateway,
        scope = scope,
        clock = { 1_000L },
        ownsScope = true,
    )
    return BotHarness(dir, gateway, store, engine, scope)
}

/** 便捷：发完消息并断言 Result 成功。 */
internal fun <T> Result<T>.orFail(): T = getOrElse { throw AssertionError("期望成功但失败了：${it.message}", it) }

/** 便捷：断言失败并返回 DshException（用于校验中文错误消息）。 */
internal fun <T> Result<T>.failureOrNull(): Throwable? = exceptionOrNull()

/** 便捷：在 harness 里建一个机器人（默认会走 fake 的 createSession）。 */
internal suspend fun BotHarness.createBot(
    name: String,
    emoji: String = "🤖",
    persona: String = "测试角色",
    model: com.dsbot.mobile.core.ModelSelection? = null,
    cwd: String? = null,
    isMain: Boolean = false,
): BotProfile = engine.createBot(
    BotDraft(
        name = name,
        persona = persona,
        emoji = emoji,
        model = model,
        cwd = cwd,
        isMain = isMain,
    ),
).orFail()

/** 便捷：建群。 */
internal suspend fun BotHarness.createGroup(
    name: String,
    memberIds: List<String>,
    adminBotId: String? = null,
    emoji: String = "👥",
): BotGroup = engine.createGroup(
    GroupDraft(name = name, memberIds = memberIds, adminBotId = adminBotId, emoji = emoji),
).orFail()

/** 便捷：取会话消息。 */
internal fun BotHarness.messages(conversationId: String): List<TeamMessage> =
    engine.conversation(conversationId).value.messages

/** 便捷：取某个会话里指定作者的消息。 */
internal fun BotHarness.botMessages(conversationId: String): List<TeamMessage> =
    messages(conversationId).filter { it.authorKind == AuthorKind.BOT }

internal fun BotHarness.systemMessages(conversationId: String): List<TeamMessage> =
    messages(conversationId).filter { it.authorKind == AuthorKind.SYSTEM }

