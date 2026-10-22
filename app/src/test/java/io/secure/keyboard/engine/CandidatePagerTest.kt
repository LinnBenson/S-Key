package io.secure.keyboard.engine

import org.junit.Test
import org.junit.Assert.*

class CandidatePagerTest {
    @Test fun onlyRequestedItemsAreReadAndEveryItemRemainsReachable() {
        var reads=0
        val pager=CandidatePager(sequence { repeat(10003) { reads++; yield("词$it") } })
        assertEquals((0..39).map { "词$it" },pager.next())
        assertEquals(40,reads)
        val rest=mutableListOf<String>()
        while(!pager.exhausted) rest.addAll(pager.next())
        assertEquals((40..10002).map { "词$it" },rest)
        assertEquals(10003,reads)
    }
    @Test fun syllableFilteringDoesNotDiscardMatchesAfterTheFirstPage() {
        val rows=List(200) { DictionaryEngine.Entry("先$it","xian",1000L-it) } + DictionaryEngine.Entry("西安","xi an",1)
        var pages=0
        val pinyin=object: PinyinLookup {
            override val entryCount=rows.size
            override fun index(kind: String)=mapOf("xian" to rows)
            override fun lookupPage(kind: String,key: String,offset: Int,limit: Int): List<DictionaryEngine.Entry> {
                pages++;assertEquals(40,limit)
                return if(key=="xian") rows.drop(offset).take(limit) else emptyList()
            }
        }
        val wubi=object: WubiLookup {
            override val entryCount=0
            override fun exactCandidateCount(code: String)=0
            override fun exact(code: String,limit: Int)=emptyList<Candidate>()
            override fun prefix(code: String,limit: Int)=emptyList<Candidate>()
        }
        val english=object: EnglishLookup { override fun complete(prefix: String,limit: Int)=emptyList<String>() }
        val engine=DictionaryEngine(wubi,pinyin,english)
        assertEquals(listOf("西安"),engine.candidates("xi'an",false,mode=ChineseInputMode.PINYIN).map { it.text })
        assertEquals(6,pages)
    }
    @Test fun orderingAndDeduplicationCrossPageBoundaries() {
        val normal=listOf("甲","𠀀","乙","丙")
        val custom=listOf("a" to "乙","a" to "自定义","ab" to "尾词")
        val source=CandidateSources.merge("a",normal.asSequence(),custom,true,{it.first},{it.second})
        val pager=CandidatePager(source)
        val all=buildList { while(!pager.exhausted) addAll(pager.next(2)) }
        assertEquals(CustomCandidateRanking.merge("a",normal,custom,{it.first},{it.second}),all)
    }
}
