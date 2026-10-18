package io.secure.keyboard
import org.junit.Assert.*
import org.junit.Test
class ChineseSpacingTest {
    @Test fun mixedWordsAndNumbers() {
        assertEquals("中 aa 文 123 测试", ChineseSpacing.format("中aa文123测试").text)
        assertEquals("中 aa 文", ChineseSpacing.format("中 aa 文").text)
        assertEquals("中文，abc123。", ChineseSpacing.format("中文，abc123。").text)
    }
    @Test fun boundariesAndSupplementaryHan() {
        assertEquals(" aa ", ChineseSpacing.format("aa", "中", "文").text)
        assertTrue(ChineseSpacing.format("aa", "中", "文").trailingSpace)
        assertEquals("b", ChineseSpacing.format("b", "a", " ").text)
        assertEquals("𠀀 abc", ChineseSpacing.format("𠀀abc").text)
        assertEquals("😀abc", ChineseSpacing.format("😀abc").text)
    }
}
