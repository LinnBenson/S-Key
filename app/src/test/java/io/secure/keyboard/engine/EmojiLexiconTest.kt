package io.secure.keyboard.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmojiLexiconTest {
    private fun lexicon() = EmojiLexicon(File("src/main/assets/dictionaries/emoji_keywords.tsv").reader())

    @Test fun approvedInventoryAndNumerals() {
        val rows = File("src/main/assets/dictionaries/emoji_keywords.tsv").readLines()
        assertEquals(109, rows.size)
        val lex = lexicon()
        listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十").zip(
            listOf("零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖", "拾")
        ).forEachIndexed { i, (ordinary, capital) ->
            val emoji = if (i == 10) "🔟" else "$i\uFE0F\u20E3"
            assertEquals(listOf(ordinary, emoji), lex.appendTo(listOf(ordinary)))
            assertEquals(listOf(capital, emoji), lex.appendTo(listOf(capital)))
        }
    }

    @Test fun exactMatchesTextFirstAndDeduplicated() {
        val lex = lexicon()
        assertEquals(listOf("开心", "高兴", "其他", "😀"), lex.appendTo(listOf("开心", "高兴", "其他")))
        assertEquals(listOf("无语", "😑", "😓"), lex.appendTo(listOf("无语")))
        assertEquals(listOf("赞", "👍"), lex.appendTo(listOf("赞")))
        assertEquals(listOf("不开心"), lex.appendTo(listOf("不开心")))
        assertTrue(lex.appendTo(emptyList()).isEmpty())
    }

    @Test fun onlyFirstThreeTriggerAndEmojiStartAtFourth() {
        val lex = lexicon()
        assertEquals(listOf("文字甲", "文字乙", "文字丙", "开心"),
            lex.appendTo(listOf("文字甲", "文字乙", "文字丙", "开心")))
        assertEquals(listOf("文字甲", "文字乙", "开心", "😀", "尾词"),
            lex.appendTo(listOf("文字甲", "文字乙", "开心", "尾词")))
        assertEquals(listOf("开心", "赞", "高兴", "😀", "👍", "无语", "尾词"),
            lex.appendTo(listOf("开心", "赞", "高兴", "无语", "尾词")))
        assertEquals(listOf("无语", "猴", "文字", "😑", "😓", "🐒", "🐵", "尾词"),
            lex.appendTo(listOf("无语", "猴", "文字", "尾词")))
        assertEquals(listOf("文字", "赞", "👍"), lex.appendTo(listOf("文字", "赞")))
    }

    @Test fun wubiAndPinyinShareTextMatchingWithoutChangingAutoCommit() {
        val engine = DictionaryEngine("---\n...\n开心\tgany\t100\n".reader(),
            "---\n...\n开心\tkai xin\t100\n".reader())
        val lex = lexicon()
        for (code in listOf("gany", "kaixin")) {
            val words = engine.candidates(code, false).map { it.text }
            assertEquals("开心", words.first())
            assertEquals("😀", lex.appendTo(words).last())
            assertEquals(words.first(), lex.appendTo(words).first())
        }
        assertEquals(listOf("开心"), engine.exactWubi("gany").map { it.text })
    }
}
