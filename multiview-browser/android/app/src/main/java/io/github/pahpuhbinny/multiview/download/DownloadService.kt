package io.github.pahpuhbinny.multiview.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.pahpuhbinny.multiview.MultiviewApp
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.vault.VaultActivity
import kotlin.concurrent.thread

/** Runs the download queue in the background with a progress notification. */
class DownloadService : Service() {

    @Volatile private var worker: Thread? = null
    private val lock = Any()
    private var lastStartId = 0
    private var lastNotify = 0L
    private val listener: () -> Unit = { updateNotification() }

    override fun onCreate() {
        super.onCreate()
        Downloads.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        synchronized(lock) {
            lastStartId = startId
            if (worker != null) return START_NOT_STICKY
            worker = thread(name = "downloads") {
                val wl = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Multiview:download")
                wl.setReferenceCounted(false)
                try {
                    while (true) {
                        val job = synchronized(lock) {
                            Downloads.next() ?: run {
                                worker = null
                                null
                            }
                        } ?: break
                        wl.acquire(4 * 60 * 60 * 1000L)
                        Pipeline.run(applicationContext, job)
                        finished(job)
                    }
                } finally {
                    if (wl.isHeld) wl.release()
                    val id = synchronized(lock) {
                        worker = null
                        lastStartId
                    }
                    // Only stops if no new download arrived in the meantime.
                    stopSelfResult(id)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(job: Downloads.Job?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VaultActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, MultiviewApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(job?.title?.ifBlank { null } ?: "Saving video")
            .setContentText(job?.statusText() ?: "Starting…")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        val p = job?.progress ?: -1f
        if (p >= 0) b.setProgress(1000, (p * 1000).toInt(), false) else b.setProgress(0, 0, true)
        val queued = Downloads.active.size - 1
        if (queued > 0) b.setSubText("$queued more waiting")
        return b.build()
    }

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 700) return
        lastNotify = now
        val job = Downloads.jobs.firstOrNull { !it.finished && it.state != Downloads.State.QUEUED } ?: return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(job))
        } catch (e: SecurityException) {
        }
    }

    private fun finished(job: Downloads.Job) {
        if (job.state != Downloads.State.DONE && job.state != Downloads.State.FAILED) return
        val n = NotificationCompat.Builder(this, MultiviewApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(if (job.state == Downloads.State.DONE) R.drawable.ic_lock else R.drawable.ic_warning)
            .setContentTitle(job.title.ifBlank { "Video" })
            .setContentText(job.statusText())
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1, Intent(this, VaultActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(job.id.hashCode(), n)
        } catch (e: SecurityException) {
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits background data work; stop cleanly and keep the queue.
        Downloads.jobs.filter { !it.finished }.forEach { Downloads.cancel(it) }
        stopSelf()
    }

    override fun onDestroy() {
        Downloads.removeListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
