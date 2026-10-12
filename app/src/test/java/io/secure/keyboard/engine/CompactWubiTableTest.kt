package io.secure.keyboard.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assert.*
import org.junit.Test

class CompactWubiTableTest {
    private fun fixture(): ByteBuffer {
        // One four-code bucket with two entries, one prefix bucket referring in reverse order.
        val first = "甲".toByteArray(Charsets.UTF_8)
        val second = "𠀀乙".toByteArray(Charsets.UTF_8)
        val textStart = 104
        val secondOffset = textStart + 2 + first.size
        val data = ByteBuffer.allocate(secondOffset + 2 + second.size).order(ByteOrder.BIG_ENDIAN)
        data.put("SKWB0001".toByteArray(Charsets.US_ASCII))
        for (value in intArrayOf(1, 2, 1, 1, 64, 76, 88, textStart, data.capacity(), 30, 0, 0, 0, 0)) data.putInt(value)
        // abcd = 1*27^3 + 2*27^2 + 3*27 + 4; a = 1*27^3.
        data.putInt(21226).putInt(textStart).putInt(2)
        data.putInt(19683).putInt(0).putInt(2)
        data.putInt(secondOffset).putInt(21226).putInt(textStart).putInt(21226)
        data.putShort(first.size.toShort()).put(first)
        data.putShort(second.size.toShort()).put(second)
        data.rewind()
        return data
    }

    @Test fun readsExactPrefixUnicodeAndLimitsWithoutChangingBufferPosition() {
        val data = fixture()
        val table = CompactWubiTable(data)
        assertEquals(2, table.entryCount)
        assertEquals(2, table.exactCandidateCount("abcd"))
        assertEquals(listOf("甲", "𠀀乙"), table.exact("abcd", 10).map { it.text })
        assertEquals(listOf("𠀀乙", "甲"), table.prefix("a", 10).map { it.text })
        assertEquals(listOf("甲"), table.exact("abcd", 1).map { it.text })
        assertTrue(table.prefix("abcd", 20).isEmpty())
        assertTrue(table.exact("ABCd", 20).isEmpty())
        assertTrue(table.exact("abcde", 20).isEmpty())
        assertTrue(table.exact("", 20).isEmpty())
        assertTrue(table.exact("abcd", 0).isEmpty())
        assertEquals(0, data.position())
        assertTrue(table.prefix("a", 30).all { it.code == "abcd" && it.source == Source.WUBI })
    }

