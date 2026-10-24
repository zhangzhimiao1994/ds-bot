package com.dsbot.mobile.bots

import com.dsbot.mobile.bots.internal.BotStore
import com.dsbot.mobile.bots.internal.MAX_MESSAGES_PER_CONVERSATION
import com.dsbot.mobile.core.ModelSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BotStore 单测：JSON 往返、400 条上限、原子写、损坏恢复、防抖合并。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class StoreFixture(val store: BotStore, val scope: CoroutineScope, val dir: File)

    private fun TestScope.newFixture(dirName: String = "botteam"): StoreFixture {
        val dir = File(tmp.root, dirName)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = BotStore(
            dir = dir,
            scope = scope,
            debounceMillis = 0L,
            ioDispatcher = dispatcher,
        )
        return StoreFixture(store, scope, dir)
    }

    private fun richSnapshot(): BotStore.Snapshot {
        val model = ModelSelection(provider = "deepseek-official", model = "deepseek-flash", reasoningEffort = "max")
        val bot = BotProfile(
            id = "bot-1",
            name = "代码匠",
            emoji = "🛠",
            colorIndex = 2,
            persona = "你是资深软件工程师",
            model = model,
            cwd = "E:\\work",
            sessionId = "session-1",
            isMain = true,
            createdAtMillis = 111L,
            lastActiveAtMillis = 222L,
        )
        val group = BotGroup(
            id = "group-1",
            name = "开发组",
            emoji = "👥",
            memberIds = listOf("bot-1"),
            adminBotId = "bot-1",
            createdAtMillis = 333L,
        )
        val message = TeamMessage(
            id = "m-1",
            conversationId = ConversationIds.bot("bot-1"),
            authorKind = AuthorKind.BOT,
            authorBotId = "bot-1",
            authorName = "代码匠",
            authorEmoji = "🛠",
            authorColorIndex = 2,
            text = "你好",
            reasoning = "先想一下",
            tools = listOf(ToolTrace(callId = "call-1", name = "pwsh", status = "SUCCESS", summary = "输出")),
            status = MessageStatus.DONE,
            error = null,
            timeMillis = 444L,
            fromMention = true,
        )
        return BotStore.Snapshot(
            bots = listOf(bot),
            groups = listOf(group),
            transcripts = mapOf(ConversationIds.bot("bot-1") to listOf(message)),
        )
    }

    @Test
    fun saveAndLoadRoundTripKeepsEveryField() = runTest {
        val fixture = newFixture()
        try {
            fixture.store.scheduleSave(richSnapshot())
            fixture.store.flush()
            assertTrue("应写出 team.json", File(fixture.dir, BotStore.FILE_NAME).exists())

            val loaded = fixture.store.load()
            assertNull("正常文件不应有告警", loaded.warning)
            assertEquals(1, loaded.snapshot.bots.size)
            val bot = loaded.snapshot.bots.single()
            assertEquals("代码匠", bot.name)
            assertEquals("🛠", bot.emoji)
            assertEquals(2, bot.colorIndex)
            assertEquals("你是资深软件工程师", bot.persona)
            assertEquals(ModelSelection("deepseek-official", "deepseek-flash", "max"), bot.model)
            assertEquals("E:\\work", bot.cwd)
            assertEquals("session-1", bot.sessionId)
            assertTrue(bot.isMain)
            assertEquals(111L, bot.createdAtMillis)
            assertEquals(222L, bot.lastActiveAtMillis)

            val group = loaded.snapshot.groups.single()
            assertEquals("开发组", group.name)
            assertEquals(listOf("bot-1"), group.memberIds)
            assertEquals("bot-1", group.adminBotId)

            val message = loaded.snapshot.transcripts.getValue(ConversationIds.bot("bot-1")).single()
            assertEquals(AuthorKind.BOT, message.authorKind)
            assertEquals("你好", message.text)
            assertEquals("先想一下", message.reasoning)
            assertEquals(1, message.tools.size)
            assertEquals("pwsh", message.tools.single().name)
            assertEquals(MessageStatus.DONE, message.status)
            assertTrue(message.fromMention)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun loadWithoutFileReturnsEmptyTeam() = runTest {
        val fixture = newFixture()
        try {
            val loaded = fixture.store.load()
            assertTrue(loaded.snapshot.bots.isEmpty())
            assertTrue(loaded.snapshot.groups.isEmpty())
            assertTrue(loaded.snapshot.transcripts.isEmpty())
            assertNull(loaded.warning)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun capsMessagesAt400LastWins() = runTest {
        val fixture = newFixture()
        try {
            val conversationId = ConversationIds.bot("bot-1")
            val messages = (0 until MAX_MESSAGES_PER_CONVERSATION + 50).map { index ->
                TeamMessage(
                    id = "m-$index",
                    conversationId = conversationId,
                    authorKind = AuthorKind.USER,
                    text = "第 $index 条",
                    timeMillis = index.toLong(),
                )
            }
            fixture.store.scheduleSave(BotStore.Snapshot(transcripts = mapOf(conversationId to messages)))
            fixture.store.flush()

            val loaded = fixture.store.load()
            val kept = loaded.snapshot.transcripts.getValue(conversationId)
            assertEquals(MAX_MESSAGES_PER_CONVERSATION, kept.size)
            assertEquals("m-50", kept.first().id)
            assertEquals("m-449", kept.last().id)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun debouncedSavesCoalesceToLatestSnapshot() = runTest {
        val fixture = newFixture()
        try {
            val conversationId = "bot:bot-1"
            fixture.store.scheduleSave(
                BotStore.Snapshot(
                    transcripts = mapOf(
                        conversationId to listOf(
                            TeamMessage(
                                id = "old",
                                conversationId = conversationId,
                                authorKind = AuthorKind.USER,
                                text = "旧",
                            ),
                        ),
                    ),
                ),
            )
            fixture.store.scheduleSave(
                BotStore.Snapshot(
                    transcripts = mapOf(
                        conversationId to listOf(
                            TeamMessage(
                                id = "new",
                                conversationId = conversationId,
                                authorKind = AuthorKind.USER,
                                text = "新",
                            ),
                        ),
                    ),
                ),
            )
            fixture.store.flush()
            assertEquals("new", fixture.store.load().snapshot.transcripts.getValue(conversationId).single().id)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun corruptJsonIsBackedUpAndTeamStartsEmpty() = runTest {
        val fixture = newFixture()
        try {
            fixture.dir.mkdirs()
            val target = File(fixture.dir, BotStore.FILE_NAME)
            target.writeText("{ 这不是合法 JSON ", Charsets.UTF_8)

            val loaded = fixture.store.load()
            assertTrue("应以空团队启动", loaded.snapshot.bots.isEmpty())
            assertNotNull("应返回中文告警", loaded.warning)
            assertTrue(loaded.warning!!.contains("损坏"))
            assertTrue("坏文件应备份为 .bak", File(fixture.dir, BotStore.FILE_NAME + BotStore.BACKUP_SUFFIX).exists())
            assertFalse("坏文件应被移走", target.exists())
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun emptyFileIsRecoveredAsEmptyTeam() = runTest {
        val fixture = newFixture()
        try {
            fixture.dir.mkdirs()
            File(fixture.dir, BotStore.FILE_NAME).writeText("", Charsets.UTF_8)
            val loaded = fixture.store.load()
            assertTrue(loaded.snapshot.bots.isEmpty())
            assertNotNull(loaded.warning)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun atomicWriteLeavesNoTempFile() = runTest {
        val fixture = newFixture()
        try {
            fixture.store.scheduleSave(richSnapshot())
            fixture.store.flush()
            val names = fixture.dir.listFiles()?.map { it.name }.orEmpty()
            assertTrue(names.contains(BotStore.FILE_NAME))
            assertFalse("不应残留临时文件", names.contains(BotStore.FILE_NAME + BotStore.TEMP_SUFFIX))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun flushNowWritesPendingSnapshotSynchronously() = runTest {
        val fixture = newFixture()
        try {
            fixture.store.scheduleSave(richSnapshot())
            // 不挂起、不推进 test scheduler，直接同步落盘：模拟 close() 的最后一次写盘。
            fixture.store.flushNow()
            val loaded = fixture.store.load()
            assertEquals(1, loaded.snapshot.bots.size)
        } finally {
            fixture.scope.cancel()
        }
    }
}
