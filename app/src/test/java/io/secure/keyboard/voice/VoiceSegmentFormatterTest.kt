package io.secure.keyboard.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSegmentFormatterTest {
    private fun format(text: String) = VoiceSegmentFormatter.formatFinalSegment(text)

    @Test fun emptyAndWhitespaceOnlySegmentsStayEmpty() {
        for (text in listOf("", " ", "\t\r\n", "\u3000\u00a0")) {
            assertEquals("", format(text))
        }
    }

    @Test fun plainTextGetsOneChineseCommaAfterOuterWhitespaceIsTrimmed() {
        assertEquals("你好，", format("你好"))
        assertEquals("hello，", format("hello"))
        assertEquals("第一行\n第二行，", format(" \t第一行\n第二行\r\n "))
    }

    @Test fun existingChineseAndAsciiPunctuationIsPreserved() {
        for (punctuation in listOf("，", "。", "！", "？", "；", "：", "、", "…", "—",
                ",", ".", "!", "?", ";", ":", "...", "-")) {
            assertEquals("你好$punctuation", format("你好$punctuation"))
            assertEquals("你好$punctuation", format(" 你好$punctuation \n"))
        }
    }

    @Test fun trailingQuotesAndBracketsRemainUnchanged() {
        for (text in listOf("“你好”", "“你好。”", "「你好」", "『你好！』", "\"hello\"",
                "'hello.'", "（你好）", "[hello]", "《你好》")) {
            assertEquals(text, format(text))
        }
    }

    @Test fun supplementaryCharactersAndEmojiRemainIntact() {
        assertEquals("𥝚，", format("𥝚"))
        assertEquals("你好😀，", format("你好😀"))
        assertEquals("👩‍🚀，", format("👩‍🚀"))
        assertEquals("𥝚。", format("𥝚。"))
    }

    @Test fun repeatedFormattingNeverAddsAnotherComma() {
        for (text in listOf("你好", "你好！", "“你好”", "𥝚", "")) {
            assertEquals(format(text), format(format(text)))
        }
    }

    @Test fun formattedUtf16LengthKeepsFollowingSegmentsAndIdentityDedupSafe() {
        val guard = VoiceCommitGuard(7, 0)
        val first = format("𥝚")
        assertEquals(3, first.length)
        assertEquals(VoiceCommitGuard.Decision.COMMIT, guard.reserve(0, first.length, 7, 0, 0))
        assertEquals(3, guard.expectedCursor)
        assertEquals(VoiceCommitGuard.Decision.IGNORE, guard.reserve(0, first.length, 7, 3, 3))
        val next = format("你好")
        assertEquals(VoiceCommitGuard.Decision.COMMIT, guard.reserve(1, next.length, 7, 3, 3))
        assertEquals(6, guard.expectedCursor)
    }
}
