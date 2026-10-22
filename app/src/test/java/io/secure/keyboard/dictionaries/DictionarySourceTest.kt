package io.secure.keyboard.dictionaries

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DictionarySourceTest {
    private fun validate(text: String, kind: String="wubi", name: String="test.tsv", empty: Boolean=false): Int {
        val file=File.createTempFile("dictionary", ".txt")
        try { file.writeText(text); return DictionarySource.validate(file,kind,name,empty) } finally { file.delete() }
    }
    private fun rejects(text: String,kind: String="wubi",name: String="test.tsv") {
        try { validate(text,kind,name); fail("must reject malformed source") } catch(_: IllegalArgumentException) { }
    }
    @Test fun validFormats() {
        assertEquals(1,validate("你\twq\t123\n"))
        assertEquals(1,validate("你好\tni hao\t123\n","pinyin"))
        assertEquals(1,validate("hello\t123\n","english"))
        assertEquals(1,validate("不\tb\t123\n","pinyin"))
        assertEquals(16855,DictionarySource.validate(File("src/main/assets/dictionaries/pinyin_initials.dict.yaml"),"pinyin","pinyin_initials.dict.yaml"))
        assertEquals(1,validate("---\nname: test\ncolumns:\n  - text\n  - code\n  - weight\n...\n你\twq\t123\n",name="test.dict.yaml"))
        assertEquals(0,validate("# empty draft\n",empty=true))
    }
    @Test fun rejectsWrongTypeColumnsEncodingAndEmpty() {
        rejects("你 wq 123\n")
        rejects("你好\tni hao\t1\n")
        rejects("你好\tinvalid\t1\n","pinyin")
        rejects("name: test\n...\n你好\tni hao\t1\n","pinyin","pinyin_initials.dict.yaml")
        rejects("Hello\t1\n","english")
        rejects("hello\tbad\n","english")
        rejects("你\twq\t1\textra\n")
        rejects("你\twq\t-1\n")
        rejects("<html>download failed</html>")
        rejects("# empty\n")
        rejects("name: test\n你\twq\t1\n",name="test.yaml")
        rejects("name: test\ncolumns:\n - code\n - text\n...\nwq\t你\n",name="test.yaml")
        rejects("name: test\nimport_tables:\n - other\n...\n你\twq\t1\n",name="test.yaml")
    }
    @Test fun allBundledChineseSourcesRemainValid() {
        for(name in listOf("pinyin_simp.dict.yaml","wubi86.dict.yaml")) {
            assertTrue(DictionarySource.validate(File("src/main/assets/dictionaries/$name"),if(name.startsWith("pinyin")) "pinyin" else "wubi",name)>0)
        }
    }
}
