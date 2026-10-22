package io.secure.keyboard.engine

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class PinyinFuzzyEmojiTest {
    @Test fun fuzzyPhrasesAndExplicitBoundariesRespectSwitches() {
        val engine = DictionaryEngine(StringReader("...\n"), StringReader("...\n中国\tzhong guo\t100\n"))
        for (raw in listOf("zongguo", "zong'guo")) {
            assertTrue(engine.decodePinyin(raw,"",emptyList(),true,setOf("zh_z")).any { it.text=="中国" && it.consumed==raw.length })
            assertFalse(engine.decodePinyin(raw,"",emptyList(),false,setOf("zh_z")).any { it.text=="中国" })
            assertFalse(engine.decodePinyin(raw,"",emptyList(),true,setOf("ch_c")).any { it.text=="中国" })
        }
        assertFalse(engine.decodePinyin("zo'ngguo","",emptyList(),true,setOf("zh_z")).any { it.text=="中国" })
    }

    @Test fun fuzzyChoicesRemainReachableAfterManyExactHomophones() {
        val rows=(1..30).joinToString("\n") { "精确$it\tza\t1000" } + "\n模糊\tzha\t10\n"
        val engine=DictionaryEngine(StringReader("...\n"),StringReader("...\n"+rows))
        assertTrue(engine.decodePinyin("za","",emptyList(),true,setOf("zh_z")).any { it.text=="模糊" })
    }

    @Test fun everyFuzzyPairWorksInPinyinDecoder() {
        val pairs = mapOf("zh_z" to ("zha" to "za"),"ch_c" to ("cha" to "ca"),"sh_s" to ("sha" to "sa"),
            "n_l" to ("na" to "la"),"r_l" to ("ran" to "lan"),"f_h" to ("fa" to "ha"),
            "an_ang" to ("ban" to "bang"),"en_eng" to ("ben" to "beng"),"in_ing" to ("bin" to "bing"))
        for ((pair, forms) in pairs) for ((stored, typed) in listOf(forms, forms.second to forms.first)) {
            val engine=DictionaryEngine(StringReader("...\n"),StringReader("...\n目标\t$stored\t100\n"))
            assertTrue(pair,engine.decodePinyin(typed,"",emptyList(),true,setOf(pair)).any { it.text=="目标" && it.consumed==typed.length })
            assertFalse(pair,engine.decodePinyin(typed,"",emptyList(),false,setOf(pair)).any { it.text=="目标" && it.consumed==typed.length })
        }
    }

    @Test fun emojiKeepsSourceConsumptionAndLeadingWords() {
        val lexicon=EmojiLexicon(StringReader("😀\t开心\t高兴\n🍎\t苹果\n"))
        val words=listOf(PinyinDecoder.Option("开心",6),PinyinDecoder.Option("苹果",4),PinyinDecoder.Option("高兴",7),PinyinDecoder.Option("更多",9))
        val result=lexicon.appendToOptions(words.asSequence()).toList()
        assertEquals(listOf("开心","苹果","高兴","😀","🍎","更多"),result.map { it.text })
        assertEquals(6,result.first { it.text=="😀" }.consumed)
        assertEquals(4,result.first { it.text=="🍎" }.consumed)
        assertEquals(0,lexicon.appendToOptions(sequenceOf(PinyinDecoder.Option("开心",0))).last().consumed)
    }
}