    @Test fun concurrentLookupsNeverShareMutableBufferPositions() {
        val table = CompactWubiTable(fixture())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 4).map {
                pool.submit(java.util.concurrent.Callable {
                    repeat(250) {
                        assertEquals(listOf("甲", "𠀀乙"), table.exact("abcd", 10).map { it.text })
                        assertEquals(listOf("𠀀乙", "甲"), table.prefix("a", 10).map { it.text })
                    }
                    true
                })
            }
            jobs.forEach { assertTrue(it.get()) }
        } finally { pool.shutdownNow() }
    }

    @Test fun corruptHeaderAndOutOfBoundsRecordsFailExplicitly() {
        fun fails(block: () -> Unit) {
            try { block(); fail("Corrupt table accepted") } catch (_: IllegalArgumentException) { }
        }
        fails { CompactWubiTable(ByteBuffer.allocate(63)) }
        fails { CompactWubiTable(fixture().apply { position(1) }) }
        fails { CompactWubiTable(fixture().apply { putInt(12, Int.MAX_VALUE) }) }
        fails { CompactWubiTable(fixture().apply { putInt(8, 99) }) }
        fails { CompactWubiTable(fixture().apply { putInt(24, -1) }) }
        fails { CompactWubiTable(fixture().apply { putInt(28, Int.MAX_VALUE) }) }
        fails { CompactWubiTable(fixture().apply { putInt(40, 1) }) }
        fails { CompactWubiTable(fixture().apply { putInt(68, Int.MAX_VALUE) }).exact("abcd", 1) }
        fails { CompactWubiTable(fixture().apply { putInt(80, Int.MAX_VALUE) }).prefix("a", 2) }
        fails { CompactWubiTable(fixture().apply { putShort(104, (-1).toShort()) }).exact("abcd", 1) }
    }

    @Test fun tableIntegratesWithPinyinAndCustomPriorityWithoutLosingExtensionB() {
        val engine = DictionaryEngine(CompactWubiTable(fixture()), "...\n拼音\tabcd\t30\n".reader())
        val texts = engine.candidates("abcd", false).map { it.text }
        assertEquals(listOf("甲", "𠀀乙", "拼音"), texts)
        val custom = listOf("自定义" to "abcd")
        assertEquals(listOf("甲", "自定义", "拼音", "𠀀乙"),
            CustomCandidateRanking.merge("abcd", texts, custom, { it.second }, { it.first }))
        assertEquals("甲", engine.exactWubi("abcd", 1).single().text)
    }

    @Test fun packagedTablePreservesEveryEntryAndMakesLargeExactBucketReachable() {
        val asset = File("src/main/assets/dictionaries/compact-wubi.skwb")
        FileChannel.open(asset.toPath(), StandardOpenOption.READ).use { channel ->
            val table = CompactWubiTable(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
            val engine = DictionaryEngine(table, "...\n".reader())
            assertEquals(441796, engine.wubiEntryCount)
            assertEquals(472, table.exactCandidateCount("khkh"))
            assertEquals(40, engine.candidates("khkh", false).size)
            val all = engine.candidates("khkh", false, allExactWubi = true)
            assertEquals(472, all.size)
            assertEquals(all.size, all.map { it.text }.distinct().size)
            assertEquals(table.exact("khkh", Int.MAX_VALUE), all)
            assertEquals(all.first(), engine.exactWubi("khkh", 1).single())
            assertTrue(engine.candidates("kh", false, allExactWubi = true).size <= 40)
        }
    }

    @Test fun requestedLocalBuiltinPairsUseLiteralCodesAndDoNotDependOnUserEntries() {
        val requested = listOf("微信" to "tmwy", "安卓" to "pvhj", "北川" to "uxkt",
            "美团" to "uglt", "抖音" to "ruuj", "支付宝" to "fwpg",
            "滴滴" to "iuiu", "微博" to "tmfg")
        val asset = File("src/main/assets/dictionaries/compact-wubi.skwb")
        FileChannel.open(asset.toPath(), StandardOpenOption.READ).use { channel ->
            val table = CompactWubiTable(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
            val engine = DictionaryEngine(table, "...\n".reader())
            for ((text, code) in requested) {
                val expected = Candidate(text, code, Source.WUBI)
                assertEquals(code, 1, engine.exactWubi(code, Int.MAX_VALUE).count { it == expected })
                val builtin = engine.candidates(code, false, allExactWubi = true)
                assertEquals(code, 1, builtin.count { it == expected })
                assertEquals(code, expected, builtin.first())
                assertEquals(code, expected, engine.candidates(code, false, limit = 1).single())
                assertEquals(code, expected, engine.exactWubi(code, 1).single())
                // Exact duplicate custom entries cannot duplicate or displace the first
                // built-in. Other exact user matches still start at the second position.
                val userEntries = mutableListOf(text to code, "用户自定义" to code,
                    "用户自定义" to code, "𠀀用户" to code)
                val withoutUserEntries = CustomCandidateRanking.merge(code, builtin.map { it.text },
                    emptyList<Pair<String, String>>(), { it.second }, { it.first })
                val displayed = CustomCandidateRanking.merge(code, builtin.map { it.text }, userEntries,
                    { it.second }, { it.first })
                assertEquals(code, text, displayed.first())
                assertEquals(code, "用户自定义", displayed[1])
                assertEquals(code, 1, displayed.count { it == text })
                assertEquals(code, 1, displayed.count { it == "用户自定义" })
                fun containsExtensionB(value: String) =
                    value.codePoints().anyMatch { it in 0x20000..0x2A6DF }
                val extensionTail = displayed.dropWhile { !containsExtensionB(it) }
                assertTrue(code, extensionTail.contains("𠀀用户"))
                assertTrue(code, extensionTail.all(::containsExtensionB))
                assertEquals(code, displayed.first(), engine.exactWubi(code, 1).single().text)
                userEntries.clear()
                assertEquals(withoutUserEntries,
                    CustomCandidateRanking.merge(code, builtin.map { it.text }, userEntries,
                        { it.second }, { it.first }))
                assertEquals(builtin, engine.candidates(code, false, allExactWubi = true))
                assertTrue(engine.completeEnglish(code).isEmpty())
            }
        }
    }

    @Test fun packagedExactAndPrefixCandidatesMatchCurrentSourceOrderOracle() {
        // An independent source-row oracle replaces hashes frozen from the old order.
        // Static weight remains first, equal weights follow these sources and their
        // row order. Only complete-code exact groups receive the priority promotion.
        data class Row(val text: String, val code: String, val weight: Long)
        val sourceNames = listOf(
            "wubi86.dict.yaml", "wubi86_high_priority.dict.yaml", "wubi86_dict_it.dict.yaml",
            "wubi86_dict_xzqh.dict.yaml", "wubi86_dict_ciyu.dict.yaml",
            "wubi86_dict_china_cultural_cities.dict.yaml", "wubi86_dict_chengyu.dict.yaml",
        )
        val priorityCodes = listOf("tmwy", "pvhj", "uxkt", "uglt", "ruuj", "fwpg", "iuiu", "tmfg")
        val prefixes = (priorityCodes.flatMap { code -> (1..3).map { code.take(it) } } +
            listOf("a", "aa", "aaa", "aac", "aad")).distinct()
        val exactCodes = (prefixes + priorityCodes + listOf("aaaa", "aaad", "aaai")).toSet()
        val seen = HashSet<Pair<String, String>>()
        val retained = ArrayList<Row>()
        val priorities = LinkedHashMap<String, MutableList<String>>()
        for ((sourceIndex, filename) in sourceNames.withIndex()) {
            val file = if (sourceIndex == 0) File("src/main/assets/dictionaries", filename)
                else File("../third_party/wubi_supplements", filename)
            var started = false
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { line ->
                    if (!started) {
                        if (line.trim() == "...") started = true
                    } else if (line.isNotBlank() && !line.startsWith("#")) {
                        val fields = line.split('\t')
                        val text = fields[0]
                        val code = fields[1]
                        if (filename == "wubi86_high_priority.dict.yaml") {
                            val group = priorities.getOrPut(code) { ArrayList() }
                            if (text !in group) group.add(text)
                        }
                        // Retain only rows relevant to the selected oracle queries.
                        if ((code in exactCodes || prefixes.any { code.startsWith(it) && code.length > it.length }) &&
                            seen.add(text to code)) {
                            retained.add(Row(text, code, fields.getOrNull(2)?.toLongOrNull() ?: 0L))
                        }
                    }
                }
            }
            assertTrue(filename, started)
        }
        val ordinary = retained.sortedByDescending { it.weight }
        val expectedExact = ordinary.groupBy { it.code }.mapValues { (code, rows) ->
            val preferred = priorities[code].orEmpty()
            preferred + rows.map { it.text }.filter { it !in preferred }
        }
        val asset = File("src/main/assets/dictionaries/compact-wubi.skwb")
        FileChannel.open(asset.toPath(), StandardOpenOption.READ).use { channel ->
            val table = CompactWubiTable(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
            val engine = DictionaryEngine(table, "...\n".reader())
            for (code in exactCodes) {
                assertEquals(code, expectedExact[code].orEmpty(), table.exact(code, Int.MAX_VALUE).map { it.text })
            }
            for (prefix in prefixes) {
                val expectedPrefix = ordinary.asSequence()
                    .filter { it.code.startsWith(prefix) && it.code.length > prefix.length }
                    .take(30).map { Candidate(it.text, it.code, Source.WUBI) }.toList()
                assertEquals(prefix, expectedPrefix, table.prefix(prefix, Int.MAX_VALUE))
                val expectedUi = (expectedExact[prefix].orEmpty().take(40)
                    .map { Candidate(it, prefix, Source.WUBI) } + expectedPrefix)
                    .distinctBy { it.text }.take(40)
                assertEquals(prefix, expectedUi, engine.candidates(prefix, false))
            }
        }
    }
}
