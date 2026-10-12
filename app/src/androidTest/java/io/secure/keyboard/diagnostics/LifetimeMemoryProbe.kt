package io.secure.keyboard.diagnostics

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Instrumented diagnostic overhead is intentional. Never part of isolated production IME PSS. */
@Suppress("DEPRECATION")
internal class LifetimeMemoryProbe(context: Context, name: String) {
    private val report = File(context.filesDir, "lifetime-$name.jsonl")
    private val running = AtomicBoolean(false)
    private var ticker: Thread? = null
    private val processStartTicks = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(Regex("\\s+"))[19].toLong()
    @Volatile var stage = "starting"
    @Volatile var cycle = 0

    init { report.writeText("") }

    fun start() {
        check(running.compareAndSet(false, true))
        ticker = Thread({
            while (running.get()) {
                sample("sampled_peak_tick", full = false)
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "lifetime-test-sampler").apply { start() }
    }

    fun stop() {
        running.set(false)
        ticker?.interrupt()
        ticker?.join(5000)
        check(ticker?.isAlive != true) { "Diagnostic sampler did not stop" }
        ticker = null
    }

    fun sample(event: String, full: Boolean = true, extra: JSONObject = JSONObject(), forcedGc: Boolean = false) {
        val requested = SystemClock.elapsedRealtime()
        synchronized(this) { sampleLocked(event, full, extra, forcedGc, requested) }
    }

    private fun sampleLocked(event: String, full: Boolean, extra: JSONObject, forcedGc: Boolean, requested: Long) {
        val started = SystemClock.elapsedRealtime()
        if (extra.has("recoveryOperationEndElapsedMillis")) {
            extra.put("actualRecoverySampleStartMillis", started - extra.getLong("recoveryOperationEndElapsedMillis"))
        }
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val data = JSONObject()
            .put("event", event).put("stage", stage).put("cycle", cycle)
            .put("family", "instrumented-lifetime-diagnostic")
            .put("pid", Process.myPid()).put("processStartTicks", processStartTicks).put("elapsedMillis", started)
            .put("sampleLockWaitMillis", started - requested).put("fullMappingCheckpoint", full)
            .put("forcedGcRequested", forcedGc)
            .put("pssKiB", info.totalPss).put("javaPssKiB", info.dalvikPss)
            .put("nativePssKiB", info.nativePss).put("otherPssKiB", info.otherPss)
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaCommittedBytes", runtime.totalMemory()).put("javaMaxBytes", runtime.maxMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            .put("nativeHeapSizeBytes", Debug.getNativeHeapSize())
            .put("nativeFreeBytes", Debug.getNativeHeapFreeSize())
            .put("automaticGcCount", Debug.getRuntimeStat("art.gc.gc-count") ?: JSONObject.NULL)
            .put("threadCount", File("/proc/self/task").list()?.size ?: -1)
            .put("fdCount", File("/proc/self/fd").list()?.size ?: -1)
            .put("extra", extra)
        if (full) {
            data.put("threads", JSONArray(Thread.getAllStackTraces().keys.map { it.name }.sorted()))
            val totals = mutableMapOf<String, MutableMap<String, Long>>()
            var current: MutableMap<String, Long>? = null
            try {
                File("/proc/self/smaps").forEachLine { line ->
                    if (isSmapsHeader(line)) {
                        val kind = when {
                            line.contains("libsherpa") || line.contains("libonnx") -> "voiceLibraries"
                            line.contains(".onnx") -> "voiceModelFiles"
                            line.contains(".apk") -> "apkCombinedUnattributed"
                            line.contains(".ttf") || line.contains(".otf") -> "separateFonts"
                            line.contains("wubi") -> "separateWubiFiles"
                            line.contains("[anon") || line.contains("[heap]") || hasFiveSmapsFields(line) -> "anonymousHeap"
                            else -> "otherMappings"
                        }
                        current = totals.getOrPut(kind) { mutableMapOf() }
                        current!!["mappingCount"] = (current!!["mappingCount"] ?: 0) + 1
                    } else {
                        val key = smapsMetricKey(line)
                        if (key != null) {
                            val value = smapsKiBValue(line)
                            current?.let { it[key] = (it[key] ?: 0) + value }
                        }
                    }
                }
                data.put("mappingGroups", JSONObject().apply { totals.forEach { (key, value) -> put(key, JSONObject(value as Map<*, *>)) } })
            } catch (error: Exception) { data.put("smapsUnavailable", error.javaClass.simpleName) }
        }
        data.put("samplingDurationMillisBeforeReportWrite", SystemClock.elapsedRealtime() - started)
        report.appendText(data.toString() + "\n")
    }

    // smaps has one address header followed by fixed named numeric fields. Avoid compiling
    // a Regex/matcher and allocating a metric-name list for every line of a full checkpoint.
    private fun isSmapsHeader(line: String): Boolean {
        var index = 0
        while (index < line.length && isSmapsHex(line[index])) index++
        if (index == 0 || index >= line.length || line[index] != '-') return false
        val secondAddressStart = ++index
        while (index < line.length && isSmapsHex(line[index])) index++
        if (index == secondAddressStart || index >= line.length || !isSmapsSpace(line[index])) return false
        while (index < line.length && isSmapsSpace(line[index])) index++
        // Match the old Regex's non-DOTALL tail, even for unusual path characters.
        while (index < line.length) {
            when (line[index++]) { '\n', '\r', '\u0085', '\u2028', '\u2029' -> return false }
        }
        return true
    }

    private fun isSmapsHex(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    // ASCII whitespace used by kernel smaps headers and field separators.
    private fun isSmapsSpace(value: Char): Boolean =
        value == ' ' || value == '\t' || value == '\n' || value == '\u000b' || value == '\u000c' || value == '\r'

    private fun hasFiveSmapsFields(line: String): Boolean {
        var start = 0
        var end = line.length
        // Keep the former trim().split(Regex("\\s+")).size == 5 semantics.
        while (start < end && line[start].isWhitespace()) start++
        while (end > start && line[end - 1].isWhitespace()) end--
        var fields = 0
        var index = start
        while (index < end) {
            fields++
            if (fields > 5) return false
            while (index < end && !isSmapsSpace(line[index])) index++
            while (index < end && isSmapsSpace(line[index])) index++
        }
        return fields == 5
    }

    private fun smapsMetricKey(line: String): String? = when {
        line.startsWith("Size:") || line == "Size" -> "SizeKiB"
        line.startsWith("Rss:") || line == "Rss" -> "RssKiB"
        line.startsWith("Pss:") || line == "Pss" -> "PssKiB"
        line.startsWith("Private_Clean:") || line == "Private_Clean" -> "Private_CleanKiB"
        line.startsWith("Private_Dirty:") || line == "Private_Dirty" -> "Private_DirtyKiB"
        line.startsWith("Shared_Clean:") || line == "Shared_Clean" -> "Shared_CleanKiB"
        line.startsWith("Shared_Dirty:") || line == "Shared_Dirty" -> "Shared_DirtyKiB"
        line.startsWith("Swap:") || line == "Swap" -> "SwapKiB"
        line.startsWith("SwapPss:") || line == "SwapPss" -> "SwapPssKiB"
        else -> null
    }

    private fun smapsKiBValue(line: String): Long {
        var index = line.indexOf(':') + 1
        while (index < line.length && line[index].isWhitespace()) index++
        val numberStart = index
        var value = 0L
        while (index < line.length) {
            val character = line[index++]
            if (character !in '0'..'9') {
                if (character == ' ' && index - 1 > numberStart) return value
                // Kernel fields use unsigned ASCII decimals followed by a space. Preserve
                // the former signed/Unicode/malformed-value behavior outside that fast path.
                return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0
            }
            val digit = character - '0'
            if (value > Long.MAX_VALUE / 10 || (value == Long.MAX_VALUE / 10 && digit > 7)) return 0
            value = value * 10 + digit
        }
        return value
    }

    /** Relative deadlines prevent expensive samples from adding accidental extra settle time. */
    fun recover(checkpoint: Boolean = false, final: Boolean = false, operationEndMillis: Long = SystemClock.elapsedRealtime(), extra: () -> JSONObject = { JSONObject() }) {
        val started = operationEndMillis
        val samples = listOf(0, 2) + (if (checkpoint || final) listOf(10) else emptyList()) + (if (final) listOf(60) else emptyList())
        for (seconds in samples) {
            SystemClock.sleep((started + seconds * 1000 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            sample("recovery_${seconds}s", full = seconds >= 10, extra = extra()
                .put("scheduledRecoveryMillis", seconds * 1000)
                .put("recoveryOperationEndElapsedMillis", started))
        }
    }

    fun observeWeakReferences(label: String, references: List<WeakReference<*>>, forceGc: Boolean) {
        if (forceGc) repeat(5) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            SystemClock.sleep(200)
        }
        sample(label, extra = JSONObject().put("weakReferenceCount", references.size)
            .put("stillReachable", references.count { it.get() != null })
            .put("interpretation", if (forceGc) "Separate diagnostic collectibility only; survival alone does not identify a GC root" else "Natural recovery; survival may reflect deferred GC"), forcedGc = forceGc)
    }
}
