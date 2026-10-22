package io.secure.keyboard.engine

import org.junit.Assert.*
import org.junit.Test

class CustomDictionaryIndexTest {
    private data class Entry(val reading: String, val text: String)

    @Test fun indexedChineseMatchesKeepNormalizationAndSavedOrder() {
        val entries = listOf(Entry("nihao","前缀"),Entry(" n ' i ","精确"),Entry("nü","女"),Entry("nv'er","女儿"),Entry("other","其他"))
        val index = CustomDictionaryIndex(entries,{ it.reading },false)
        for (query in listOf("n","NI","n' i","NÜ","nv","notfound",""," ' ")) {
            val baseline = listOf("首选","内置")
            assertEquals(query,
                CustomCandidateRanking.merge(query,baseline,entries,{ it.reading },{ it.text }),
                CustomCandidateRanking.merge(query,baseline,index.matching(query),{ it.reading },{ it.text }))
        }
    }

    @Test fun englishMatchesPreserveCaseOutputsAndExcludeInvalidCodes() {
        val entries = listOf(Entry("Hello","Hi"),Entry("HE","您好"),Entry("he!","bad"),Entry("hero","Hero"),Entry("bye","bye"))
        val index = CustomDictionaryIndex(entries,{ it.reading },true)
        for (query in listOf("h","HE","Hello","he!","", "x")) {
            assertEquals(query,
                EnglishCustomCandidateRanking.merge(query,emptyList(),entries,{ it.reading },{ it.text }),
                EnglishCustomCandidateRanking.merge(query,emptyList(),index.matching(query),{ it.reading },{ it.text }))
        }
    }

    @Test fun largeBucketsAreNotTruncatedOrReorderedAndReadingsAreIndexedOnce() {
        val entries = (0 until 10000).map { Entry(if(it%2==0) "nz" else "na", "$it") }
        var reads = 0
        val index = CustomDictionaryIndex(entries,{ reads++; it.reading },false)
        assertEquals(entries,index.matching("n"))
        assertEquals(entries.filter { it.reading=="na" },index.matching("na"))
        assertTrue(index.matching("z").isEmpty())
        assertEquals(entries.size,reads)
    }
}
