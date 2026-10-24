package io.secure.keyboard.diagnostics

import io.secure.keyboard.OfflineKeyboardService
import io.secure.keyboard.voice.OfflineVoiceSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Capture before onDestroy clears owners; wait off main before deleting an isolated filesystem. */
internal fun sessionWorkers(service: OfflineKeyboardService): List<ExecutorService> {
    val session = service.session
    val voiceWorker = session.voice?.let {
        OfflineVoiceSession::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(it) as ExecutorService
    }
    return listOfNotNull(session.worker, session.emojiWorker, session.voiceEditorWorker,
        session.aiWorker, session.translationWorker, voiceWorker)
}
internal fun awaitStoppedWorkers(workers: List<ExecutorService>) {
    for (worker in workers) check(worker.awaitTermination(30, TimeUnit.SECONDS)) { "Session worker did not terminate before fixture deletion" }
}
