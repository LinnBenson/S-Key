package io.secure.keyboard.diagnostics

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.secure.keyboard.data.ContentStore
import io.secure.keyboard.ui.CandidateStrip
import io.secure.keyboard.ui.ContentEntryRow
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exact model/queue ownership diagnostics, intentionally separate from uninstrumented IME PSS. */
@Suppress("DEPRECATION")
class ServiceOwnershipDiagnosticTest : InstrumentationTestCase() {
    private lateinit var fixture: LifetimeServiceFixture
    private lateinit var probe: LifetimeMemoryProbe
    override fun setUp() {
        super.setUp()
        fixture = LifetimeServiceFixture(instrumentation).also { it.open() }
        probe = LifetimeMemoryProbe(instrumentation.targetContext, "service-ownership")
    }
    override fun tearDown() {
        try { probe.stop(); fixture.close() } finally { super.tearDown() }
    }

    fun testPairedSlowHostThousandRefreshesNoForcedGc() { pairedSlowHost(false) }
    fun testPairedSlowHostThousandRefreshesForcedGcOwnershipOnly() {
        val retired = pairedSlowHost(true)
        // Let the producer's interpreter/JIT stack slots unwind before checking collectibility.
        probe.observeWeakReferences("released_hosts_after_producer_return", retired, true)
        assertTrue("Retired host connections remain reachable", retired.all { it.get() == null })
    }

