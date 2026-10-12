package io.secure.keyboard.voice

/**
 * One in-process import, one current observer, one latest state. All public operations and all
 * state/observer access run on the main thread. Work and posted completion never capture an
 * observer: detaching removes the sole UI reference even while a provider or import lock is stuck.
 *
 * This bounds outstanding work, not provider latency. Do not use interruption as a promise that
 * an arbitrary document provider will stop. Nothing here is persisted or resumes after process death.
 */
internal class ModelImportCoordinator(
    private val execute: (() -> Unit) -> Unit,
    private val postToMain: (() -> Unit) -> Unit,
    private val requireMainThread: () -> Unit,
) {
    sealed class State {
        object Idle : State()
        object Running : State()
        object Success : State()
        data class Failure(val message: String) : State()
    }

    private var current: State = State.Idle
    private var observer: ((State) -> Unit)? = null

    val state: State
        get() {
            requireMainThread()
            return current
        }

    fun attach(callback: (State) -> Unit) {
        requireMainThread()
        observer = callback
        callback(current)
    }

    fun detach(callback: (State) -> Unit) {
        requireMainThread()
        // A late old Activity.onDestroy must not detach a newer started Activity.
        if (observer === callback) observer = null
    }

    /** work must own application-scoped resources only; never pass an Activity/View closure. */
    fun start(work: () -> Unit): Boolean {
        requireMainThread()
        if (current === State.Running) return false
        current = State.Running
        observer?.invoke(current)
        try {
            execute {
                // Keep only a message, not a Throwable, provider, URI or arbitrary result history.
                val completion = runCatching(work).fold(
                    onSuccess = { State.Success },
                    onFailure = { State.Failure(it.message ?: "模型导入失败，请重试") },
                )
                postToMain { complete(completion) }
            }
        } catch (error: Exception) {
            // A rejected worker submission must allow retry rather than leave Running forever.
            complete(State.Failure(error.message ?: "模型导入失败，请重试"))
        }
        return true
    }

    private fun complete(completion: State) {
        requireMainThread()
        current = completion
        observer?.invoke(current)
    }
}
