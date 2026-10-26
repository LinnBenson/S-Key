package io.secure.keyboard.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** One replaceable job, no exact-alarm permission and no permanent background service. */
class ClipboardCleanupJob : JobService() {
    private var work: Thread? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = Thread {
            val retry = runCatching { ContentStore(this).trimClipboard() }.isFailure
            jobFinished(params, retry)
        }.also { it.start() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.interrupt(); work = null; return true }
    companion object {
        private const val ID = 0x534B01
        fun schedule(context: Context, expires: Long?) {
            // Isolated test/import contexts must not schedule a job against the real store.
            if (context.noBackupFilesDir != context.createPackageContext(context.packageName, 0).noBackupFilesDir) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (expires == null) { scheduler.cancel(ID); return }
            // Do not move an existing deadline forward on each history read.
            if (scheduler.getPendingJob(ID)?.extras?.getLong("expires") == expires) return
            val delay = (expires - System.currentTimeMillis()).coerceAtLeast(1000L)
            val extras = android.os.PersistableBundle().apply { putLong("expires", expires) }
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, ClipboardCleanupJob::class.java))
                .setMinimumLatency(delay).setOverrideDeadline(delay + 60_000).setExtras(extras).build())
        }
    }
}
