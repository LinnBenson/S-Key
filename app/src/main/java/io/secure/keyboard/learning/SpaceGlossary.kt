package io.secure.keyboard.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.Handler
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Created only by an enabled, visible keyboard after a Chinese candidate was committed. */
internal class SpaceGlossary(
    private val main: Handler,
    private val backend: Backend,
    private var display: ((String?) -> Unit)?,
    private val durationMs: Long = 8000L
) : AutoCloseable {
    interface Backend : AutoCloseable { fun lookup(word: String, signal: CancellationSignal): String? }
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) {
        Thread(it, "skey-space-gloss").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val callbackToken = Any()
    @Volatile private var revision = 0L
    @Volatile private var closed = false
    private var task: FutureTask<Unit>? = null
    private var signal: CancellationSignal? = null
    var text: String? = null
        private set

    // All public methods run on the IME main thread. The worker only owns the backend.
    fun query(lookup: (Backend, CancellationSignal) -> String?) {
        if (closed) return
        val ticket = ++revision
        signal?.cancel(); task?.cancel(true); task?.let { executor.remove(it) }
        main.removeCallbacksAndMessages(callbackToken)
        text = null; display?.invoke(null)
        if (closed || ticket != revision) return
        val cancellation = CancellationSignal(); signal = cancellation
        val job = FutureTask<Unit> {
            val result = try { lookup(backend, cancellation) } catch (_: Exception) { null }
            synchronized(callbackToken) {
                if (!closed && ticket == revision && !cancellation.isCanceled) {
                    main.postAtTime({
                        if (!closed && ticket == revision && !cancellation.isCanceled) {
                            task = null; signal = null
                            text = result
                            display?.invoke(result)
                            if (result != null && !closed && ticket == revision) main.postAtTime({
                                if (!closed && ticket == revision) { text = null; display?.invoke(null) }
                            }, callbackToken, SystemClock.uptimeMillis() + durationMs)
                        }
                    }, callbackToken, SystemClock.uptimeMillis())
                }
            }
        }
        task = job; executor.execute(job)
    }

    fun queryBeforeCursor(committed: String? = null, readTail: () -> CharSequence?) {
        // Capture only a short eligible word, never the entire committed sentence.
        val preferred = committed?.takeIf { it.length in 2..4 && it.all { c -> c in '\u3400'..'\u9fff' } }
        query { backend, cancellation ->
            cancellation.throwIfCanceled()
            if (preferred != null) {
                val exact = backend.lookup(preferred, cancellation)
                cancellation.throwIfCanceled()
                if (exact != null) return@query exact
            }
            // Read host context only when the committed word did not match.
            val tail = readTail()?.takeLast(4)?.toString().orEmpty()
            cancellation.throwIfCanceled()
            var result: String? = null
            for (length in minOf(4, tail.length) downTo 2) {
                cancellation.throwIfCanceled()
                val word = tail.takeLast(length)
                if (word == preferred || word.any { it !in '\u3400'..'\u9fff' }) continue
                result = backend.lookup(word, cancellation)
                if (result != null) break
            }
            result
        }
    }

    override fun close() {
        if (closed) return
        synchronized(callbackToken) {
            closed = true; revision++
            main.removeCallbacksAndMessages(callbackToken)
        }
        signal?.cancel(); signal = null
        task?.cancel(true); task?.let { executor.remove(it) }; task = null
        text = null; display = null
        // Close on the owning worker, after any canceled SQLite operation unwinds.
        executor.execute { backend.close() }
        executor.shutdown()
    }

    internal fun awaitReleased(timeoutMs: Long) = executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    class SqliteBackend(context: Context) : Backend {
        companion object {
            private val filesLock = Any()
            private val openPaths = mutableMapOf<String, Int>()
        }
        private val app = context.applicationContext
        private var db: SQLiteDatabase? = null
        override fun lookup(word: String, signal: CancellationSignal): String? {
            signal.throwIfCanceled()
            if (word.length !in 2..12 || word.any { it !in '\u3400'..'\u9fff' }) return null
            val database = db ?: open(signal).also { db = it }
            signal.throwIfCanceled()
            return database.rawQuery("SELECT english FROM gloss WHERE word=?", arrayOf(word), signal).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
        private fun open(signal: CancellationSignal): SQLiteDatabase {
            val hash = app.assets.open("learning/sha256.txt").bufferedReader().use { it.readText().trim() }
            val dir = File(app.noBackupFilesDir, "space-glossary").apply { mkdirs() }
            val target = File(dir, "$hash.db")
            if (!target.isFile) {
                // Unique temporary file permits a rapidly reopened session to extract concurrently.
                val temporary = File.createTempFile("gloss-", ".tmp", dir)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    GZIPInputStream(app.assets.open("learning/glossary.bin")).use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                signal.throwIfCanceled()
                                val size = input.read(buffer)
                                if (size < 0) break
                                output.write(buffer, 0, size); digest.update(buffer, 0, size)
                            }
                            output.fd.sync()
                        }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it) } == hash)
                    signal.throwIfCanceled()
                    check(temporary.renameTo(target) || target.isFile)
                } finally { temporary.delete() }
            }
            signal.throwIfCanceled()
            return synchronized(filesLock) {
                signal.throwIfCanceled()
                val opened = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                openPaths[target.path] = (openPaths[target.path] ?: 0) + 1
                dir.listFiles().orEmpty().filter {
                    it.name.matches(Regex("[0-9a-f]{64}\\.db")) && it != target && it.path !in openPaths
                }.forEach { it.delete() }
                opened
            }
        }
        override fun close() = synchronized(filesLock) {
            db?.let {
                val path = it.path
                it.close()
                val remaining = (openPaths[path] ?: 1) - 1
                if (remaining == 0) openPaths.remove(path) else openPaths[path] = remaining
            }
            db = null
        }
    }
}
