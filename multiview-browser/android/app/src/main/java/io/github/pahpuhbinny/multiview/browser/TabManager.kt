package io.github.pahpuhbinny.multiview.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.download.MediaCandidate
import io.github.pahpuhbinny.multiview.media.PlaybackHub
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.Locale

/**
 * Owns every tab and decides which ones are on screen. All methods run on
 * the main thread unless they say otherwise.
 */
class TabManager(
    private val activity: AppCompatActivity,
    private val tiles: TileLayout,
    val listener: Listener,
) : TileFrame.Actions {

    interface Listener {
        fun onTabsChanged()
        fun onFocusChanged(tab: Tab)
        fun onTabUpdated(tab: Tab)
        fun onMediaStateChanged()
        fun onEnterFullscreen(tab: Tab, view: View, callback: WebChromeClient.CustomViewCallback)
        fun onExitFullscreen()
        fun onPopupBlocked(tab: Tab, url: String)
        fun onExternalApp(tab: Tab, intent: Intent, fallbackUrl: String?)
        fun onDownloadRequested(tab: Tab, url: String, mimeType: String, contentDisposition: String)
        fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
        fun onProtectedMedia(tab: Tab, request: PermissionRequest)
        fun createStartPage(tab: Tab): View
    }

    val tabs = ArrayList<Tab>()
    var focused: Tab? = null
        private set
    val multiviewIds = LinkedHashSet<Int>()
    var inMultiview = false
        private set
    var chromeHidden = false
        set(v) {
            field = v
            arrange()
        }

    /** Hosts you chose to open over plain http, for this session. */
    val httpAllowed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    /** Hosts on a malware list you chose to open anyway, for this session. */
    val unsafeAllowed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private var nextId = 1
    val groups = ArrayList<TabGroup>()
    private var nextGroupId = 1
    private val main = Handler(Looper.getMainLooper())
    private val webViewUa: String by lazy { WebSettings.getDefaultUserAgent(activity) }
    private var cachedScript: String? = null
    private var mediaPosted = false

    init {
        PlaybackHub.attach(this)
        setUpServiceWorkers()
    }

    // ------------------------------------------------------------------ tabs

    fun tabById(id: Int) = tabs.firstOrNull { it.id == id }

    fun newTab(url: String?, focus: Boolean = true, index: Int = tabs.size, lazy: Boolean = false, group: TabGroup? = null): Tab {
        val tab = Tab(nextId++)
        tab.desktopMode = Prefs.desktopModeDefault
        if (group != null && group in groups) {
            tab.groupId = group.id
            tab.groupColor = group.argb
        }
        tab.frame = TileFrame(activity, tab).also { it.actions = this }
        tab.frame.setStartPage(listener.createStartPage(tab))
        createWebView(tab)
        tabs.add(index.coerceIn(0, tabs.size), tab)
        tiles.addView(tab.frame)
        when {
            url == null || url == "about:blank" -> showStart(tab, true)
            lazy -> {
                tab.pendingUrl = url
                tab.url = url
                showStart(tab, false)
            }
            else -> load(tab, url)
        }
        if (focus || focused == null) focus(tab) else arrange()
        listener.onTabsChanged()
        return tab
    }

    fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        multiviewIds.remove(tab.id)
        tabs.removeAt(idx)
        tiles.removeView(tab.frame)
        destroyWebView(tab)
        pruneGroups()
        if (inMultiview && multiviewIds.size < 2) inMultiview = false
        if (focused === tab) {
            focused = null
            if (tabs.isEmpty()) {
                newTab(null)
            } else {
                val next = if (inMultiview) tabById(multiviewIds.first()) ?: tabs[0] else tabs[(idx - 1).coerceAtLeast(0)]
                focus(next)
            }
        } else {
            arrange()
        }
        listener.onTabsChanged()
        mediaChanged()
    }

    fun closeAll() {
        for (t in tabs.toList()) {
            tiles.removeView(t.frame)
            destroyWebView(t)
        }
        tabs.clear()
        groups.clear()
        multiviewIds.clear()
        inMultiview = false
        focused = null
    }

    fun focus(tab: Tab) {
        val prev = focused
        if (inMultiview && tab.id !in multiviewIds) inMultiview = false
        focused = tab
        if (inMultiview && prev !== tab && Prefs.soloAudio && tab.hasMedia) applySoloAudio()
        arrange()
        listener.onFocusChanged(tab)
    }

    override fun onTileTouched(tab: Tab) {
        if (focused !== tab) focus(tab)
    }

    /** Puts the right tabs on screen. */
    fun arrange() {
        val shown = if (inMultiview) multiviewIds.mapNotNull { tabById(it) } else listOfNotNull(focused)
        tiles.setShown(shown.map { it.frame })
        for (t in tabs) {
            val isShown = t in shown
            t.frame.setMode(inMultiview && isShown, t === focused)
            if (chromeHidden) t.frame.setChromeHidden(true)
            if (isShown) t.pendingUrl?.let { u -> t.pendingUrl = null; load(t, u) }
            if (!Prefs.keepPlaying) {
                if (isShown) t.webView.onResume() else t.webView.onPause()
            }
        }
    }

    // ------------------------------------------------------------------ multiview

    fun isInMultiviewSet(tab: Tab) = tab.id in multiviewIds

    /** Adds or removes a tab from the multiview set. Returns false if the set is full. */
    fun toggleMultiviewMember(tab: Tab): Boolean {
        if (tab.id in multiviewIds) {
            multiviewIds.remove(tab.id)
            if (inMultiview && multiviewIds.size < 2) {
                inMultiview = false
            } else if (inMultiview && focused === tab) {
                focused = tabById(multiviewIds.first())
            }
        } else {
            if (multiviewIds.size >= MAX_TILES) return false
            multiviewIds.add(tab.id)
            if (inMultiview) applySoloAudio()
        }
        arrange()
        focused?.let { listener.onFocusChanged(it) }
        listener.onTabsChanged()
        return true
    }

    fun enterMultiview(): Boolean {
        multiviewIds.retainAll(tabs.map { it.id }.toSet())
        if (multiviewIds.size < 2) return false
        inMultiview = true
        if (focused?.id !in multiviewIds) focused = tabById(multiviewIds.first())
        applySoloAudio()
        arrange()
        listener.onTabsChanged()
        focused?.let { listener.onFocusChanged(it) }
        return true
    }

    fun exitMultiview() {
        if (!inMultiview) return
        inMultiview = false
        arrange()
        listener.onTabsChanged()
        focused?.let { listener.onFocusChanged(it) }
    }

    override fun onTileAudio(tab: Tab) = toggleAudio(tab)

    override fun onTileMaximize(tab: Tab) {
        focused = tab
        exitMultiview()
    }

    override fun onTileRemove(tab: Tab) {
        toggleMultiviewMember(tab)
    }

    override fun onTileTitle(tab: Tab) {
        if (focused !== tab) focus(tab)
    }

    // ------------------------------------------------------------------ tab groups

    fun groupOf(tab: Tab): TabGroup? = if (tab.groupId == 0) null else groups.firstOrNull { it.id == tab.groupId }

    fun groupById(id: Int) = groups.firstOrNull { it.id == id }

    fun tabsIn(group: TabGroup): List<Tab> = tabs.filter { it.groupId == group.id }

    /** Groups in the order their first tab appears. */
    fun orderedGroups(): List<TabGroup> =
        groups.sortedBy { g -> tabs.indexOfFirst { it.groupId == g.id }.let { if (it < 0) Int.MAX_VALUE else it } }

    fun createGroup(name: String, color: Int, members: List<Tab>): TabGroup {
        val g = TabGroup(nextGroupId++, name.ifBlank { "Group ${nextGroupId - 1}" }.take(60), color)
        groups.add(g)
        members.forEach { assign(it, g) }
        keepGroupsTogether()
        pruneGroups()
        groupsChanged()
        return g
    }

    /** The colour to suggest for a new group: the first one not in use. */
    fun nextFreeColor(): Int {
        val used = groups.map { it.color }.toSet()
        return (1 until GroupColors.count).firstOrNull { it !in used } ?: (groups.size % GroupColors.count)
    }

    private fun assign(tab: Tab, g: TabGroup?) {
        tab.groupId = g?.id ?: 0
        tab.groupColor = g?.argb ?: 0
        tab.frame.refreshHeader()
    }

    fun setGroup(tab: Tab, g: TabGroup?) {
        assign(tab, g)
        keepGroupsTogether()
        pruneGroups()
        groupsChanged()
    }

    fun renameGroup(g: TabGroup, name: String) {
        g.name = name.ifBlank { g.name }.take(60)
        groupsChanged()
    }

    fun recolorGroup(g: TabGroup, color: Int) {
        g.color = color
        tabsIn(g).forEach { assign(it, g) }
        groupsChanged()
    }

    fun toggleCollapsed(g: TabGroup) {
        g.collapsed = !g.collapsed
        listener.onTabsChanged()
    }

    /** Takes the tabs out of the group and deletes it. */
    fun ungroup(g: TabGroup) {
        tabsIn(g).forEach { assign(it, null) }
        groups.remove(g)
        groupsChanged()
    }

    fun closeGroup(g: TabGroup) {
        val members = tabsIn(g)
        groups.remove(g)
        members.forEach { closeTab(it) }
        groupsChanged()
    }

    fun newTabInGroup(g: TabGroup): Tab {
        val last = tabs.indexOfLast { it.groupId == g.id }
        return newTab(null, focus = true, index = if (last < 0) tabs.size else last + 1, group = g)
    }

    /** Shows the group's tabs side by side (up to [MAX_TILES]). Returns how many are shown. */
    fun watchGroup(g: TabGroup): Int {
        val members = tabsIn(g).take(MAX_TILES)
        if (members.isEmpty()) return 0
        if (members.size == 1) {
            focus(members[0])
            return 1
        }
        multiviewIds.clear()
        members.forEach { multiviewIds.add(it.id) }
        focused = members[0]
        inMultiview = false
        enterMultiview()
        return members.size
    }

    fun muteGroup(g: TabGroup, mute: Boolean) {
        tabsIn(g).forEach { setMuted(it, mute, notify = false) }
        tabsIn(g).forEach { listener.onTabUpdated(it) }
        mediaChanged()
    }

    fun pauseGroup(g: TabGroup) {
        for (t in tabsIn(g)) if (t.isPlaying) {
            t.held = true
            t.postToFrames("""{"cmd":"pauseAll"}""")
        }
        mediaChanged()
    }

    fun playGroup(g: TabGroup) {
        for (t in tabsIn(g)) if (t.held) {
            t.held = false
            t.postToFrames("""{"cmd":"playAll"}""")
        }
        mediaChanged()
    }

    /** Moves each group's tabs next to each other, keeping the first tab's position. */
    private fun keepGroupsTogether() {
        val out = groupedOrder(tabs) { it.groupId }
        tabs.clear()
        tabs.addAll(out)
    }

    /** Groups with no tabs left disappear. */
    private fun pruneGroups() {
        val used = tabs.map { it.groupId }.toSet()
        groups.removeAll { it.id !in used }
        tabs.filter { it.groupId != 0 && groupById(it.groupId) == null }.forEach { assign(it, null) }
    }

    private fun groupsChanged() {
        tabs.forEach { it.frame.refreshHeader() }
        listener.onTabsChanged()
        focused?.let { listener.onFocusChanged(it) }
    }

    // ------------------------------------------------------------------ sound

    /** Sound follows the tile you're using, so tiles don't talk over each other. */
    private fun applySoloAudio() {
        if (!inMultiview || !Prefs.soloAudio) return
        val f = focused ?: return
        for (id in multiviewIds) {
            val t = tabById(id) ?: continue
            setMuted(t, t !== f, notify = false)
        }
        mediaChanged()
    }

    fun toggleAudio(tab: Tab) {
        if (tab.muted) {
            setMuted(tab, false)
            if (inMultiview && Prefs.soloAudio && tab.id in multiviewIds) {
                for (id in multiviewIds) tabById(id)?.takeIf { it !== tab }?.let { setMuted(it, true) }
            }
        } else {
            setMuted(tab, true)
        }
    }

    fun setMuted(tab: Tab, muted: Boolean, notify: Boolean = true) {
        tab.muted = muted
        tab.postToFrames(JSONObject().put("cmd", "mute").put("v", muted).toString())
        tab.frame.refreshHeader()
        if (notify) {
            listener.onTabUpdated(tab)
            mediaChanged()
        }
    }

    fun pauseAll() {
        for (t in tabs) {
            if (t.isPlaying) {
                t.held = true
                t.postToFrames("""{"cmd":"pauseAll"}""")
            }
        }
        mediaChanged()
    }

    fun playAll() {
        for (t in tabs) {
            if (t.held) {
                t.held = false
                t.postToFrames("""{"cmd":"playAll"}""")
            }
        }
        mediaChanged()
    }

    fun anyHeld() = tabs.any { it.held }

    fun releaseHolds() {
        for (t in tabs) {
            if (!t.held) continue
            t.held = false
            t.postToFrames(JSONObject().put("cmd", "config").put("muted", t.muted).put("held", false).toString())
        }
        mediaChanged()
    }

    private fun mediaChanged() {
        if (mediaPosted) return
        mediaPosted = true
        main.postDelayed({
            mediaPosted = false
            PlaybackHub.update(activity)
            listener.onMediaStateChanged()
        }, 150)
    }

    // ------------------------------------------------------------------ loading

    /** Loads something typed in the address bar. */
    fun loadInput(tab: Tab, raw: String) {
        val r = UrlTools.resolveInput(raw)
        load(tab, r.url)
        if (r.httpFallback != null) tab.upgradedFrom = r.httpFallback
    }

    fun load(tab: Tab, url: String) {
        var target = url
        tab.pendingUrl = null
        tab.errorFor = null
        if (target == "about:blank") {
            tab.url = target
            tab.title = ""
            tab.webView.loadUrl("about:blank")
            showStart(tab, true)
            listener.onTabUpdated(tab)
            return
        }
        val host = UrlTools.hostOf(target)
        if (target.startsWith("http://", true) && Prefs.httpsOnly &&
            Uri.parse(target).host?.lowercase(Locale.ROOT) !in httpAllowed
        ) {
            if (tab.upgradedFrom == target && tab.upgradeAttempts >= 2) {
                // The site keeps sending us back to http: it doesn't really do https.
                showNoHttps(tab, target)
                return
            }
            tab.upgradeAttempts = if (tab.upgradedFrom == target) tab.upgradeAttempts + 1 else 1
            tab.upgradedFrom = target
            target = "https://" + target.substring(7)
        } else if (!target.startsWith("http://", true)) {
            if (tab.upgradedFrom != null && UrlTools.hostOf(tab.upgradedFrom) != host) {
                tab.upgradedFrom = null
                tab.upgradeAttempts = 0
            }
        }
        target = UrlTools.cleanUrl(target)
        showStart(tab, false)
        tab.url = target
        tab.loadingUrl = target
        tab.webView.loadUrl(target, extraHeaders())
        listener.onTabUpdated(tab)
    }

    private fun extraHeaders(): Map<String, String> =
        if (Prefs.sendGpc) mapOf("Sec-GPC" to "1", "DNT" to "1") else emptyMap()

    fun reload(tab: Tab) {
        if (tab.errorFor != null) load(tab, tab.errorFor!!) else if (tab.isBlank) showStart(tab, true) else tab.webView.reload()
    }

    fun goBack(tab: Tab): Boolean {
        if (tab.webView.canGoBack()) {
            tab.errorFor = null
            tab.webView.goBack()
            return true
        }
        return false
    }

    private fun showStart(tab: Tab, show: Boolean) {
        tab.startPageVisible = show
        tab.frame.showStartPage(show)
    }

    fun setDesktopMode(tab: Tab, on: Boolean) {
        tab.desktopMode = on
        tab.webView.settings.userAgentString = userAgent(tab)
        tab.webView.settings.loadWithOverviewMode = true
        tab.webView.settings.useWideViewPort = true
        if (!tab.isBlank) tab.webView.reload()
    }

    private fun userAgent(tab: Tab) =
        if (tab.desktopMode) UrlTools.desktopUserAgent(webViewUa) else UrlTools.mobileUserAgent(webViewUa)

    // ------------------------------------------------------------------ WebView setup

    private fun createWebView(tab: Tab) {
        val wv = BrowserWebView(activity)
        tab.webView = wv
        configure(tab)
        tab.frame.attachWebView(wv)
    }

    private fun destroyWebView(tab: Tab) {
        val wv = tab.webView
        try {
            tab.scriptHandler?.remove()
        } catch (e: Exception) {
        }
        tab.scriptHandler = null
        tab.frames.clear()
        wv.stopLoading()
        wv.webChromeClient = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(tab: Tab) {
        val wv = tab.webView
        wv.keepAlive = Prefs.keepPlaying
        wv.setBackgroundColor(ContextCompat.getColor(activity, R.color.bg))
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.userAgentString = userAgent(tab)
        applyMutableSettings(tab)
        // No ad-attribution reporting to advertisers.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(s, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
        }
        wv.webViewClient = PageClient(this, tab)
        wv.webChromeClient = ChromeClient(this, tab)
        wv.setDownloadListener { url, _, disposition, mime, _ ->
            listener.onDownloadRequested(tab, url, mime ?: "", disposition ?: "")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, Prefs.bridgeName, setOf("*")) { _, message, _, isMainFrame, replyProxy ->
                val data = message.data
                if (data != null) onBridge(tab, data, isMainFrame, replyProxy)
            }
        }
        installScript(tab)
    }

    private fun applyMutableSettings(tab: Tab) {
        tab.webView.keepAlive = Prefs.keepPlaying
        tab.webView.settings.safeBrowsingEnabled = Prefs.safeBrowsing
        CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !Prefs.blockThirdPartyCookies)
    }

    fun script(): String = cachedScript ?: Injector.script(activity).also { cachedScript = it }

    private fun installScript(tab: Tab) {
        try {
            tab.scriptHandler?.remove()
        } catch (e: Exception) {
        }
        tab.scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            tab.scriptHandler = WebViewCompat.addDocumentStartJavaScript(tab.webView, script(), setOf("*"))
        }
    }

    /** Re-reads settings after you change them. Pages pick up script changes when reloaded. */
    fun applySettings() {
        cachedScript = null
        CookieManager.getInstance().setAcceptCookie(true)
        for (t in tabs) {
            applyMutableSettings(t)
            installScript(t)
        }
        arrange()
    }

    private fun setUpServiceWorkers() {
        try {
            val swc = ServiceWorkerController.getInstance()
            swc.serviceWorkerWebSettings.allowContentAccess = false
            swc.serviceWorkerWebSettings.allowFileAccess = false
            swc.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    val host = request.url.host ?: return null
                    return if (ContentBlocker.check(host, null, false) != null) emptyResponse() else null
                }
            })
        } catch (e: Exception) {
            // Not every WebView version supports service-worker interception.
        }
    }

    fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(activity).clearHttpAuthUsernamePassword()
        for (t in tabs) {
            t.webView.clearCache(true)
            t.webView.clearHistory()
            t.webView.clearFormData()
        }
    }

    // ------------------------------------------------------------------ page events (main thread)

    internal fun onPageStarted(tab: Tab, url: String) {
        val isBlocked = url == tab.blockedMainUrl && url.isNotEmpty()
        tab.resetPage()
        tab.checkFirstNav = false
        if (tab.errorFor != null && (url.startsWith("about:") || url.startsWith("data:"))) {
            tab.interstitial = true
        } else {
            tab.errorFor = null
            tab.interstitial = isBlocked
            if (!url.startsWith("about:")) {
                tab.url = url
                showStart(tab, false)
            }
        }
        tab.pageHost = UrlTools.hostOf(tab.url)
        tab.progress = 5
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            tab.webView.evaluateJavascript(script(), null)
        }
        listener.onTabUpdated(tab)
        mediaChanged()
    }

    internal fun onUrlChanged(tab: Tab, url: String) {
        if (tab.errorFor != null || url.startsWith("about:") || url.startsWith("data:")) return
        val oldPath = Uri.parse(tab.url).let { it.host + it.path }
        val newPath = Uri.parse(url).let { it.host + it.path }
        tab.url = url
        tab.pageHost = UrlTools.hostOf(url)
        if (oldPath != newPath) tab.candidates.clear()
        listener.onTabUpdated(tab)
    }

    internal fun onPageFinished(tab: Tab) {
        tab.progress = 100
        if (tab.isSecure) {
            tab.upgradeAttempts = 0
        }
        listener.onTabUpdated(tab)
    }

    internal fun onProgress(tab: Tab, p: Int) {
        tab.progress = p
        listener.onTabUpdated(tab)
    }

    internal fun onTitle(tab: Tab, title: String?) {
        if (tab.interstitial) return
        tab.title = title?.takeIf { !it.startsWith("http") || it != tab.url } ?: ""
        tab.frame.refreshHeader()
        listener.onTabUpdated(tab)
    }

    internal fun onIcon(tab: Tab, icon: Bitmap?) {
        tab.favicon = icon
        listener.onTabUpdated(tab)
    }

    /** Called off the UI thread when a request was blocked. */
    internal fun onBlocked(tab: Tab) {
        main.post {
            tab.blockedCount++
            if (tab === focused) listener.onTabUpdated(tab)
        }
    }

    /** Called off the UI thread when the page fetches something that looks like a video file. */
    internal fun onNetworkMedia(tab: Tab, url: String, kind: MediaCandidate.Kind) {
        main.post {
            if (tabs.contains(tab)) addCandidate(tab, MediaCandidate(url, kind, tab.title, tab.url))
        }
    }

    /** A stream's quality and sound playlists sit beside or below its main playlist. */
    private fun sameStream(main: String, other: String): Boolean {
        val a = Uri.parse(main)
        val b = Uri.parse(other)
        if (a.host != b.host) return false
        val dir = a.path?.substringBeforeLast('/', "") ?: return false
        return dir.isNotEmpty() && b.path?.startsWith("$dir/") == true
    }

    private fun addCandidate(tab: Tab, c: MediaCandidate) {
        if (c.kind == MediaCandidate.Kind.HLS && c.url !in tab.candidates &&
            tab.candidates.values.any { it.kind == MediaCandidate.Kind.HLS && sameStream(it.url, c.url) }
        ) return
        val existing = tab.candidates[c.url]
        if (existing != null) {
            if (c.duration > 0) existing.duration = c.duration
            if (c.width > 0) existing.width = c.width
            if (c.height > 0) existing.height = c.height
            if (c.poster.isNotEmpty()) existing.poster = c.poster
            if (c.title.isNotBlank()) existing.title = c.title
        } else {
            if (tab.candidates.size >= 30) tab.candidates.remove(tab.candidates.keys.first())
            tab.candidates[c.url] = c
        }
        listener.onTabUpdated(tab)
    }

    internal fun onMainFrameError(tab: Tab, url: String, code: Int, description: String) {
        val fallback = tab.upgradedFrom
        val host = UrlTools.hostOf(url)
        if (fallback != null && url.startsWith("https://", true) && UrlTools.hostOf(fallback) == host &&
            code in setOf(
                WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                WebViewClient.ERROR_UNKNOWN, WebViewClient.ERROR_IO
            )
        ) {
            showNoHttps(tab, fallback)
            return
        }
        if (code == WebViewClient.ERROR_UNSAFE_RESOURCE || code == WebViewClient.ERROR_UNSUPPORTED_SCHEME) return
        showPage(tab, url, Interstitials.loadError(url, host, description))
    }

    internal fun onCertError(tab: Tab, url: String) {
        val fallback = tab.upgradedFrom
        if (fallback != null && UrlTools.hostOf(fallback) == UrlTools.hostOf(url)) {
            showNoHttps(tab, fallback)
        } else {
            showPage(tab, url, Interstitials.certError(url, UrlTools.hostOf(url)))
        }
    }

    private fun showNoHttps(tab: Tab, httpUrl: String) {
        showPage(tab, httpUrl, Interstitials.noHttps(httpUrl, Uri.parse(httpUrl).host ?: httpUrl))
    }

    private fun showPage(tab: Tab, forUrl: String, html: String) {
        tab.errorFor = forUrl
        tab.url = forUrl
        tab.interstitial = true
        tab.webView.stopLoading()
        tab.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        listener.onTabUpdated(tab)
    }

    internal fun runInterstitialAction(tab: Tab, action: Interstitials.Action) {
        when (action) {
            is Interstitials.Action.Back -> {
                tab.errorFor = null
                if (!goBack(tab)) load(tab, "about:blank")
            }
            is Interstitials.Action.Retry -> load(tab, action.url)
            is Interstitials.Action.LoadHttp -> {
                Uri.parse(action.url).host?.lowercase(Locale.ROOT)?.let { httpAllowed.add(it) }
                tab.upgradedFrom = null
                load(tab, action.url)
            }
            is Interstitials.Action.ProceedUnsafe -> {
                unsafeAllowed.add(action.host.lowercase(Locale.ROOT))
                load(tab, action.url)
            }
        }
    }

    internal fun onRendererGone(tab: Tab) {
        main.post {
            if (!tabs.contains(tab)) return@post
            val url = tab.url
            destroyWebView(tab)
            tab.resetPage()
            createWebView(tab)
            arrange()
            if (!tab.isBlank) load(tab, url) else showStart(tab, true)
            mediaChanged()
        }
    }

    // ------------------------------------------------------------------ windows, apps, permissions

    internal fun onCreateWindow(opener: Tab, userGesture: Boolean, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (userGesture) {
            // A link or button you tapped: open it in a new tab next to this one,
            // but close it straight away if it goes to an ad or malware server.
            val t = newTab(null, focus = true, index = tabs.indexOf(opener) + 1, group = groupOf(opener))
            t.checkFirstNav = true
            t.openerId = opener.id
            showStart(t, false)
            transport.webView = t.webView
            resultMsg.sendToTarget()
            return true
        }
        // A pop-up the page opened by itself: find out where it was going without loading it.
        val catcher = WebView(activity)
        var done = false
        fun finish(url: String?) {
            if (done) return
            done = true
            main.post {
                catcher.stopLoading()
                catcher.destroy()
            }
            if (url == null || !UrlTools.isWeb(url)) return
            opener.popupsBlocked++
            val host = Uri.parse(url).host ?: return
            if (ContentBlocker.check(host, opener.pageHost, false) == null) listener.onPopupBlocked(opener, url)
            listener.onTabUpdated(opener)
        }
        catcher.settings.javaScriptEnabled = false
        catcher.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.isForMainFrame) {
                    val u = request.url.toString()
                    main.post { finish(u) }
                }
                return emptyResponse()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                finish(request.url.toString())
                return true
            }
        }
        transport.webView = catcher
        resultMsg.sendToTarget()
        main.postDelayed({ finish(null) }, 4000)
        return true
    }

    /** Called off the UI thread for the first page a tapped pop-up loads. */
    internal fun checkPopupTarget(tab: Tab, host: String): Boolean {
        tab.checkFirstNav = false
        val opener = tabById(tab.openerId)
        val verdict = ContentBlocker.check(host, opener?.pageHost ?: "", false)
        if (verdict != null) {
            main.post {
                opener?.let {
                    it.popupsBlocked++
                    listener.onTabUpdated(it)
                }
                closeTab(tab)
                opener?.let { focus(it) }
            }
            return true
        }
        return false
    }

    internal fun openExternal(tab: Tab, request: WebResourceRequest, url: String) {
        // Sites can't launch other apps on their own, only when you tap something.
        if (!request.hasGesture()) {
            tab.popupsBlocked++
            listener.onTabUpdated(tab)
            return
        }
        val intent = try {
            if (url.startsWith("intent:", true)) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (e: Exception) {
            return
        }
        val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { UrlTools.isWeb(it) }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.removeFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        listener.onExternalApp(tab, intent, fallback)
    }

    internal fun onShowCustomView(tab: Tab, view: View, callback: WebChromeClient.CustomViewCallback) =
        listener.onEnterFullscreen(tab, view, callback)

    internal fun onHideCustomView() = listener.onExitFullscreen()

    internal fun onPermission(tab: Tab, request: PermissionRequest) {
        if (request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
            listener.onProtectedMedia(tab, request)
        } else {
            request.deny()
        }
    }

    // ------------------------------------------------------------------ page script messages

    private fun onBridge(tab: Tab, data: String, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (data.length > 16_384 || !tabs.contains(tab)) return
        val o = try {
            JSONObject(data)
        } catch (e: JSONException) {
            return
        }
        when (o.optString("type")) {
            "state" -> onState(tab, o, isMainFrame, proxy)
            "media" -> onMediaFound(tab, o)
            "autoMuted" -> {
                tab.muted = true
                tab.frame.refreshHeader()
                listener.onTabUpdated(tab)
                mediaChanged()
            }
            "userUnmuted" -> {
                tab.muted = false
                if (inMultiview && Prefs.soloAudio && tab.id in multiviewIds) {
                    for (id in multiviewIds) tabById(id)?.takeIf { it !== tab }?.let { setMuted(it, true, notify = false) }
                }
                tab.frame.refreshHeader()
                listener.onTabUpdated(tab)
                mediaChanged()
            }
            "gone" -> {
                tab.frames.remove(proxy)
                mediaChanged()
            }
        }
    }

    private fun Double.finiteOrZero() = if (isNaN() || isInfinite() || this < 0) 0.0 else this

    private fun onState(tab: Tab, o: JSONObject, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        val wasAudible = tab.isAudible
        val isNew = !tab.frames.containsKey(proxy)
        val count = o.optInt("count", 0)
        val playing = o.optInt("playing", 0).coerceAtLeast(0)
        val m = o.optJSONObject("main")
        val mainVideo = m?.let {
            MainVideo(
                src = it.optString("src").take(4096),
                time = it.optDouble("t", 0.0).finiteOrZero(),
                duration = it.optDouble("d", 0.0).finiteOrZero(),
                width = it.optInt("w", 0),
                height = it.optInt("h", 0),
                playing = it.optBoolean("playing"),
                x = it.optDouble("x", -1.0).toFloat(),
                y = it.optDouble("y", -1.0).toFloat(),
                rectW = it.optDouble("rw", 0.0).finiteOrZero().toFloat(),
                rectH = it.optDouble("rh", 0.0).finiteOrZero().toFloat(),
            )
        }
        if (count <= 0) {
            tab.frames.remove(proxy)
        } else {
            tab.frames[proxy] = FrameMedia(isMainFrame, playing, o.optBoolean("audible"), count, mainVideo)
        }
        if (isNew && count > 0 && (tab.muted || tab.held)) {
            try {
                proxy.postMessage(JSONObject().put("cmd", "config").put("muted", tab.muted).put("held", tab.held).toString())
            } catch (e: Exception) {
            }
        }
        if (tab.pendingSeek > 0 && mainVideo != null && mainVideo.duration > tab.pendingSeek) {
            try {
                proxy.postMessage(JSONObject().put("cmd", "seek").put("t", tab.pendingSeek).toString())
            } catch (e: Exception) {
            }
            tab.pendingSeek = -1.0
        }
        if (playing > 0 && tab.held) {
            // You pressed play on the page itself.
            tab.held = false
            tab.postToFrames(JSONObject().put("cmd", "config").put("muted", tab.muted).put("held", false).toString())
        }
        if (!wasAudible && tab.isAudible) {
            // Android gives the sound to the newest player; tell the others so
            // they keep playing silently instead of stopping.
            val msg = """{"cmd":"peerAudio"}"""
            for (t in tabs) if (t !== tab) t.postToFrames(msg)
        }
        tab.frame.refreshHeader()
        listener.onTabUpdated(tab)
        mediaChanged()
    }

    private fun onMediaFound(tab: Tab, o: JSONObject) {
        val src = o.optString("src")
        if (!UrlTools.isWeb(src) || src.length > 4096) return
        val host = Uri.parse(src).host ?: return
        if (ContentBlocker.check(host, tab.pageHost, false) != null) return
        val kind = MediaCandidate.kindFor(src) ?: MediaCandidate.Kind.FILE
        if (o.optString("kind") == "audio" && kind == MediaCandidate.Kind.FILE && MediaCandidate.kindFor(src) == null) return
        addCandidate(
            tab,
            MediaCandidate(
                url = src,
                kind = kind,
                title = o.optString("title").take(300).ifBlank { tab.title },
                pageUrl = tab.url,
                duration = o.optDouble("d", 0.0).finiteOrZero(),
                width = o.optInt("w", 0),
                height = o.optInt("h", 0),
                poster = o.optString("poster").takeIf { UrlTools.isWeb(it) && it.length < 4096 } ?: "",
            )
        )
    }

    fun requestReport(tab: Tab) = tab.postToFrames("""{"cmd":"report"}""")

    companion object {
        const val MAX_TILES = 4

        /**
         * Reorders [items] so each group's members sit together where the
         * group's first member was; ungrouped items (group 0) keep their place.
         */
        fun <T> groupedOrder(items: List<T>, groupOf: (T) -> Int): List<T> {
            val out = ArrayList<T>(items.size)
            val placed = HashSet<Int>()
            for (t in items) {
                val g = groupOf(t)
                if (g == 0) out.add(t) else if (placed.add(g)) out.addAll(items.filter { groupOf(it) == g })
            }
            return out
        }

        fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
