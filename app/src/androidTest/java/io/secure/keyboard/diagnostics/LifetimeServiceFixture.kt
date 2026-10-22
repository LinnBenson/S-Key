package io.secure.keyboard.diagnostics

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.test.ServiceTestCase
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import io.secure.keyboard.MainActivity
import io.secure.keyboard.OfflineKeyboardService
import io.secure.keyboard.data.SettingsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
internal class LifetimeServiceFixture(private val instrumentation: Instrumentation) : AutoCloseable {
    val sandbox = LifetimeSandbox(instrumentation.targetContext)
    private class App(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
    }
    private class Harness : ServiceTestCase<OfflineKeyboardService>(OfflineKeyboardService::class.java) {
        fun create(app: Application): OfflineKeyboardService {
            setContext(app); setApplication(app); setUp()
            startService(Intent(app, OfflineKeyboardService::class.java))
            return service
        }
        fun close() = tearDown()
    }
    private val app = App(sandbox)
    private val harness = Harness()
    lateinit var service: OfflineKeyboardService
        private set
    lateinit var activity: Activity
        private set
    lateinit var host: Host
        private set

    class Host(view: View) : BaseInputConnection(view, true) {
        @Volatile var reads = 0
        @Volatile var commits = 0
        val beforeReadCount = AtomicInteger()
        @Volatile var beforeReadGate: (() -> Unit)? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Host surrounding-text IPC must stay off main" }
            reads++; beforeReadCount.incrementAndGet(); beforeReadGate?.invoke(); return "hello"
        }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Host IPC must stay off main" }
            reads++
            return ExtractedText().apply { startOffset = 0; selectionStart = 0; selectionEnd = 0 }
        }
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { commits++; return true }
    }

    fun open() {
        check(!sandbox.root.exists()) { "Preserve prior incomplete fixture first" }
        sandbox.root.mkdirs()
        SettingsStore(app).apply {
            englishCompletion = true; fourCodeAutoCommit = false; vibration = false; keySound = false
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.checkedMain {
            service = harness.create(app)
            activity.setContentView(service.onCreateInputView())
            replaceHost()
        }
        await("Dictionary loaded", 60_000) { field<Boolean>("loaded") }
        drain(field("worker"))
    }

    /** Main-thread-only. Real InputMethodService startInput callback with a controlled host. */
    fun replaceHost(restartView: Boolean = true) {
        host = Host(View(activity))
        val info = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 }
        service.onCreateInputMethodInterface().startInput(host, info)
        if (restartView) service.onStartInputView(info, false)
        set("active", true)
    }

    fun drain(executor: ExecutorService) {
        val marker = CountDownLatch(1)
        executor.execute { marker.countDown() }
        check(marker.await(60, TimeUnit.SECONDS)) { "Worker did not drain" }
        instrumentation.waitForIdleSync()
    }

    fun await(message: String, millis: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var done = false
            instrumentation.checkedMain { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        error(message)
    }

    @Suppress("UNCHECKED_CAST") fun <T> field(name: String): T = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.get(service) as T
    fun set(name: String, value: Any?) = io.secure.keyboard.SessionTestAccess.field(name).apply { isAccessible = true }.set(service, value)
    fun invoke(name: String): Any? = io.secure.keyboard.SessionTestAccess.method(name).apply { isAccessible = true }.invoke(service)

    override fun close() {
        instrumentation.checkedMain {
            if (::service.isInitialized) harness.close()
            if (::activity.isInitialized) activity.finish()
        }
        instrumentation.waitForIdleSync()
        sandbox.close()
    }
}

/** Preserve assertion/error evidence without killing the target application's main thread. */
internal fun Instrumentation.checkedMain(block: () -> Unit) {
    check(Looper.myLooper() != Looper.getMainLooper()) { "checkedMain must be called from the instrumentation thread" }
    var failure: Throwable? = null
    runOnMainSync {
        try { block() } catch (error: Throwable) { failure = error }
    }
    failure?.let { throw it }
}