    /** Same real executor, same blocked InputConnection and same 1,000 refresh stimulus on B/C. */
    private fun pairedSlowHost(forcedGc: Boolean): List<WeakReference<*>> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = mutableListOf<WeakReference<*>>()
        val obsoleteReadCounters = mutableListOf<AtomicInteger>()
        try {
            instrumentation.checkedMain {
                fixture.host.beforeReadGate = {
                    entered.countDown()
                    check(release.await(120, TimeUnit.SECONDS)) { "Controlled host gate exceeded observation window" }
                }
                fixture.set("chinese", false)
                fixture.invoke("refreshCandidates")
            }
            assertTrue("First host request must actually block the production worker", entered.await(15, TimeUnit.SECONDS))
            repeat(10) {
                instrumentation.checkedMain {
                    retired.add(WeakReference(fixture.host))
                    if (it > 0) obsoleteReadCounters.add(fixture.host.beforeReadCount)
                    fixture.replaceHost(restartView = false)
                    fixture.set("chinese", false)
                    repeat(100) { fixture.invoke("refreshCandidates") }
                }
            }
            val worker = fixture.field<ExecutorService>("worker")
            probe.sample("same_real_worker_slow_host_gated", extra = queueState(worker)
                .put("requestedDerivedRefreshes", 1000).put("retiredHosts", retired.size)
                .put("retiredHostsStillReachable", retired.count { it.get() != null }), forcedGc = forcedGc)
            if (forcedGc) probe.observeWeakReferences("gated_old_host_collectibility", retired, true)
            release.countDown()
            // A mailbox can schedule a continuation after an ordinary executor marker. Require
            // its optional reflected scheduled flag false as well as the current candidate query.
            fixture.await("Latest candidate query completes and optional mailbox drains") {
                fixture.field<String>("candidateQuery") == "hello" &&
                    fixture.field<Int>("candidateGeneration") == fixture.field<Int>("generation") &&
                    fixture.field<Any?>("candidateConnection") === fixture.host && !mailboxScheduled()
            }
            fixture.drain(worker)
            assertEquals("Obsolete host connections must never be queried after release", 0, obsoleteReadCounters.sumOf { it.get() })
            assertTrue("Current host must be queried", fixture.host.beforeReadCount.get() > 0)
            assertEquals("Refresh stress never authorizes a commit", 0, fixture.host.commits)
            probe.sample("same_real_worker_slow_host_drained", extra = queueState(worker)
                .put("obsoleteHostReadCount", obsoleteReadCounters.sumOf { it.get() }), forcedGc = forcedGc)
            if (forcedGc) probe.observeWeakReferences("released_old_host_collectibility", retired, true)
        } finally { release.countDown() }
        return retired
    }

    private fun mailboxSnapshot(): Any? {
        val field = fixture.service.session.javaClass.declaredFields.firstOrNull { it.name == "candidateQueries" } ?: return null
        val mailbox = field.apply { isAccessible = true }.get(fixture.service.session)
        return mailbox.javaClass.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(mailbox)
    }

    private fun mailboxScheduled(): Boolean {
        val snapshot = mailboxSnapshot() ?: return false
        return snapshot.javaClass.getDeclaredMethod("getScheduled").invoke(snapshot) as Boolean
    }

    private fun queueState(worker: ExecutorService): JSONObject {
        val data = JSONObject()
        val queueSize = executorQueueSize(worker)
        data.put("realExecutorQueueSize", queueSize ?: JSONObject.NULL)
        data.put("realExecutorQueueInspectable", queueSize != null)
        val snapshot = mailboxSnapshot()
        if (snapshot == null) data.put("mailboxPresent", false)
        else {
            data.put("mailboxPresent", true)
            for (name in listOf("Running", "Pending", "Scheduled", "Closed")) {
                data.put("mailbox$name", snapshot.javaClass.getDeclaredMethod("get$name").invoke(snapshot))
            }
        }
        return data
    }

    /** Optional attribution only: fail honestly if the platform hides its executor delegate. */
    private fun executorQueueSize(executor: ExecutorService): Int? = runCatching {
        var current: Any = executor
        repeat(5) {
            if (current is ThreadPoolExecutor) return@runCatching (current as ThreadPoolExecutor).queue.size
            var type: Class<*>? = current.javaClass
            var delegate: java.lang.reflect.Field? = null
            while (type != null && delegate == null) {
                delegate = type.declaredFields.firstOrNull { it.name == "e" && ExecutorService::class.java.isAssignableFrom(it.type) }
                type = type.superclass
            }
            current = requireNotNull(delegate).apply { isAccessible = true }.get(current)
        }
        null
    }.getOrNull()

    fun testHiddenCandidatePresentationReleasesAllTenThousandLogicalRows() {
        instrumentation.checkedMain {
            fixture.set("chinese", true)
            fixture.set("code", "code")
            fixture.set("candidateTexts", (0 until 10_000).map { "synthetic-$it" })
            fixture.invoke("renderHeader")
        }
        val strip = fixture.field<CandidateStrip>("candidateStrip")
        val displayed = CandidateStrip::class.java.getDeclaredField("displayed").apply { isAccessible = true }
        val rowField = CandidateStrip::class.java.getDeclaredField("row").apply { isAccessible = true }
        val before = (displayed.get(strip) as List<*>).size
        assertEquals(10_000, before)
        instrumentation.checkedMain {
            fixture.service.onFinishInputView(false)
            fixture.service.onFinishInput()
        }
        instrumentation.waitForIdleSync()
        val remaining = (displayed.get(strip) as List<*>).size
        val labels = (rowField.get(strip) as LinearLayout).childCount
        probe.sample("hidden_candidate_presentation", extra = JSONObject().put("logicalRowsBefore", before)
            .put("logicalRowsAfterFinish", remaining).put("attachedCandidateLabelsAfterFinish", labels)
            .put("releasedPresentationInvariant", remaining == 0 && labels == 0))
        // Baseline B is expected to reveal this invariant failure; C's reviewer must require true.
    }

    /** One bounded shared B/C ownership check; synthetic content never touches the OS clipboard. */
    fun testHiddenClipboardAndPhraseRowsAfterIsolatedCategoryClearNoForcedGc() {
        val content = fixture.field<ContentStore>("content")
        content.clearClipboard()
        content.clearPhrases()
        for (panel in listOf("clipboard", "phrases")) {
            repeat(10) { index ->
                val synthetic = buildString(4096) {
                    append("lifetime-"); append(panel); append('-'); append(index); append(':')
                    while (length < 4096) append('x')
                }
                if (panel == "clipboard") content.addClipboard(synthetic) else content.addPhrase(synthetic)
            }
            instrumentation.checkedMain {
                fixture.replaceHost(restartView = false)
                fixture.set("panel", panel)
                fixture.invoke("renderBody")
                // onStartInputView replaces the root after the fixture's initial attachment.
                val currentRoot = fixture.field<ViewGroup>("root")
                (currentRoot.parent as? ViewGroup)?.removeView(currentRoot)
                fixture.activity.setContentView(currentRoot)
            }
            fixture.await("Asynchronous content adapter has visible rows") {
                fun loaded(view: View): Boolean =
                    (view is android.widget.ListView && view.adapter?.count == 10 && view.childCount > 0) ||
                        (view is ViewGroup && (0 until view.childCount).any { loaded(view.getChildAt(it)) })
                loaded(fixture.field<ViewGroup>("body"))
            }
            probe.stage = "${panel}_visible"
            val visible = contentPresentationOwnership()
            assertEquals("All ten synthetic rows must be available through the virtual list", 10, visible.getInt("logicalRowCount"))
            assertTrue(visible.getInt("contentEntryRowCount") in 1..10)
            assertEquals("Each visible row must own its full synthetic text", 4096 * visible.getInt("contentEntryRowCount"), visible.getInt("strongFullTextUtf16CodeUnits"))
            probe.sample("content_rows_visible", extra = visible)
            instrumentation.checkedMain {
                fixture.service.onFinishInputView(false)
                fixture.service.onFinishInput()
            }
            instrumentation.waitForIdleSync()
            probe.stage = "${panel}_hidden"
            probe.sample("content_rows_after_hide_and_finish", extra = contentPresentationOwnership())
            if (panel == "clipboard") content.clearClipboard() else content.clearPhrases()
            assertEquals("Isolated category storage must be empty after clear", 0,
                if (panel == "clipboard") content.clipboard().size else content.phrases().size)
            probe.stage = "${panel}_hidden_category_cleared"
            val hidden = contentPresentationOwnership().put("isolatedStoredRowCountAfterClear", 0)
            hidden.put("releasedHiddenContentPresentationInvariant", hidden.getInt("contentEntryRowCount") == 0 &&
                hidden.getInt("strongFullTextUtf16CodeUnits") == 0)
            probe.sample("content_rows_after_category_clear", extra = hidden)
            // Record B's direct ownership violation without prematurely aborting the paired test.
            // The C reviewer must require releasedHiddenContentPresentationInvariant=true.
        }
    }

    private fun contentPresentationOwnership(): JSONObject {
        var result = JSONObject()
        instrumentation.checkedMain {
            val body = fixture.field<ViewGroup>("body")
            val fullText = ContentEntryRow::class.java.getDeclaredField("fullText").apply { isAccessible = true }
            var logicalRows = 0
            var rowCount = 0
            var fullTextUnits = 0
            var nonemptyFullTextFields = 0
            fun inspect(view: View) {
                if (view is android.widget.ListView) logicalRows += view.adapter?.count ?: 0
                if (view is ContentEntryRow) {
                    rowCount++
                    val text = fullText.get(view) as String
                    fullTextUnits += text.length
                    if (text.isNotEmpty()) nonemptyFullTextFields++
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
            }
            inspect(body)
            result = JSONObject().put("logicalRowCount", logicalRows).put("bodyDirectChildCount", body.childCount)
                .put("contentEntryRowCount", rowCount).put("nonemptyStrongFullTextFieldCount", nonemptyFullTextFields)
                .put("strongFullTextUtf16CodeUnits", fullTextUnits)
                .put("ownershipPath", "OfflineKeyboardService.body -> descendant ContentEntryRow.fullText")
                .put("textPayloadLogged", false).put("osClipboardWritten", false)
        }
        return result
    }
}
