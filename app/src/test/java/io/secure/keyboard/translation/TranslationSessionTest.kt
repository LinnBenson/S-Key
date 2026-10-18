package io.secure.keyboard.translation
import org.junit.Assert.*
import org.junit.Test
class TranslationSessionTest {
    @Test fun editsRejectStaleResultsAndDoNotSplitEmoji() {
        val s = TranslationSession(); s.enabled = true; s.edit("a😀")
        val token = s.begin(); s.backspace(); s.complete(token, "stale")
        assertEquals("a", s.text); assertEquals("", s.result); assertFalse(s.busy)
    }
    @Test fun closeRevokesResultAndErasesDraft() {
        val s = TranslationSession(); s.enabled = true; s.edit("hello"); val token = s.begin()
        s.close(); s.enabled = true; s.complete(token, "stale")
        assertEquals("", s.result); assertEquals("", s.text)
    }
    @Test fun inputLimitAndCurrentResponse() {
        val s = TranslationSession(); s.enabled = true
        assertFalse(s.edit("x".repeat(5001))); assertEquals("",s.text)
        s.edit("hello"); val token = s.begin(); s.complete(token,"你好")
        assertEquals("你好", s.result); assertFalse(s.busy)
    }
}
