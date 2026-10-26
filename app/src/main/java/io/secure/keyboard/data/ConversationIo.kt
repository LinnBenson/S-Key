package io.secure.keyboard.data

import android.content.Context
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process-wide ordering for IME conversation I/O and expiry jobs; never runs on the caller. */
internal object ConversationIo {
    val executor = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
    private val cleaning = AtomicBoolean()
    fun cleanup(context: Context) {
        if (!cleaning.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try { runCatching { AiConversationStore(app).list() } }
            finally { cleaning.set(false) }
        }
    }
}
