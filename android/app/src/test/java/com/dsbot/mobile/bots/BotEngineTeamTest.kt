package com.dsbot.mobile.bots

import com.dsbot.mobile.bots.internal.BotStore
import com.dsbot.mobile.core.ApprovalDecision
import com.dsbot.mobile.core.ApprovalRequest
import com.dsbot.mobile.core.ModelSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 团队/持久化/审批相关单测：名单与群 CRUD、模型同步、清空、跨引擎重载、
 * 审批原样透传（docs/SECURITY.md §1）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotEngineTeamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "botteam")

    private suspend fun TestScope.newHarness(): BotHarness = createHarness(dir()).loaded()

    @Test
    fun createBotRejectsDuplicateName() = runTest {
        val harness = newHarness()
        try {
            harness.createBot("代码匠")
            val duplicate = harness.engine.createBot(BotDraft(name = "代码匠", persona = "另一个"))
            assertTrue(duplicate.isFailure)
            assertTrue(duplicate.exceptionOrNull()?.message.orEmpty().contains("同名"))
            assertEquals(1, harness.engine.state.value.bots.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun teamStateListsBotAndGroupSummaries() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            val group = harness.createGroup("开发组", listOf(bot.id), adminBotId = bot.id)

            val state = harness.engine.state.value
            assertTrue(state.loaded)
            assertEquals(1, state.bots.size)
            assertEquals(1, state.groups.size)
            assertEquals(2, state.summaries.size)
            assertEquals(ConversationIds.bot(bot.id), state.summaries[0].conversationId)
            assertEquals(ConversationKind.BOT, state.summaries[0].kind)
            assertEquals("代码匠", state.summaries[0].title)
            assertEquals(ConversationIds.group(group.id), state.summaries[1].conversationId)
            assertEquals(ConversationKind.GROUP, state.summaries[1].kind)
            assertEquals("开发组", state.summaries[1].title)
            assertEquals(listOf(bot.id), state.summaries[1].memberIds)
        } finally {
            harness.close()
        }
    }

    @Test
    fun deleteBotRemovesItFromGroupsAndDropsTranscript() = runTest {
        val harness = newHarness()
        try {
            val first = harness.createBot("小闪", "⚡")
            val second = harness.createBot("代码匠", "🛠")
            val group = harness.createGroup("开发组", listOf(first.id, second.id), adminBotId = second.id)
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            harness.engine.send(ConversationIds.bot(second.id), "你好").orFail()

            harness.engine.deleteBot(second.id).orFail()

            val state = harness.engine.state.value
            assertEquals(listOf(first.id), state.bots.map { it.id })
            val updatedGroup = state.group(group.id)!!
            assertEquals(listOf(first.id), updatedGroup.memberIds)
            assertNull("管理员被删除后应清空", updatedGroup.adminBotId)
            assertTrue(harness.messages(ConversationIds.bot(second.id)).isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun createGroupRejectsInvalidMembersAndAdmin() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")

            val missing = harness.engine.createGroup(
                GroupDraft(name = "开发组", memberIds = listOf(bot.id, "ghost")),
            )
            assertTrue(missing.isFailure)
            assertTrue(missing.exceptionOrNull()?.message.orEmpty().contains("群成员不存在"))

            val badAdmin = harness.engine.createGroup(
                GroupDraft(name = "开发组", memberIds = listOf(bot.id), adminBotId = "other"),
            )
            assertTrue(badAdmin.isFailure)
            assertTrue(badAdmin.exceptionOrNull()?.message.orEmpty().contains("管理员必须是群成员"))

            val noMember = harness.engine.createGroup(GroupDraft(name = "空群", memberIds = emptyList()))
            assertTrue(noMember.isFailure)
            assertTrue(noMember.exceptionOrNull()?.message.orEmpty().contains("至少需要一个成员"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun updateGroupReplacesRosterAndAdmin() = runTest {
        val harness = newHarness()
        try {
            val first = harness.createBot("小闪", "⚡")
            val second = harness.createBot("代码匠", "🛠")
            val group = harness.createGroup("开发组", listOf(first.id, second.id), adminBotId = first.id)

            val updated = harness.engine.updateGroup(
                group.copy(name = "新开发组", memberIds = listOf(second.id), adminBotId = second.id),
            ).orFail()

            assertEquals("新开发组", updated.name)
            assertEquals(listOf(second.id), harness.engine.state.value.group(group.id)!!.memberIds)
            assertEquals("新开发组", harness.engine.conversation(ConversationIds.group(group.id)).value.title)
        } finally {
            harness.close()
        }
    }

    @Test
    fun updateBotSyncsNameToHostSessionAndPersists() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠", "🛠")
            val renamed = bot.copy(name = "架构师", emoji = "🧱")

            val updated = harness.engine.updateBot(renamed).orFail()

            assertEquals("架构师", updated.name)
            assertTrue(
                "主机会话应同步改名",
                harness.gateway.renames.any { it.sessionId == "session-1" && it.title == "🧱 架构师" },
            )
            assertEquals("架构师", harness.engine.state.value.bots.single().name)
        } finally {
            harness.close()
        }
    }

    @Test
    fun setBotModelUpdatesHostAndPersists() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            val selection = ModelSelection("deepseek-official", "deepseek-pro", "high")

            harness.engine.setBotModel(bot.id, selection).orFail()

            assertEquals(selection, harness.engine.state.value.bots.single().model)
            assertTrue(
                harness.gateway.modelSelections.any { it.sessionId == "session-1" && it.selection == selection },
            )
            // 落盘后换一个引擎重新加载，模型偏好还在。
            val reloadDir = dir()
            harness.close()
            val reloaded = createHarness(reloadDir).loaded()
            try {
                assertEquals(selection, reloaded.engine.state.value.bots.single().model)
            } finally {
                reloaded.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun respondApprovalForwardsRequestVerbatim() = runTest {
        val harness = newHarness()
        try {
            val approval = ApprovalRequest(
                eventId = "evt-sec-1",
                agentId = "session-1",
                toolName = "pwsh",
                callId = "call-9",
                argumentsJson = "{\"command\":\"rm -rf build\"}",
                reason = "需要写入权限",
                displayReasonZh = "允许使用完全权限执行命令",
                fingerprint = "fingerprint-abc123",
                receivedAtMillis = 1L,
            )
            harness.gateway.approvals.value = listOf(approval)
            // 审批列表原样透传。
            assertEquals(listOf(approval), harness.engine.approvals.value)
            // 引擎绝不自动批准：创建/发送流程不应产生任何审批回传。
            assertTrue(harness.gateway.approvalResponses.isEmpty())

            harness.engine.respondApproval(approval, ApprovalDecision.ALLOW_ONCE).orFail()
            val (forwarded, decision) = harness.gateway.approvalResponses.single()
            assertSame("必须原样转发同一个请求实例", approval, forwarded)
            assertEquals("evt-sec-1", forwarded.eventId)
            assertEquals("fingerprint-abc123", forwarded.fingerprint)
            assertEquals(ApprovalDecision.ALLOW_ONCE, decision)

            harness.engine.respondApproval(approval.copy(eventId = "evt-sec-2"), ApprovalDecision.REJECT).orFail()
            assertEquals(ApprovalDecision.REJECT, harness.gateway.approvalResponses.last().second)
            assertEquals("evt-sec-2", harness.gateway.approvalResponses.last().first.eventId)
        } finally {
            harness.close()
        }
    }

    @Test
    fun clearRemovesLocalTranscriptAndStaysClearedAfterReload() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val conversationId = ConversationIds.bot(bot.id)
            harness.engine.send(conversationId, "你好").orFail()
            assertEquals(2, harness.messages(conversationId).size)

            harness.engine.clear(conversationId).orFail()
            assertTrue(harness.messages(conversationId).isEmpty())

            val reloadDir = dir()
            harness.close()
            val reloaded = createHarness(reloadDir).loaded()
            try {
                assertTrue(reloaded.messages(conversationId).isEmpty())
                assertEquals(1, reloaded.engine.state.value.bots.size)
            } finally {
                reloaded.close()
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun persistenceRoundTripAcrossEngineReload() = runTest {
        val harness = newHarness()
        lateinit var conversationId: String
        try {
            val bot = harness.createBot("代码匠", "🛠", persona = "你是工程师")
            harness.createGroup("开发组", listOf(bot.id), adminBotId = bot.id)
            harness.gateway.followHandler = { Feeds.completedTurn("落盘的回答") }
            conversationId = ConversationIds.bot(bot.id)
            harness.engine.send(conversationId, "你好").orFail()
            harness.store.flush()
            assertTrue(File(dir(), BotStore.FILE_NAME).exists())
        } finally {
            harness.close()
        }

        val reloaded = createHarness(dir()).loaded()
        try {
            val state = reloaded.engine.state.value
            assertEquals(1, state.bots.size)
            assertEquals("代码匠", state.bots.single().name)
            assertEquals("你是工程师", state.bots.single().persona)
            assertEquals(1, state.groups.size)
            assertEquals(2, reloaded.messages(conversationId).size)
            assertEquals(MessageStatus.DONE, reloaded.botMessages(conversationId).single().status)
            assertEquals("落盘的回答", reloaded.botMessages(conversationId).single().text)
        } finally {
            reloaded.close()
        }
    }

    @Test
    fun openConversationLoadsBoundSessionsAndRejectsUnknownTargets() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            val group = harness.createGroup("开发组", listOf(bot.id))
            val botConversationId = ConversationIds.bot(bot.id)
            harness.engine.openConversation(botConversationId).orFail()
            assertEquals(listOf("session-1"), harness.engine.conversation(botConversationId).value.boundSessionIds)
            assertTrue(harness.engine.conversation(botConversationId).value.messages.isEmpty())

            harness.engine.openConversation(ConversationIds.group(group.id)).orFail()
            assertEquals(listOf("session-1"), harness.engine.conversation(ConversationIds.group(group.id)).value.boundSessionIds)

            val unknownBot = harness.engine.openConversation("bot:missing")
            assertTrue(unknownBot.isFailure)
            assertTrue(unknownBot.exceptionOrNull()?.message.orEmpty().contains("找不到该机器人"))

            val badId = harness.engine.openConversation("whatever")
            assertTrue(badId.isFailure)
            assertTrue(badId.exceptionOrNull()?.message.orEmpty().contains("无法识别的会话"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun deleteGroupRemovesGroupAndTranscript() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            val group = harness.createGroup("开发组", listOf(bot.id))
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val conversationId = ConversationIds.group(group.id)
            harness.engine.send(conversationId, "大家好").orFail()

            harness.engine.deleteGroup(group.id).orFail()

            assertTrue(harness.engine.state.value.groups.isEmpty())
            assertTrue(harness.messages(conversationId).isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun closeIsIdempotentAndSendAfterCloseFailsWithChineseMessage() = runTest {
        val harness = newHarness()
        try {
            val bot = harness.createBot("代码匠")
            harness.close()
            harness.close()
            val result = harness.engine.send(ConversationIds.bot(bot.id), "你好")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("已关闭"))
        } finally {
            runCatching { harness.close() }
        }
    }
}
