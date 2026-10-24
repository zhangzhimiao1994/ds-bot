package com.dsbot.mobile.bots.internal

import com.dsbot.mobile.bots.BotProfile

/**
 * 群聊里的 `@成员名` 解析（owner: android-core）。
 *
 * 规则（针对真实的中文聊天习惯）：
 * - 只匹配**群成员的名字**，`@` 后面必须是某个成员的完整名字；
 * - 名字后面必须是非字母数字（空格、中文标点、换行、结尾都算边界），
 *   所以 `@代码匠，帮我看看` 能命中「代码匠」，而 `@代码匠心` 不会命中「代码匠」；
 * - 支持长名字优先（「小闪」与「小闪2」同时存在时不误判），重叠的匹配只取更长者；
 * - 同一成员被 @ 多次只返回一次，结果按在文本中出现的顺序排列。
 */
internal object MentionParser {

    /** 返回 [text] 中按出现顺序被 @ 到的成员（已去重、已处理重叠前缀）。 */
    fun mentionsIn(text: String, candidates: List<BotProfile>): List<BotProfile> {
        if (text.isEmpty() || candidates.isEmpty()) return emptyList()
        data class Hit(val start: Int, val end: Int, val bot: BotProfile)

        val hits = mutableListOf<Hit>()
        candidates.sortedByDescending { it.name.length }.forEach { bot ->
            val name = bot.name.trim()
            if (name.isEmpty()) return@forEach
            val needle = "@$name"
            var from = 0
            while (from <= text.length - needle.length) {
                val start = text.indexOf(needle, from)
                if (start < 0) break
                val end = start + needle.length
                if (isBoundary(text, end)) hits += Hit(start, end, bot)
                from = start + 1
            }
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))

        val result = mutableListOf<BotProfile>()
        var cursor = 0
        for (hit in hits) {
            if (hit.start < cursor) continue
            if (result.none { it.id == hit.bot.id }) result += hit.bot
            cursor = hit.end
        }
        return result
    }

    /** `@名字` 之后是否到了文本边界（空格/标点/换行/结尾都算）。 */
    private fun isBoundary(text: String, index: Int): Boolean {
        if (index >= text.length) return true
        val next = text[index]
        return !(next.isLetterOrDigit() || next == '_')
    }
}
