package io.github.pahpuhbinny.multiview.cast

import android.content.Context
import android.net.Uri
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import io.github.pahpuhbinny.multiview.download.MediaCandidate
import java.util.concurrent.Executors

/**
 * Chromecast support. Works only on phones with Google Play services; on
 * other phones the cast button stays hidden and "Mirror screen" is offered.
 */
object CastSupport {

    var context: CastContext? = null
        private set
    private var pending: Pair<MediaCandidate, Double>? = null
    private val readyCallbacks = ArrayList<() -> Unit>()
    var onStatus: ((String) -> Unit)? = null

    fun init(app: Context) {
        try {
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx ->
                    context = ctx
                    ctx.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                    readyCallbacks.forEach { it() }
                    readyCallbacks.clear()
                }
        } catch (e: Exception) {
            // No Google Play services: casting unavailable.
        }
    }

    fun whenReady(cb: () -> Unit) {
        if (context != null) cb() else readyCallbacks.add(cb)
    }

    fun setUpButton(app: Context, button: MediaRouteButton) {
        whenReady {
            try {
                CastButtonFactory.setUpMediaRouteButton(app, button)
            } catch (e: Exception) {
            }
        }
    }

    val isConnected: Boolean
        get() = context?.sessionManager?.currentCastSession?.isConnected == true

    val deviceName: String?
        get() = context?.sessionManager?.currentCastSession?.castDevice?.friendlyName

    /** Plays [media] on the TV. Returns false if no TV is connected yet (it'll start once one is). */
    fun cast(media: MediaCandidate, startAtSeconds: Double = 0.0): Boolean {
        val session = context?.sessionManager?.currentCastSession
        if (session == null || !session.isConnected) {
            pending = media to startAtSeconds
            return false
        }
        load(session, media, startAtSeconds)
        return true
    }

    fun stop() {
        context?.sessionManager?.endCurrentSession(true)
    }

    private fun load(session: CastSession, media: MediaCandidate, startAt: Double) {
        val client = session.remoteMediaClient ?: return
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, media.title.ifBlank { Uri.parse(media.pageUrl).host ?: "Video" })
            putString(MediaMetadata.KEY_SUBTITLE, Uri.parse(media.pageUrl).host ?: "")
            if (media.poster.startsWith("http")) addImage(WebImage(Uri.parse(media.poster)))
        }
        val info = MediaInfo.Builder(media.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.mimeType)
            .setMetadata(meta)
            .build()
        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime((startAt * 1000).toLong().coerceAtLeast(0))
                .build()
        )
        onStatus?.invoke("Casting to ${session.castDevice?.friendlyName ?: "TV"}")
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            pending?.let { (m, t) -> load(session, m, t) }
            pending = null
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            pending?.let { (m, t) -> load(session, m, t) }
            pending = null
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pending = null
            onStatus?.invoke("Couldn't connect to the TV")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
}
