package io.secure.keyboard.diagnostics

import android.os.Bundle
import android.test.InstrumentationTestCase
import android.view.ViewGroup
import android.widget.ListView
import io.secure.keyboard.data.ContentStore
import java.util.concurrent.ExecutorService
import org.json.JSONObject
import java.io.File

@Suppress("DEPRECATION")
class PrivacyMemoryAuditTest : InstrumentationTestCase() {
    private fun containsSentinel(file: File, sentinel: String): Boolean = file.inputStream().use { input ->
        val bytes = ByteArray(64 * 1024)
        var tail = ""
        while (true) {
            val count = input.read(bytes)
            if (count < 0) break
            val chunk = tail + String(bytes, 0, count, Charsets.ISO_8859_1)
            if (chunk.contains(sentinel)) return@use true
            tail = chunk.takeLast(sentinel.length - 1)
        }
        false
    }

    fun testCleanupInterruptedSyntheticFixture() {
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "lifetime-dictionary-fixture")
        if (root.exists()) assertTrue(root.deleteRecursively())
        context.deleteSharedPreferences("lifetime-diagnostic-settings")
    }

    fun testSixtyPanelCyclesReleaseContentAndNeverPersistTypedSentinel() {
        val fixture = LifetimeServiceFixture(instrumentation)
        val probe = LifetimeMemoryProbe(instrumentation.targetContext, "privacy-panel-audit")
        try {
            fixture.open()
            val store = fixture.field<ContentStore>("content")
            // Exclude the emulator’s existing OS clipboard from this synthetic count.
            store.clearClipboard()
            repeat(12) {
                store.addClipboard("audit-clipboard-$it")
                store.addPhrase("audit-phrase-$it")
            }
            val sentinel = "SKEY_AUDIT_PRIVATE_20261012"
            val commit = io.secure.keyboard.SessionTestAccess.method("commit", String::class.java).apply { isAccessible = true }
            repeat(60) { cycle ->
                instrumentation.checkedMain {
                    fixture.replaceHost()
                    fixture.set("chinese", false)
                    commit.invoke(fixture.service, sentinel)
                    fixture.set("panel", if (cycle % 2 == 0) "clipboard" else "phrases")
                    fixture.invoke("renderBody")
                }
                fixture.drain(fixture.field<ExecutorService>("worker"))
                fixture.await("Content model must become visible") {
                    val body = fixture.field<ViewGroup>("body")
                    fun hasList(v: android.view.View): Boolean =
                        (v is ListView && v.adapter?.count == 12) ||
                            (v is ViewGroup && (0 until v.childCount).any { hasList(v.getChildAt(it)) })
                    hasList(body)
                }
                instrumentation.checkedMain {
                    fixture.service.onFinishInputView(false)
                    fixture.service.onFinishInput()
                    assertEquals(0, fixture.field<ViewGroup>("body").childCount)
                    assertEquals("", fixture.field<String>("code"))
                    assertEquals("", fixture.field<String>("candidateQuery"))
                    assertTrue(fixture.field<List<*>>("candidateTexts").isEmpty())
                    assertNull(fixture.field<Any?>("candidateConnection"))
                    assertEquals("", fixture.field<String>("voicePreview"))
                }
                fixture.drain(fixture.field("worker"))
                if (cycle in listOf(0, 9, 19, 39, 59)) {
                    probe.cycle = cycle + 1
                    probe.sample("after_finished_panel_cycle")
                }
            }
            assertFalse("Typed text must not enter isolated app files",
                fixture.sandbox.root.walkTopDown().filter { it.isFile }.any { containsSentinel(it, sentinel) })
            assertFalse("Typed text must not enter settings",
                fixture.sandbox.getSharedPreferences("settings", 0).all.values.any { it.toString().contains(sentinel) })
            assertEquals(12, store.clipboard().size)
            assertEquals(12, store.phrases().size)
        } finally {
            probe.stop()
            fixture.close()
        }
    }

    fun testSixtyAssistantToolCyclesReleaseEditorsAndNeverPersistDraft() {
        val fixture = LifetimeServiceFixture(instrumentation)
        val probe = LifetimeMemoryProbe(instrumentation.targetContext, "assistant-tools-audit")
        try {
            fixture.open()
            val open = io.secure.keyboard.SessionTestAccess.method("openTool", String::class.java).apply { isAccessible = true }
            val commit = io.secure.keyboard.SessionTestAccess.method("commitInputText", String::class.java).apply { isAccessible = true }
            repeat(60) { cycle ->
                instrumentation.checkedMain {
                    fixture.replaceHost()
                    open.invoke(fixture.service, if (cycle % 2 == 0) "ai" else "translate")
                    commit.invoke(fixture.service, "SKEY_PRIVATE_ASSISTANT_AUDIT_20261019")
                    for (tool in listOf("clipboard", "phrases", "emoji", "quickedit")) open.invoke(fixture.service, tool)
                    fixture.service.onFinishInputView(false)
                    fixture.service.onFinishInput()
                    assertNull(fixture.field<Any?>("translationTextView"))
                    assertNull(fixture.field<Any?>("assistantEditConnection"))
                    assertNull(fixture.field<Any?>("assistantVoicePanel"))
                    assertTrue(fixture.field<List<*>>("aiResults").isEmpty())
                }
                fixture.drain(fixture.field<ExecutorService>("worker"))
                if (cycle in listOf(0, 9, 29, 59)) {
                    probe.cycle = cycle + 1
                    probe.sample("finished_assistant_tool_cycle")
                }
            }
            val sentinel = "SKEY_PRIVATE_ASSISTANT_AUDIT_20261019"
            assertFalse(fixture.sandbox.root.walkTopDown().filter { it.isFile }.any { containsSentinel(it, sentinel) })
            assertFalse(fixture.sandbox.getSharedPreferences("settings", 0).all.values.any { it.toString().contains(sentinel) })
        } finally { probe.stop(); fixture.close() }
    }

    fun testTwelveNativeRecognizerSilenceCyclesWithoutMicrophone() {
        val context = instrumentation.targetContext
        val installed = io.secure.keyboard.voice.ModelRepository(context)
        val temporary = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = File(context.getExternalFilesDir(null), "privacy-audit-model")
        }
        val repo = if (installed.isInstalled()) installed else io.secure.keyboard.voice.ModelRepository(temporary)
        if (!repo.isInstalled()) {
            instrumentation.sendStatus(0, Bundle().apply { putString("audit_native", "NOT_RUN: no installed pinned model") })
            return
        }
        repo.verifyInstalled()
        val probe = LifetimeMemoryProbe(instrumentation.targetContext, "native-silence-audit")
        try {
            repeat(12) { cycle ->
                val engine = com.k2fsa.sherpa.onnx.OnlineRecognizer(config =
                    com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
                        featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
                        modelConfig = com.k2fsa.sherpa.onnx.OnlineModelConfig(
                            zipformer2Ctc = com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(model = repo.modelFile.absolutePath),
                            tokens = repo.tokensFile.absolutePath, numThreads = 2, provider = "cpu", debug = false)))
                try {
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(FloatArray(24000), 16000)
                        stream.inputFinished()
                        while (engine.isReady(stream)) engine.decode(stream)
                        // Inspect only length; never log recognition text.
                        assertEquals(0, engine.getResult(stream).text.trim().length)
                    } finally { stream.release() }
                } finally { engine.release() }
                probe.cycle = cycle + 1
                probe.sample("native_recognizer_released", full = false)
            }
        } finally { probe.stop() }
        instrumentation.sendStatus(0, Bundle().apply { putString("audit_native", "12 native silence cycles completed; no microphone opened") })
    }

    fun testCleanTemporaryModel() {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "privacy-audit-model")
        if (dir.exists()) assertTrue(dir.deleteRecursively())
    }

    /** Exports numeric diagnostic evidence only, never user text, snapshots or heap contents. */
    fun testStorageMetadataOnly() {
        val context = instrumentation.targetContext
        val values = JSONObject()
        for ((name, directory) in listOf("files" to context.filesDir, "cache" to context.cacheDir,
            "noBackup" to context.noBackupFilesDir, "preferences" to File(context.applicationInfo.dataDir, "shared_prefs"))) {
            val files = directory.walkTopDown().filter { it.isFile }.toList()
            values.put(name + "Bytes", files.sumOf { it.length() })
            values.put(name + "Files", files.size)
        }
        values.put("abandonedModelImports", context.noBackupFilesDir.listFiles()?.count {
            it.name.matches(Regex("mandarin-import-[0-9]+")) } ?: 0)
        values.put("contentTemporaryFile", File(context.noBackupFilesDir, "content/content.tmp").exists())
        instrumentation.sendStatus(0, Bundle().apply { putString("storage_metadata", values.toString()) })
    }

    fun testReportNumericDiagnostics() {
        val names = listOf("privacy-panel-audit", "assistant-tools-audit", "settings", "service-ownership", "dictionary", "native-silence-audit")
        for (name in names) {
            val file = File(instrumentation.targetContext.filesDir, "lifetime-$name.jsonl")
            if (!file.isFile) continue
            val rows = file.readLines().map { JSONObject(it) }.filter { it.optString("event") != "sampled_peak_tick" }
            for (row in rows) {
                val safe = JSONObject()
                for (key in listOf("event", "stage", "cycle", "pssKiB", "javaUsedBytes", "nativeAllocatedBytes", "threadCount", "fdCount", "forcedGcRequested"))
                    if (row.has(key)) safe.put(key, row.get(key))
                val extra = row.optJSONObject("extra")
                if (extra != null) {
                    val numeric = JSONObject()
                    extra.keys().forEach { key ->
                        val value = extra.get(key)
                        if (value is Number || value is Boolean) numeric.put(key, value)
                    }
                    safe.put("extra", numeric)
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("audit_metrics", safe.toString()) })
            }
        }
    }
}
