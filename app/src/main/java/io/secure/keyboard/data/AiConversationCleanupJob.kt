package io.secure.keyboard.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Best-effort deadline, supplemented by process startup and foreground cleanup. */
class AiConversationCleanupJob : JobService() {
    private var generation = 0
    override fun onStartJob(params: JobParameters): Boolean {
        val ticket = ++generation
        ConversationIo.executor.execute {
            val retry = runCatching { AiConversationStore(applicationContext).list() }.isFailure
            android.os.Handler(mainLooper).post { if (ticket == generation) jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { generation++; return true }
    companion object {
        internal const val ID = 0x534B02
        fun schedule(context: Context, expires: Long?) {
            if (context.noBackupFilesDir != context.createPackageContext(context.packageName, 0).noBackupFilesDir) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (expires == null) { scheduler.cancel(ID); return }
            if (scheduler.getPendingJob(ID)?.extras?.getLong("expires") == expires) return
            val delay = (expires - System.currentTimeMillis()).coerceAtLeast(1000L)
            val extras = android.os.PersistableBundle().apply { putLong("expires", expires) }
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, AiConversationCleanupJob::class.java))
                .setMinimumLatency(delay).setOverrideDeadline(delay + 60_000L).setExtras(extras).build())
        }
    }
}
