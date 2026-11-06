package xyz.ghostwitness.app.online

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Jobs survive activity exit/reboot. Unmetered networks are the default definition of good internet. */
class UploadService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var work: Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = executor.submit {
            val retry = try { UploadEngine(applicationContext).run() } catch (_: Exception) { true }
            if (!Thread.currentThread().isInterrupted) jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(true); return true }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    companion object {
        private const val NOW = 4101
        private const val PERIODIC = 4102
        fun schedule(context: Context) {
            val settings = OnlineSettings(context)
            if (!settings.uploadsConfigured()) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            fun builder(id: Int) = JobInfo.Builder(id, ComponentName(context, UploadService::class.java))
                .setRequiredNetworkType(if (settings.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
            if (scheduler.getPendingJob(NOW) == null) scheduler.schedule(builder(NOW)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(builder(PERIODIC).setPeriodic(15 * 60_000L).build())
        }
        fun reconfigure(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(NOW); scheduler.cancel(PERIODIC)
            schedule(context)
        }
    }
}
