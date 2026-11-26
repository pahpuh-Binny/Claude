package io.github.pahpuhbinny.multiview.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.pahpuhbinny.multiview.browser.TabManager
import java.lang.ref.WeakReference

/**
 * Connects the tabs to the background-playback service. The service runs
 * (with its notification) while any tab is playing or paused from the
 * notification, which keeps Android from closing the app in the background.
 */
object PlaybackHub {

    data class Summary(val playingTabs: Int, val heldTabs: Int, val titles: List<String>, val anyAudible: Boolean) {
        val active get() = playingTabs > 0 || heldTabs > 0
    }

    private var managerRef = WeakReference<TabManager>(null)
    private val main = Handler(Looper.getMainLooper())
    @Volatile var appVisible = false
    internal var service: PlaybackService? = null
    private var stopPending: Runnable? = null

    fun attach(manager: TabManager) {
        managerRef = WeakReference(manager)
    }

    fun summary(): Summary {
        val m = managerRef.get() ?: return Summary(0, 0, emptyList(), false)
        val playing = m.tabs.filter { it.isPlaying }
        val held = m.tabs.filter { it.held && !it.isPlaying }
        return Summary(
            playing.size, held.size,
            (playing + held).map { it.displayTitle() }.distinct().take(4),
            playing.any { it.isAudible }
        )
    }

    fun update(context: Context) {
        val s = summary()
        if (s.active) {
            stopPending?.let { main.removeCallbacks(it) }
            stopPending = null
            val svc = service
            if (svc != null) {
                svc.refresh(s)
            } else if (appVisible) {
                try {
                    ContextCompat.startForegroundService(
                        context.applicationContext, Intent(context.applicationContext, PlaybackService::class.java)
                    )
                } catch (e: Exception) {
                    // Android won't start it from the background; it'll start next time you open the app.
                }
            }
        } else if (service != null && stopPending == null) {
            // Give it a moment: sites often pause briefly between videos.
            val r = Runnable {
                stopPending = null
                if (!summary().active) service?.stopPlayback()
            }
            stopPending = r
            main.postDelayed(r, if (appVisible) 4_000 else 45_000)
        }
    }

    fun pauseAll() = managerRef.get()?.pauseAll()

    fun playAll() = managerRef.get()?.playAll()

    /** Pause everything and forget it, so the notification goes away. */
    fun stopAll() {
        managerRef.get()?.let {
            it.pauseAll()
            it.releaseHolds()
        }
    }

    fun hasHeld() = managerRef.get()?.anyHeld() == true
}
