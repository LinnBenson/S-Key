package io.secure.keyboard.ime

import android.os.Handler
import android.view.inputmethod.InputConnection
import io.secure.keyboard.ChineseSpacing
import io.secure.keyboard.concurrent.LatestTaskMailbox
import java.util.concurrent.Executors

/** Two characters on either side, prefetched off main. Never rewrites an already committed edit. */
internal class SpacingContext(private val main: Handler) {
    internal val worker = Executors.newSingleThreadExecutor()
    private val queries = LatestTaskMailbox(worker)
    @Volatile private var revision = 0L
    private var target: InputConnection? = null
    private var before = ""
    private var after = ""
    private var ready = false

    fun invalidate() {
        revision++
        queries.clear()
        target = null
        before = ""; after = ""; ready = false
    }

    fun has(target: InputConnection?) = ready && this.target === target

    fun prefetch(connection: InputConnection, composing: String, stillCurrent: () -> Boolean) {
        if (target !== connection) invalidate()
        target = connection
        val token = ++revision
        queries.submit {
            if (token != revision) return@submit
            val context = runCatching {
                val left = connection.getTextBeforeCursor(composing.length + 2, 0)?.toString()
                val right = connection.getTextAfterCursor(2, 0)?.toString()
                if (left == null || right == null || !left.endsWith(composing)) null
                else left.dropLast(composing.length).takeLast(2) to right.take(2)
            }.getOrNull()
            if (token != revision) return@submit
            main.post {
                if (token == revision && target === connection && stillCurrent() && context != null) {
                    before = context.first; after = context.second; ready = true
                }
            }
        }
    }

    fun format(connection: InputConnection, value: String, canPlaceSuffix: Boolean): ChineseSpacing.Edit =
        if (has(connection)) ChineseSpacing.format(value, before, if (canPlaceSuffix) after else "")
        else ChineseSpacing.format(value)

    fun committed(connection: InputConnection, output: String, trailingSpace: Boolean) {
        revision++
        queries.clear()
        if (target !== connection) { before = ""; after = "" }
        target = connection
        before = (before + if (trailingSpace) output.dropLast(1) else output).takeLast(2)
        if (trailingSpace) after = (" " + after).take(2)
        ready = true
    }

    fun close() { invalidate(); queries.close(); worker.shutdownNow() }
}
