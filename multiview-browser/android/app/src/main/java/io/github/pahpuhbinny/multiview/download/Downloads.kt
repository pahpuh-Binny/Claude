package io.github.pahpuhbinny.multiview.download

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The queue of videos being saved to the vault. */
object Downloads {

    enum class State { QUEUED, DOWNLOADING, REBUILDING, ENCRYPTING, DONE, FAILED, CANCELLED }

    class Job(
        val candidate: MediaCandidate,
        val title: String,
        val userAgent: String,
        val referer: String,
        val allowHttp: Boolean,
    ) {
        val id: String = UUID.randomUUID().toString().replace("-", "")
        @Volatile var state = State.QUEUED
        /** 0..1, or -1 when unknown. */
        @Volatile var progress = -1f
        @Volatile var message = ""
        @Volatile var cancelled = false
        @Volatile var bytes = 0L

        val finished get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED

        fun statusText(): String = when (state) {
            State.QUEUED -> "Waiting…"
            State.DOWNLOADING -> "Downloading" + pct()
            State.REBUILDING -> "Rebuilding a clean video file" + pct()
            State.ENCRYPTING -> "Encrypting into the vault" + pct()
            State.DONE -> "Saved to the vault"
            State.FAILED -> "Failed: $message"
            State.CANCELLED -> "Cancelled"
        }

        private fun pct() = if (progress >= 0) " ${(progress * 100).toInt()}%" else "…"
    }

    val jobs = CopyOnWriteArrayList<Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var posted = false

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Tells the screens something changed, at most ~4 times a second. */
    fun changed() {
        synchronized(this) {
            if (posted) return
            posted = true
        }
        main.postDelayed({
            synchronized(this) { posted = false }
            listeners.forEach { it() }
        }, 250)
    }

    fun enqueue(context: Context, job: Job) {
        jobs.add(job)
        changed()
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(job: Job) {
        job.cancelled = true
        if (job.state == State.QUEUED) job.state = State.CANCELLED
        changed()
    }

    fun clearFinished() {
        jobs.removeAll { it.finished }
        changed()
    }

    fun next(): Job? = jobs.firstOrNull { it.state == State.QUEUED && !it.cancelled }

    val active get() = jobs.filter { !it.finished }
}
