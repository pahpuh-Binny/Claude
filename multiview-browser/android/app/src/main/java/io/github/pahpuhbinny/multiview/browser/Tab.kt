package io.github.pahpuhbinny.multiview.browser

import android.graphics.Bitmap
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import io.github.pahpuhbinny.multiview.download.MediaCandidate

/** What the page script last reported about one frame's videos. */
data class MainVideo(
    val src: String,
    val time: Double,
    val duration: Double,
    val width: Int,
    val height: Int,
    val playing: Boolean,
    /** Position on screen in device pixels (top frame only, else -1). */
    val x: Float,
    val y: Float,
    val rectW: Float,
    val rectH: Float,
)

data class FrameMedia(
    val isTop: Boolean,
    val playing: Int,
    val audible: Boolean,
    val count: Int,
    val main: MainVideo?,
)

class Tab(val id: Int) {
    lateinit var webView: BrowserWebView
    lateinit var frame: TileFrame

    var title: String = ""
    var url: String = "about:blank"
    @Volatile var pageHost: String = ""
    var progress: Int = 100
    var favicon: Bitmap? = null
    var thumbnail: Bitmap? = null
    var desktopMode = false
    var muted = false
    /** Paused from the notification, headset or lock screen. */
    var held = false
    var blockedCount = 0
    var popupsBlocked = 0
    /** Set while a warning page is showing instead of [url]. */
    var interstitial = false
    /** The http:// address we upgraded to https://, so we can offer it if https fails. */
    var upgradedFrom: String? = null
    var pendingSeek: Double = -1.0
    /** Restored tabs load only when you first look at them. */
    var pendingUrl: String? = null
    /** A pop-up you opened by tapping: close it if it turns out to be an ad. */
    @Volatile var checkFirstNav = false
    var openerId = 0
    /** Main-frame address currently being fetched (set off the UI thread). */
    @Volatile var loadingUrl: String = ""
    /** Main-frame address replaced by the dangerous-site warning. */
    @Volatile var blockedMainUrl: String = ""
    /** Address a browser warning or error page is standing in for. */
    var errorFor: String? = null
    var upgradeAttempts = 0
    var scriptHandler: ScriptHandler? = null
    var startPageVisible = false
    /** The tab group this tab is in (0 = none). */
    var groupId = 0
    /** Its group's colour, or 0 when ungrouped (kept in sync by TabManager). */
    var groupColor = 0
    val createdAt = System.currentTimeMillis()

    val frames = LinkedHashMap<JavaScriptReplyProxy, FrameMedia>()
    val candidates = LinkedHashMap<String, MediaCandidate>()

    val isPlaying get() = frames.values.any { it.playing > 0 }
    val isAudible get() = frames.values.any { it.audible }
    val hasMedia get() = frames.isNotEmpty()
    val isBlank get() = url.isEmpty() || url == "about:blank"
    val isSecure get() = url.startsWith("https://", ignoreCase = true)

    /** The most prominent video in the tab: playing beats paused, top frame and big beat small. */
    fun mainVideo(): MainVideo? {
        var best: MainVideo? = null
        var bestScore = -1.0
        for (f in frames.values) {
            val m = f.main ?: continue
            val area = maxOf(m.width * m.height.toDouble(), m.rectW * m.rectH.toDouble())
            val score = (if (m.playing) 1e12 else 0.0) + (if (f.isTop) 1e6 else 0.0) + area
            if (score > bestScore) {
                bestScore = score
                best = m
            }
        }
        return best
    }

    fun displayTitle(): String = when {
        title.isNotBlank() -> title
        isBlank -> "New tab"
        else -> UrlTools.hostOf(url)
    }

    fun postToFrames(json: String) {
        val dead = ArrayList<JavaScriptReplyProxy>()
        for (p in frames.keys) {
            try {
                p.postMessage(json)
            } catch (e: Exception) {
                dead.add(p)
            }
        }
        dead.forEach { frames.remove(it) }
    }

    fun resetPage() {
        frames.clear()
        candidates.clear()
        blockedCount = 0
        popupsBlocked = 0
        favicon = null
        interstitial = false
    }
}
