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
    @Test fun convertedDefaultSourcesPassRuntimeValidation() {
        for((name,kind,count) in listOf(Triple("skey_wubi86.tsv","wubi",136916),Triple("skey_pinyin.tsv","pinyin",65125),Triple("skey_english.tsv","english",25000)))
            assertEquals(count,DictionarySource.validate(File("../third_party/open_dictionaries/$name"),kind,name))
    }
    @Test fun validFormats() {
        assertEquals(1,validate("你\twq\t123\n"))
        assertEquals(1,validate("你好\tni hao\t123\n","pinyin"))
        assertEquals(1,validate("hello\t123\n","english"))
        assertEquals(1,validate("不\tb\t123\n","pinyin"))
        assertEquals(1,validate("---\nname: test\ncolumns:\n  - text\n  - code\n  - weight\n...\n你\twq\t123\n",name="test.dict.yaml"))
        assertEquals(0,validate("# empty draft\n",empty=true))
    }
    @Test fun customFilenameDoesNotChangePinyinSemantics() {
        assertEquals(1,validate("name: test\n...\n你好\tni hao\t1\n","pinyin","pinyin_initials.dict.yaml"))
    }
    @Test fun rejectsWrongTypeColumnsEncodingAndEmpty() {
        rejects("你 wq 123\n")
        rejects("你好\tni hao\t1\n")
        rejects("你好\tinvalid\t1\n","pinyin")
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
    @Test fun rejectsMalformedWeightsControlsAndUtf8() {
        rejects("χ\tzzxx\t450.5\n")
        rejects("你\twq\t9223372036854775808\n")
        rejects("你\u001b\twq\t1\n")
        val file=File.createTempFile("invalid-dictionary", ".tsv")
        try {
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            try { DictionarySource.validate(file,"wubi","bad.tsv"); fail("bad UTF-8") }
            catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("UTF-8")) }
            java.io.RandomAccessFile(file,"rw").use { it.setLength(64L*1024*1024+1) }
            try { DictionarySource.parse(file,"wubi","huge.tsv") { _,_,_ -> }; fail("oversized compile source") }
            catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("64 MiB")) }
        } finally { file.delete() }
    }
    @Test fun rejectsInvalidEditUnicodeWithoutChangingSource() {
        val file=File.createTempFile("dictionary", ".tsv")
        val output=File.createTempFile("replacement", ".tsv")
        try {
            file.writeText("你\twq\t1\n")
            val page=DictionarySource.page(file,0,"version")
            try { DictionarySource.replace(file,page,"\uD800\twq\t1\n",output); fail("unpaired surrogate") }
            catch(_: IllegalArgumentException) {}
            assertEquals(page.text,file.readText())
        } finally { file.delete(); output.delete() }
    }
}
