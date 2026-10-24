package com.dsbot.mobile.bots

import com.dsbot.mobile.bots.internal.MentionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@成员名` 解析单测：中文名、中文标点边界、长名优先、多 @ 顺序、去重、误匹配防护。
 */
class MentionParserTest {

    private fun bot(id: String, name: String) =
        BotProfile(id = id, name = name, emoji = "🤖", persona = "角色")

    private val codeBot = bot("b1", "代码匠")
    private val researcher = bot("b2", "研究员")
    private val writer = bot("b3", "笔杆子")

    private fun names(text: String, members: List<BotProfile> = listOf(codeBot, researcher, writer)): List<String> =
        MentionParser.mentionsIn(text, members).map { it.name }

    @Test
    fun matchesChineseNameFollowedByChinesePunctuation() {
        assertEquals(listOf("代码匠"), names("@代码匠，帮我看看这个构建错误。"))
    }

    @Test
    fun matchesNameAtEndOfText() {
        assertEquals(listOf("代码匠"), names("这个问题请 @代码匠"))
    }

    @Test
    fun matchesNameFollowedBySpaceNewlineAndAsciiPunctuation() {
        assertEquals(listOf("代码匠"), names("@代码匠 看下"))
        assertEquals(listOf("代码匠"), names("麻烦 @代码匠\n再确认一次"))
        assertEquals(listOf("代码匠", "研究员"), names("@代码匠, @研究员!"))
    }

    @Test
    fun doesNotMatchLongerWordWithSamePrefix() {
        // 「代码匠心」不是成员「代码匠」+ 边界，不应误触发。
        assertEquals(emptyList<String>(), names("@代码匠心 你好"))
        // 但名字后接中文标点/空格要命中。
        assertEquals(listOf("代码匠"), names("@代码匠：你好"))
    }

    @Test
    fun prefersLongestNameWhenNamesSharePrefix() {
        val short = bot("s1", "小闪")
        val long = bot("s2", "小闪2")
        val members = listOf(short, long)
        assertEquals(listOf("小闪2"), MentionParser.mentionsIn("@小闪2 你来", members).map { it.name })
        assertEquals(listOf("小闪"), MentionParser.mentionsIn("@小闪 你来", members).map { it.name })
    }

    @Test
    fun returnsMentionsInTextOrderAndDeduplicates() {
        assertEquals(listOf("笔杆子", "研究员"), names("@笔杆子 先写，然后 @研究员 复核"))
        assertEquals(listOf("研究员"), names("@研究员 和 @研究员 都看下"))
    }

    @Test
    fun ignoresUnknownNamesAndPlainText() {
        assertEquals(emptyList<String>(), names("大家好，今天做什么？"))
        assertEquals(emptyList<String>(), names("@不存在的人 你好"))
    }

    @Test
    fun onlyMatchesGroupMembers() {
        // bot 不在成员列表里：解析不到。
        val outsider = bot("b9", "代码匠")
        assertEquals(emptyList<String>(), names("@代码匠 你好", members = listOf(researcher, outsider.copy(name = "别人"))))
        assertTrue(MentionParser.mentionsIn("@代码匠 你好", emptyList()).isEmpty())
    }
}
