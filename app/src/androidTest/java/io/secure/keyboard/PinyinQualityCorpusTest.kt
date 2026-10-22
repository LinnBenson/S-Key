package io.secure.keyboard

import android.test.InstrumentationTestCase
import io.secure.keyboard.dictionaries.CompiledDictionary
import io.secure.keyboard.dictionaries.DictionaryRepository
import io.secure.keyboard.data.TraditionalConverter
import io.secure.keyboard.engine.PinyinComposition

@Suppress("DEPRECATION")
class PinyinQualityCorpusTest : InstrumentationTestCase() {
    fun testPackagedFirstChoiceCorpus() {
        val context=instrumentation.targetContext
        val rows=instrumentation.context.assets.open("pinyin_quality.tsv").bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }
        val lines=listOf("STPhrases.txt","STCharacters.txt").flatMap { name -> context.assets.open("opencc/$name").bufferedReader().use { it.readLines() } }
        val converter=TraditionalConverter(lines.asSequence())
        val failures=mutableListOf<String>()
        CompiledDictionary(DictionaryRepository(context).defaultRuntime()).use { db ->
            for(row in rows) {
                val input=row[1].takeUnless { it=="_" }.orEmpty()
                val prior=row[2].takeUnless { it=="_" }.orEmpty()
                val result=db.engine.decodePinyin(input,prior,emptyList(),false,emptySet()).firstOrNull()?.text
                if(result!=row[3] && result!=row.getOrNull(5)) failures.add("${row[0]} $input / $prior: expected=${row[3]}, actual=$result")
                if(result!=row[3] && result==row.getOrNull(5)) instrumentation.sendStatus(0,android.os.Bundle().apply {
                    putString("quality_known_issue", "${row[0]} $input / $prior: target=${row[3]}, baseline=$result")
                })
                if(converter.convert(row[3])!=row[4]) failures.add("繁体 ${row[3]}: ${converter.convert(row[3])}")
            }
            val raw="nihaowoshizhongguoren"
            val first=db.engine.decodePinyin(raw,"",emptyList(),false,emptySet())
            val segment=first.firstOrNull { it.text=="你好" && it.consumed==5 }
            assertNotNull("必须可以分段选择你好",segment)
            val composition=PinyinComposition()
            composition.select(segment!!.text,raw.take(segment.consumed))
            val rest=db.engine.decodePinyin(raw.drop(segment.consumed),composition.text,emptyList(),false,emptySet())
            if(composition.text+rest.firstOrNull()?.text!="你好我是中国人") failures.add("长句/分段: ${first.firstOrNull()?.text}; remaining=${rest.firstOrNull()?.text}")
            assertEquals("nihao",composition.undo())
        }
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
}
