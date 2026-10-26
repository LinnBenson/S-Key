package io.secure.keyboard.voice

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Process-owned model transfer. No Activity, View, persistent URI grant, service or saved history. */
internal object ModelImportTransfer {
    private val main = Handler(Looper.getMainLooper())
    private val coordinator = ModelImportCoordinator(
        execute = { work ->
            // One accepted import owns one short-lived thread, never an idle process-lived pool.
            // A stalled provider owns at most this single app-scoped worker. No interrupt claim.
            Thread({ work() }, "model-import").start()
        },
        postToMain = { completion -> main.post { completion() }; Unit },
        requireMainThread = { check(Looper.myLooper() === Looper.getMainLooper()) },
    )

    val state: ModelImportCoordinator.State get() = coordinator.state

    fun attach(observer: (ModelImportCoordinator.State) -> Unit) = coordinator.attach(observer)
    fun detach(observer: (ModelImportCoordinator.State) -> Unit) = coordinator.detach(observer)

    fun start(context: Context, tree: Uri): Boolean {
        val app = context.applicationContext
        // Defined here, outside the Activity: this closure captures only the app and selected URI.
        return coordinator.start { ModelRepository(app).importTree(tree) }
    }
}
