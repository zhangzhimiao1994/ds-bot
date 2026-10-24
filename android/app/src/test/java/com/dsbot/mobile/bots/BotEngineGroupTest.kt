package com.dsbot.mobile.bots

import com.dsbot.mobile.bots.internal.BotStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 群聊编排单测：@ 定向、管理员优先、隐藏前缀、@ 接力（≤2 跳 / ≤4 条）、
 * 单人失败不阻断、缺失成员/管理员降级提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotEngineGroupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "botteam")

    private suspend fun TestScope.newHarness(): BotHarness = createHarness(dir()).loaded()

    private suspend fun TestScope.newHarnessFrom(dir: File): BotHarness = createHarness(dir).loaded()

    @Test
    fun directMentionDispatchesOnlyMentionedBotWithHiddenPrefix() = runTest {
        val harness = newHarness()
        try {
            val codeBot = harness.createBot("代码匠", "🛠")
            val researcher = harness.createBot("研究员", "🔎")
            val writer = harness.createBot("笔杆子", "✍️")
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val group = harness.createGroup("开发组", listOf(codeBot.id, researcher.id, writer.id))
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "@研究员 帮我看下，谢谢").orFail()

            // 只派发给被 @ 的成员。
            val replies = harness.botMessages(conversationId)
            assertEquals(1, replies.size)
            assertEquals(researcher.id, replies.single().authorBotId)
            assertFalse(replies.single().fromMention)
            assertEquals(1, harness.gateway.userPrompts.size)
            val prompt = harness.gateway.userPrompts.single()
            assertEquals("session-2", prompt.sessionId)
            // 隐藏前缀 + 原文（成员顺序与群成员列表一致）。
            assertEquals(
                "[群聊「开发组」｜成员：代码匠、研究员、笔杆子｜你是 研究员]\n@研究员 帮我看下，谢谢",
                prompt.text,
            )
            // 用户消息按原文落到本地时间线。
            val userMessage = harness.messages(conversationId).first()
            assertEquals(AuthorKind.USER, userMessage.authorKind)
            assertEquals("@研究员 帮我看下，谢谢", userMessage.text)
        } finally {
            harness.close()
        }
    }

    @Test
    fun explicitMentionBeatsAdminPriority() = runTest {
        val harness = newHarness()
        try {
            val admin = harness.createBot("小闪", "⚡")
            val worker = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val group = harness.createGroup("开发组", listOf(admin.id, worker.id), adminBotId = admin.id)
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "@代码匠 你来做").orFail()

            val replies = harness.botMessages(conversationId)
            assertEquals(1, replies.size)
            assertEquals(worker.id, replies.single().authorBotId)
        } finally {
            harness.close()
        }
    }

    @Test
    fun withoutMentionAdminAnswersOnly() = runTest {
        val harness = newHarness()
        try {
            val admin = harness.createBot("小闪", "⚡")
            val worker = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val group = harness.createGroup("开发组", listOf(admin.id, worker.id), adminBotId = admin.id)
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "大家好").orFail()

            val replies = harness.botMessages(conversationId)
            assertEquals(1, replies.size)
            assertEquals(admin.id, replies.single().authorBotId)
            assertEquals("session-1", harness.gateway.userPrompts.single().sessionId)
        } finally {
            harness.close()
        }
    }

    @Test
    fun fullGroupWithoutMentionAllMembersAnswerInOrder() = runTest {
        val harness = newHarness()
        try {
            val first = harness.createBot("小闪", "⚡")
            val second = harness.createBot("代码匠", "🛠")
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val group = harness.createGroup("开发组", listOf(first.id, second.id))
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "大家好").orFail()

            val replies = harness.botMessages(conversationId)
            assertEquals(2, replies.size)
            assertEquals(listOf(first.id, second.id), replies.map { it.authorBotId })
            assertEquals(2, harness.gateway.userPrompts.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun mentionRelayFansOutToMentionedMembers() = runTest {
        val harness = newHarness()
        try {
            val admin = harness.createBot("代码匠", "🛠")
            val researcher = harness.createBot("研究员", "🔎")
            val writer = harness.createBot("笔杆子", "✍️")
            harness.gateway.followHandler = { sessionId ->
                when (sessionId) {
                    "session-1" -> Feeds.completedTurn("我让 @研究员 和 @笔杆子 一起看")
                    else -> Feeds.completedTurn("收到")
                }
            }
            val group = harness.createGroup(
                "开发组",
                listOf(admin.id, researcher.id, writer.id),
                adminBotId = admin.id,
            )
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "开始吧").orFail()

            val replies = harness.botMessages(conversationId)
            assertEquals(3, replies.size)
            assertEquals(listOf(admin.id, researcher.id, writer.id), replies.map { it.authorBotId })
            assertFalse(replies[0].fromMention)
            assertTrue("接力消息应标记 fromMention", replies[1].fromMention)
            assertTrue(replies[2].fromMention)

            // 接力 prompt 里带上了来源消息与群前缀。
            val relayPrompt = harness.gateway.userPrompts[1]
            assertTrue(relayPrompt.text.startsWith("[群聊「开发组」｜成员：代码匠、研究员、笔杆子｜你是 研究员]"))
            assertTrue(relayPrompt.text.contains("「代码匠」在群聊里 @了你"))
            assertTrue(relayPrompt.text.contains("我让 @研究员 和 @笔杆子 一起看"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun mentionRelayStopsAfterTwoHops() = runTest {
        val harness = newHarness()
        try {
            val a = harness.createBot("小闪", "⚡")
            val b = harness.createBot("代码匠", "🛠")
            val c = harness.createBot("研究员", "🔎")
            val d = harness.createBot("笔杆子", "✍️")
            harness.gateway.followHandler = { sessionId ->
                when (sessionId) {
                    "session-1" -> Feeds.completedTurn("@代码匠 你来做")
                    "session-2" -> Feeds.completedTurn("@研究员 继续")
                    "session-3" -> Feeds.completedTurn("@笔杆子 收尾")
                    else -> Feeds.completedTurn("完成")
                }
            }
            val group = harness.createGroup(
                "开发组",
                listOf(a.id, b.id, c.id, d.id),
                adminBotId = a.id,
            )
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "开始").orFail()

            // 初始(跳 0) → 1 跳 → 2 跳；第 3 跳必须被截断。
            val replies = harness.botMessages(conversationId)
            assertEquals(3, replies.size)
            assertEquals(listOf(a.id, b.id, c.id), replies.map { it.authorBotId })
            assertTrue("第 4 个机器人不应被第 3 跳唤醒", harness.gateway.userPrompts.none { it.sessionId == "session-4" })
        } finally {
            harness.close()
        }
    }

    @Test
    fun botMessageCapIsFourPerTurn() = runTest {
        val harness = newHarness()
        try {
            val bots = (1..5).map { index -> harness.createBot("成员$index", "🤖") }
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            val group = harness.createGroup("大群", bots.map { it.id })
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "大家好").orFail()

            assertEquals(4, harness.botMessages(conversationId).size)
            assertEquals(4, harness.gateway.userPrompts.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun singleMemberFailureDoesNotBlockOthersAndLeavesSystemHint() = runTest {
        val harness = newHarness()
        try {
            val failing = harness.createBot("代码匠", "🛠")
            val healthy = harness.createBot("研究员", "🔎")
            harness.gateway.followHandler = { sessionId ->
                when (sessionId) {
                    "session-1" -> Feeds.erroredTurn()
                    else -> Feeds.completedTurn("我没事")
                }
            }
            val group = harness.createGroup("开发组", listOf(failing.id, healthy.id))
            val conversationId = ConversationIds.group(group.id)

            harness.engine.send(conversationId, "大家好").orFail()

            val replies = harness.botMessages(conversationId)
            assertEquals(2, replies.size)
            assertEquals(MessageStatus.ERROR, replies[0].status)
            assertEquals(MessageStatus.DONE, replies[1].status)
            assertEquals("我没事", replies[1].text)
            val system = harness.systemMessages(conversationId)
            assertEquals(1, system.size)
            assertTrue(system.single().text.contains("「代码匠」本轮未完成"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun deletedMemberAndMissingAdminAreSkippedWithSystemHint() = runTest {
        val first = newHarness()
        val firstDir = first.dir
        val adminHintConversation = ConversationIds.group("legacy-group")
        try {
            val alive = first.createBot("小闪", "⚡")
            // 手动构造一份“历史遗留”盘面：群引用已删除的 ghost 成员，管理员也是 ghost。
            val legacyGroup = BotGroup(
                id = "legacy-group",
                name = "遗留群",
                emoji = "👥",
                memberIds = listOf(alive.id, "ghost-bot"),
                adminBotId = "ghost-bot",
                createdAtMillis = 1L,
            )
            first.store.scheduleSave(
                BotStore.Snapshot(bots = first.engine.state.value.bots, groups = listOf(legacyGroup)),
            )
            first.store.flush()
        } finally {
            first.close()
        }

        val harness = newHarnessFrom(firstDir)
        try {
            harness.gateway.followHandler = { Feeds.completedTurn("收到") }
            harness.engine.send(adminHintConversation, "大家好").orFail()

            val systems = harness.systemMessages(adminHintConversation)
            assertTrue(
                "缺失成员应落 SYSTEM 提示",
                systems.any { it.text.contains("有成员已不在团队") && it.text.contains("ghost-bot") },
            )
            assertTrue(
                "管理员不在群里应提醒并降级",
                systems.any { it.text.contains("管理员已不在群里") },
            )
            // 仍在群里的成员正常回复。
            assertEquals(1, harness.botMessages(adminHintConversation).size)
        } finally {
            harness.close()
        }
    }
}
