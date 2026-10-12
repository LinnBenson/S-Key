package io.secure.keyboard.diagnostics

import android.content.ContextWrapper
import android.test.AndroidTestCase
import java.io.File

/** Fixed synthetic parser gate only: no smaps scan, sampler thread, GC or PSS measurement. */
@Suppress("DEPRECATION")
class LifetimeMemoryProbeParserDiagnosticTest : AndroidTestCase() {
    fun testActualKotlinHelpersMatchOriginalParserOnFixedFixtures() {
        val scratch = File(context.cacheDir, "lifetime-parser-${System.nanoTime()}")
        assertTrue("Create private parser-test scratch directory", scratch.mkdir())
        try {
            // Constructor reads process identity and creates only an empty report in this scratch dir.
            val probe = LifetimeMemoryProbe(object : ContextWrapper(context) {
                override fun getFilesDir(): File = scratch
            }, "parser-only")
            fun method(name: String) = LifetimeMemoryProbe::class.java
                .getDeclaredMethod(name, String::class.java).apply { isAccessible = true }
            val headerMethod = method("isSmapsHeader")
            val fieldsMethod = method("hasFiveSmapsFields")
            val keyMethod = method("smapsMetricKey")
            val valueMethod = method("smapsKiBValue")
            fun header(line: String) = headerMethod.invoke(probe, line) as Boolean
            fun fiveFields(line: String) = fieldsMethod.invoke(probe, line) as Boolean
            fun key(line: String) = keyMethod.invoke(probe, line) as String?
            fun value(line: String) = valueMethod.invoke(probe, line) as Long

            // Compile each original Regex once; evaluate only the small fixed fixture below.
            val oldHeader = Regex("^[0-9a-fA-F]+-[0-9a-fA-F]+\\s+.*")
            val oldSpaces = Regex("\\s+")
            val metrics = listOf("Size", "Rss", "Pss", "Private_Clean", "Private_Dirty",
                "Shared_Clean", "Shared_Dirty", "Swap", "SwapPss")
            fun oldKey(line: String): String? = line.substringBefore(':')
                .takeIf { it in metrics }?.plus("KiB")
            fun oldValue(line: String) = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
            val base = "00001000-00002000 rw-p 00000000 00:00 0"
            val mappings = listOf(
                base, "$base\t ", base.replace(' ', '\t'), "$base [anon:dalvik-main space]", "$base [heap]",
                "$base /data/libsherpa.onnx.apk.ttf.wubi", "$base /data/libonnxruntime.so",
                "$base /data/model.onnx.apk", "$base /data/base.apk.ttf", "$base /data/wubi.otf",
                "$base /data/font.ttf", "$base /data/wubi index.skwb (deleted)",
                "$base /tmp/中文 file", "$base [stack]")
            for (line in mappings + listOf("", "Size: 8 kB", "-1 rw-p", "0- rw-p", "0-1rw-p", " $base")) {
                assertEquals("Header: $line", oldHeader.matches(line), header(line))
                assertEquals("Field count: $line", line.trim().split(oldSpaces).size == 5, fiveFields(line))
            }
            assertTrue("Unnamed five-field map", fiveFields(base))
            assertFalse("Named path is not an unnamed map", fiveFields("$base /tmp/file"))
            assertFalse("Space-containing path is not an unnamed map", fiveFields("$base /tmp/a b"))

            val numbers = listOf("0 kB", "123 kB", "9223372036854775807 kB", "9223372036854775808 kB",
                "-9223372036854775808 kB", "-9223372036854775809 kB", "+7 kB", "-5 kB", "１２３ kB",
                "7\t", "7\tkB", "garbage kB", "", "0000017 kB")
            for (metric in metrics) {
                for (number in numbers) {
                    val line = "$metric:\t $number"
                    assertEquals("Metric key: $line", "$metric" + "KiB", key(line))
                    assertEquals("Metric value: $line", oldValue(line), value(line))
                }
                assertEquals("Missing-colon key: $metric", oldKey(metric), key(metric))
                assertEquals("Missing-colon value: $metric", 0L, value(metric))
            }
            for (line in listOf("Pss_Anon: 1 kB", "SizeOther: 2 kB", " Size: 3 kB", "VmFlags: rd wr")) {
                assertNull("Unknown field: $line", key(line))
            }
            assertEquals(Long.MAX_VALUE, value("Size: 9223372036854775807 kB"))
            assertEquals(Long.MIN_VALUE, value("Size: -9223372036854775808 kB"))
            assertEquals(0L, value("Size: 9223372036854775808 kB"))
            assertEquals(0L, value("Size: -9223372036854775809 kB"))

            // Same unchanged category precedence, with the actual helper on the optimized side.
            fun category(line: String, optimized: Boolean): String = when {
                line.contains("libsherpa") || line.contains("libonnx") -> "voiceLibraries"
                line.contains(".onnx") -> "voiceModelFiles"
                line.contains(".apk") -> "apkCombinedUnattributed"
                line.contains(".ttf") || line.contains(".otf") -> "separateFonts"
                line.contains("wubi") -> "separateWubiFiles"
                line.contains("[anon") || line.contains("[heap]") ||
                    (if (optimized) fiveFields(line) else line.trim().split(oldSpaces).size == 5) -> "anonymousHeap"
                else -> "otherMappings"
            }
            val lines = mutableListOf("Size: 999 kB") // Before any map, ignored by both parsers.
            mappings.forEachIndexed { index, line ->
                lines.add(line)
                metrics.forEachIndexed { field, metric -> lines.add("$metric: ${(index + 1) * (field + 1)} kB") }
                lines.add("Pss_Anon: 999 kB")
            }
            fun aggregate(optimized: Boolean): Map<String, Map<String, Long>> {
                val totals = mutableMapOf<String, MutableMap<String, Long>>()
                var current: MutableMap<String, Long>? = null
                for (line in lines) {
                    if (if (optimized) header(line) else oldHeader.matches(line)) {
                        val group = totals.getOrPut(category(line, optimized)) { mutableMapOf() }
                        current = group
                        group["mappingCount"] = (group["mappingCount"] ?: 0L) + 1L
                    } else {
                        val metric = if (optimized) key(line) else oldKey(line)
                        if (metric != null) current?.let {
                            it[metric] = (it[metric] ?: 0L) + (if (optimized) value(line) else oldValue(line))
                        }
                    }
                }
                return totals
            }
            val actual = aggregate(true)
            assertEquals("Fixed-fixture grouped totals match original algorithm", aggregate(false), actual)
            assertEquals("All mapping categories retained", 7, actual.size)
            assertEquals("All mappings retained", mappings.size.toLong(), actual.values.sumOf { it.getValue("mappingCount") })
            actual.values.forEach { assertEquals("All nine metrics plus count retained", 10, it.size) }
        } finally {
            assertTrue("Remove only parser-test scratch files", scratch.deleteRecursively())
        }
    }
}
