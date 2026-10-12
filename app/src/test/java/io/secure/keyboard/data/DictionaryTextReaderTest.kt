package io.secure.keyboard.data

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class DictionaryTextReaderTest {
    @Test
    fun preservesValidUtf8TsvIncludingBomAndChinese() {
        val original = "\uFEFFword\treading\r\n你好\tnihao\r\n微笑🙂\tweixiao\n"
        assertEquals(original, DictionaryTextReader.read(original.byteInputStream(Charsets.UTF_8)))
    }

    @Test
    fun rejectsMalformedUtf8RatherThanImportingReplacementCharacters() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0x09, 0x61, 0x0A)
        val error =
            assertThrows(IllegalStateException::class.java) {
                DictionaryTextReader.read(ByteArrayInputStream(bytes))
            }
        assertTrue(error.message!!.contains("UTF-8"))
        assertTrue(error.message!!.contains("未导入"))
    }

    @Test
    fun rejectsTruncatedMultibyteSequence() {
        assertThrows(IllegalStateException::class.java) {
            DictionaryTextReader.read(
                ByteArrayInputStream(byteArrayOf(0xE4.toByte(), 0xB8.toByte()))
            )
        }
    }

    @Test
    fun enforcesCharacterLimitWithoutTruncation() {
        assertEquals("你好", DictionaryTextReader.read("你好".byteInputStream(), 2))
        assertThrows(IllegalArgumentException::class.java) {
            DictionaryTextReader.read("你好啊".byteInputStream(), 2)
        }
    }

    @Test
    fun closesStreamAfterSuccessfulAndFailedReads() {
        class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            var closed = false

            override fun close() {
                closed = true
                super.close()
            }
        }
        val valid = Tracked("你好\tnihao".toByteArray())
        DictionaryTextReader.read(valid)
        assertTrue(valid.closed)
        val invalid = Tracked(byteArrayOf(0xFF.toByte()))
        assertThrows(IllegalStateException::class.java) { DictionaryTextReader.read(invalid) }
        assertTrue(invalid.closed)
    }
}
