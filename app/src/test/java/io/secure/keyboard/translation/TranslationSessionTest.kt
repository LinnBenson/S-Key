package io.secure.keyboard.translation
import org.junit.Assert.*
import org.junit.Test
class TranslationSessionTest {
    @Test fun draftEditingAndClearingDoesNotInvalidateStreamingResult() {
        val s=TranslationSession();s.enabled=true;s.edit("sent")
        val token=s.begin();s.partial(token,"partial")
        assertTrue(s.editDraft("next draft"));assertTrue(s.busy);assertEquals("partial",s.result)
        s.complete(token,"finished");assertEquals("next draft",s.text);assertEquals("finished",s.result)
        s.editDraft("");assertEquals("finished",s.result)
        assertFalse(s.editDraft("x".repeat(5001)));assertEquals("",s.text)
    }

    @Test fun streamingStaysBusyUntilCompleteAndRejectsLateChunks() {
        val s = TranslationSession(); s.enabled = true; s.edit("original")
        val token = s.begin(); s.partial(token, "你"); assertTrue(s.busy)
        s.partial(token, "你好"); assertEquals("你好", s.result); assertTrue(s.busy)
        s.complete(token, "你好！"); assertFalse(s.busy)
        s.partial(token, "late"); assertEquals("你好！", s.result)
        val next = s.begin(); s.close(); s.partial(next, "stale")
        assertEquals("", s.result)
    }

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
