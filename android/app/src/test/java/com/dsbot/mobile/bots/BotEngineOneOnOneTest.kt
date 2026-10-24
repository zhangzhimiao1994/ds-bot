package com.dsbot.mobile.bots

import com.dsbot.mobile.core.DshException
import com.dsbot.mobile.core.ModelSelection
import com.dsbot.mobile.core.SessionFeed
import com.dsbot.mobile.core.StreamFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1:1 会话编排单测：初始化提示词、流式折叠、错误/中断、离线补建、
 * session/not-found 重建重试、stop()。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotEngineOneOnOneTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun kotlinx.coroutines.test.TestScope.newHarness(): BotHarness =
        createHarness(File(tmp.root, "botteam")).loaded()

    @Test
    fun createBotSendsInitializationPromptRenameAndModel() = runTest {
        val harness = newHarness()
        try {
            val model = ModelSelection("deepseek-official", "deepseek-flash", "max")
            val bot = harness.engine.createBot(
                BotDraft(
                    name = "代码匠",
                    persona = "你是资深软件工程师，擅长定位缺陷。",
                    emoji = "🛠",
                    colorIndex = 2,
                    model = model,
                    cwd = "E:\\work",
                    isMain = true,
                ),
            ).orFail()

            assertEquals("session-1", bot.sessionId)
            assertTrue(bot.isMain)
            // 主机侧：改名 "emoji name"、选模型、发角色初始化提示词。
            assertEquals("🛠 代码匠", harness.gateway.renames.single().title)
            assertEquals(model, harness.gateway.modelSelections.single().selection)
            val initPrompt = harness.gateway.prompts.single()
            assertEquals("session-1", initPrompt.sessionId)
            assertTrue(initPrompt.text.startsWith("[团队初始化] 你是「代码匠」。"))
            assertTrue(initPrompt.text.contains("你是资深软件工程师，擅长定位缺陷。"))
            assertTrue(initPrompt.text.contains("始终以该角色对话"))
            assertTrue(initPrompt.text.contains("简体中文"))
            assertTrue(initPrompt.text.contains("先结论后细节"))

            // 手机侧：名单已落盘到 state，会话已绑定。
            val saved = harness.engine.state.value.bots.single()
            assertEquals("代码匠", saved.name)
            assertEquals("session-1", saved.sessionId)
            val conversation = harness.engine.conversation(ConversationIds.bot(bot.id)).value
            assertEquals(ConversationKind.BOT, conversation.kind)
            assertEquals("代码匠", conversation.title)
            assertEquals(listOf("session-1"), conversation.boundSessionIds)
        } finally {
            harness.close()
        }
    }

    @Test
    fun sendStreamsAndFoldsAssistantTurn() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = { Feeds.completedTurn(text = "最终回答", reasoning = "先推理一下") }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "你好").orFail()

            val messages = harness.messages(conversationId)
            assertEquals(2, messages.size)
            assertEquals(AuthorKind.USER, messages[0].authorKind)
            assertEquals("你好", messages[0].text)
            val reply = messages[1]
            assertEquals(AuthorKind.BOT, reply.authorKind)
            assertEquals(bot.id, reply.authorBotId)
            assertEquals("代码匠", reply.authorName)
            assertEquals(MessageStatus.DONE, reply.status)
            // block-end 的权威文本覆盖流式增量；推理内容单独折叠。
            assertEquals("最终回答", reply.text)
            assertEquals("先推理一下", reply.reasoning)
            assertNull(reply.error)
            assertFalse(reply.fromMention)

            // prompt 发到绑定会话，内容就是用户原文。
            assertEquals("你好", harness.gateway.userPrompts.single().text)
            assertEquals("session-1", harness.gateway.userPrompts.single().sessionId)

            val conversation = harness.engine.conversation(conversationId).value
            assertTrue(conversation.workingBotIds.isEmpty())
            assertFalse(conversation.isWorking)
            assertEquals("最终回答", harness.engine.state.value.summaries.single().lastText)
        } finally {
            harness.close()
        }
    }

    @Test
    fun sendFoldsToolTrace() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = {
                Feeds.completedTurnWithTools(text = "已修好", toolName = "pwsh", toolOutput = "BUILD SUCCESSFUL")
            }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "修一下构建").orFail()

            val reply = harness.botMessages(conversationId).single()
            assertEquals("已修好", reply.text)
            assertEquals(1, reply.tools.size)
            assertEquals("call-1", reply.tools.single().callId)
            assertEquals("pwsh", reply.tools.single().name)
            assertEquals("SUCCESS", reply.tools.single().status)
            assertEquals("BUILD SUCCESSFUL", reply.tools.single().summary)
        } finally {
            harness.close()
        }
    }

    @Test
    fun abortedTurnMarksInterrupted() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            harness.gateway.followHandler = { Feeds.abortedTurn(partial = "说到一半") }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "继续").orFail()

            val reply = harness.botMessages(conversationId).single()
            assertEquals(MessageStatus.INTERRUPTED, reply.status)
            assertEquals("说到一半", reply.text)
            assertTrue(harness.engine.conversation(conversationId).value.workingBotIds.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun errorTurnMarksErrorAndSendFailsWithChineseMessage() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            harness.gateway.followHandler = { Feeds.erroredTurn() }
            val conversationId = ConversationIds.bot(bot.id)

            val result = harness.engine.send(conversationId, "你好")

            val reply = harness.botMessages(conversationId).single()
            assertEquals(MessageStatus.ERROR, reply.status)
            assertEquals("主机连接中断", reply.error)
            assertTrue(result.isFailure)
            assertEquals("主机连接中断", result.exceptionOrNull()?.message)
        } finally {
            harness.close()
        }
    }

    @Test
    fun offlineCreateThenProvisionsSessionOnFirstSend() = runTest {
        val harness = newHarness()
        try {
            harness.gateway.createSessionHandler = {
                Result.failure(DshException("offline", "尚未连接电脑"))
            }
            val bot = harness.engine.createBot(BotDraft(name = "代码匠", persona = "你是工程师")).orFail()
            assertNull("离线建档允许 sessionId=null", bot.sessionId)
            assertNull(harness.engine.state.value.bots.single().sessionId)
            assertTrue(harness.engine.state.value.lastError.orEmpty().contains("离线创建"))

            // 联网后首次发送：补建会话，并把初始化提示词与用户消息合并成同一轮。
            harness.gateway.createSessionHandler = null
            harness.gateway.followHandler = { Feeds.completedTurn("上线了") }
            val conversationId = ConversationIds.bot(bot.id)
            harness.engine.send(conversationId, "你好").orFail()

            assertEquals(2, harness.gateway.createdSessionCwds.size) // 失败一次 + 补建一次
            val prompt = harness.gateway.prompts.single()
            assertEquals("session-1", prompt.sessionId)
            assertTrue("补建后的第一轮应包含初始化提示词", prompt.text.startsWith("[团队初始化] 你是「代码匠」。"))
            assertTrue(prompt.text.contains("你好"))
            assertEquals("session-1", harness.engine.state.value.bots.single().sessionId)
            assertEquals(MessageStatus.DONE, harness.botMessages(conversationId).single().status)
        } finally {
            harness.close()
        }
    }

    @Test
    fun notFoundOnFollowRebuildsSessionAndRetriesOnce() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = { sessionId ->
                if (sessionId == "session-1") Feeds.notFound() else Feeds.completedTurn("重试成功")
            }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "你好").orFail()

            assertEquals("重建一次会话", 2, harness.gateway.createdSessionCwds.size)
            assertEquals("session-2", harness.engine.state.value.bots.single().sessionId)
            val retried = harness.gateway.prompts.last()
            assertEquals("session-2", retried.sessionId)
            assertTrue("重建后的首轮应带上初始化提示词", retried.text.startsWith("[团队初始化]"))
            assertTrue(retried.text.contains("你好"))

            val messages = harness.messages(conversationId)
            assertEquals("重试复用同一条流式消息，不应多出脏消息", 2, messages.size)
            assertEquals(MessageStatus.DONE, harness.botMessages(conversationId).single().status)
            assertEquals("重试成功", harness.botMessages(conversationId).single().text)
        } finally {
            harness.close()
        }
    }

    @Test
    fun notFoundOnPromptRebuildsSessionAndRetriesOnce() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            harness.gateway.promptHandler = { sessionId, text ->
                if (sessionId == "session-1" && !text.startsWith("[团队初始化]")) {
                    Result.failure(DshException("session/not-found", "会话不存在"))
                } else {
                    Result.success(Unit)
                }
            }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "你好").orFail()

            assertEquals(2, harness.gateway.createdSessionCwds.size)
            assertEquals("session-2", harness.engine.state.value.bots.single().sessionId)
            assertEquals(MessageStatus.DONE, harness.botMessages(conversationId).single().status)
        } finally {
            harness.close()
        }
    }

    @Test
    fun stopCancelsRunningTurnAndMarksInterrupted() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = { Feeds.hangingTurn(partial = "正在输入") }
            val conversationId = ConversationIds.bot(bot.id)

            val sendDeferred = async { harness.engine.send(conversationId, "你好") }
            advanceUntilIdle()

            // 生成中：流式消息 + workingBotIds 实时可见。
            assertEquals(MessageStatus.STREAMING, harness.botMessages(conversationId).single().status)
            assertEquals(setOf(bot.id), harness.engine.conversation(conversationId).value.workingBotIds)

            harness.engine.stop(conversationId).orFail()
            val result = sendDeferred.await()

            result.orFail()
            assertEquals(MessageStatus.INTERRUPTED, harness.botMessages(conversationId).single().status)
            assertTrue(harness.engine.conversation(conversationId).value.workingBotIds.isEmpty())
            assertTrue("stop 应让主机取消 turn", harness.gateway.cancelledTurns.contains("session-1"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun ignoresPreviousTurnEventsBeforeThisTurnsUserEcho() = runTest {
        // 现实竞态：createBot 的角色初始化 turn 还没结束时用户就发了首条消息。
        // follow 会先收到 init 轮的事件，引擎必须无视它们，只折叠本轮回显之后的内容。
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            harness.gateway.prependUserEcho = false
            harness.gateway.followHandler = {
                flow {
                    // 旧 turn（init 轮）的事件。
                    emit(SessionFeed.Snapshot(cursor = 1, assistantRevision = 0, running = true, records = emptyList()))
                    emit(SessionFeed.Stream(StreamFrame.Start(revision = 1, index = 0)))
                    emit(SessionFeed.Stream(StreamFrame.Delta(revision = 2, index = 1, chunkIndex = 0, kind = "text", text = "初始化回复")))
                    emit(SessionFeed.Event(Feeds.assistantMessage("初始化回复")))
                    emit(SessionFeed.Event(Feeds.turnEnd("completed")))
                    // 本轮开始：用户消息回显后才算数。
                    emit(SessionFeed.Event(Feeds.userMessageEvent("你好")))
                    emit(SessionFeed.Stream(StreamFrame.Start(revision = 3, index = 2)))
                    emit(SessionFeed.Stream(StreamFrame.Delta(revision = 4, index = 3, chunkIndex = 0, kind = "text", text = "真正的回答")))
                    emit(SessionFeed.Event(Feeds.assistantMessage("真正的回答")))
                    emit(SessionFeed.Event(Feeds.turnEnd("completed")))
                }
            }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "你好").orFail()

            val reply = harness.botMessages(conversationId).single()
            assertEquals(MessageStatus.DONE, reply.status)
            assertEquals("真正的回答", reply.text)
        } finally {
            harness.close()
        }
    }

    @Test
    fun armsFromSnapshotRecordsWhenPromptAlreadyStarted() = runTest {
        // prompt 已经开始甚至跑完，相关记录只出现在 snapshot.records 里（没有实时回显）。
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            harness.gateway.prependUserEcho = false
            harness.gateway.followHandler = {
                flow {
                    emit(
                        SessionFeed.Snapshot(
                            cursor = 1,
                            assistantRevision = 0,
                            running = false,
                            records = listOf(
                                Feeds.userMessageEvent("你好"),
                                Feeds.assistantMessage("快回复"),
                                Feeds.turnEnd("completed"),
                            ),
                        ),
                    )
                    emit(SessionFeed.Ended())
                }
            }
            val conversationId = ConversationIds.bot(bot.id)

            harness.engine.send(conversationId, "你好").orFail()

            val reply = harness.botMessages(conversationId).single()
            assertEquals(MessageStatus.DONE, reply.status)
            assertEquals("快回复", reply.text)
        } finally {
            harness.close()
        }
    }

    @Test
    fun conversationFlowIsAlwaysAvailableEvenForUnknownIds() = runTest {
        val harness = newHarness()
        try {
            val botConversation = harness.engine.conversation("bot:missing").value
            assertEquals(ConversationKind.BOT, botConversation.kind)
            assertTrue(botConversation.messages.isEmpty())

            val groupConversation = harness.engine.conversation("group:missing").value
            assertEquals(ConversationKind.GROUP, groupConversation.kind)
        } finally {
            harness.close()
        }
    }
}
