package io.secure.keyboard.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TraditionalConverterTest {
    private val converter = TraditionalConverter(sequence {
        for (name in listOf("STCharacters.txt", "STPhrases.txt")) {
            File("src/main/assets/opencc/$name").useLines { yieldAll(it) }
        }
    })
    @Test fun convertsWordsWithContext() {
        assertEquals("頭髮發展，皇后後臺，乾杯幹活", converter.convert("头发发展，皇后后台，干杯干活"))
    }
    @Test fun preservesMixedTextAndLineBreaks() {
        assertEquals("Hello 123 😀\n漢字，繁體！", converter.convert("Hello 123 😀\n汉字，繁體！"))
        assertEquals("", converter.convert(""))
    }
    @Test fun longestPhraseWinsAndSupplementaryCharactersSurvive() {
        val custom = TraditionalConverter(sequenceOf("发\t發", "头发\t頭髮", "头发丝\t頭髮絲"))
        assertEquals("😀頭髮絲發𠀀", custom.convert("😀头发丝发𠀀"))
    }
}
