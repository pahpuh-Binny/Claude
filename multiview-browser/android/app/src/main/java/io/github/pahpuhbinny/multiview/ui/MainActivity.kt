package io.github.pahpuhbinny.multiview.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Patterns
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.google.android.gms.cast.framework.CastState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.browser.GroupColors
import io.github.pahpuhbinny.multiview.browser.Tab
import io.github.pahpuhbinny.multiview.browser.TabGroup
import io.github.pahpuhbinny.multiview.browser.TabManager
import io.github.pahpuhbinny.multiview.browser.UrlTools
import io.github.pahpuhbinny.multiview.cast.CastSupport
import io.github.pahpuhbinny.multiview.data.Bookmark
import io.github.pahpuhbinny.multiview.data.Capture
import io.github.pahpuhbinny.multiview.data.Session
import io.github.pahpuhbinny.multiview.data.SessionGroup
import io.github.pahpuhbinny.multiview.data.SessionTab
import io.github.pahpuhbinny.multiview.data.Store
import io.github.pahpuhbinny.multiview.databinding.ActivityMainBinding
import io.github.pahpuhbinny.multiview.download.Downloads
import io.github.pahpuhbinny.multiview.download.MediaCandidate
import io.github.pahpuhbinny.multiview.media.PlaybackHub
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import io.github.pahpuhbinny.multiview.vault.VaultActivity
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity(), TabManager.Listener {

    private lateinit var b: ActivityMainBinding
    lateinit var tabs: TabManager
        private set

    private val main = Handler(Looper.getMainLooper())
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var switcher: TabSwitcher? = null
    private var immersive = false
    private var wasInPip = false
    private var settingsDirty = false
    private val drmAllowed = HashSet<String>()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val openBookmarks = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        val url = data.getStringExtra(BookmarksActivity.EXTRA_URL) ?: return@registerForActivityResult
        openBookmark(url, data.getDoubleExtra(BookmarksActivity.EXTRA_TIME, 0.0), data.getBooleanExtra(BookmarksActivity.EXTRA_MULTIVIEW, false))
    }

    private val openSessions = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        when (data.getStringExtra(SessionsActivity.EXTRA_ACTION)) {
            SessionsActivity.ACTION_SAVE -> promptSaveSession(null)
            SessionsActivity.ACTION_UPDATE -> data.getStringExtra(SessionsActivity.EXTRA_ID)?.let { id ->
                Store.sessions(this).firstOrNull { it.id == id }?.let { promptSaveSession(it) }
            }
            SessionsActivity.ACTION_RESTORE -> data.getStringExtra(SessionsActivity.EXTRA_ID)?.let { id ->
                Store.sessions(this).firstOrNull { it.id == id }?.let {
                    restoreSession(it, data.getBooleanExtra(SessionsActivity.EXTRA_REPLACE, true))
                }
            }
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsDirty = true }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        tabs = TabManager(this, b.tiles, this)
        if (Prefs.clearOnExit) tabs.clearBrowsingData()
        Prefs.prefs.registerOnSharedPreferenceChangeListener(prefListener)

        setUpToolbar()
        setUpBack()
        setUpCast()

        val opened = handleIntent(intent)
        if (!opened || Prefs.restoreTabs) {
            val last = if (Prefs.restoreTabs) Store.lastSession(this) else null
            if (last != null && last.tabs.isNotEmpty()) restoreSession(last, replace = false, focusRestored = !opened)
            else if (!opened) tabs.newTab(null)
        }
        if (tabs.tabs.isEmpty()) tabs.newTab(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        PlaybackHub.appVisible = true
        PlaybackHub.update(this)
    }

    override fun onResume() {
        super.onResume()
        if (settingsDirty) {
            settingsDirty = false
            tabs.applySettings()
            tabs.focused?.let { updateToolbar(it) }
            updateGroupBar()
        }
        tabs.tabs.filter { it.startPageVisible }.forEach { (it.frame.startPage as? StartPageView)?.refresh() }
    }

    override fun onStop() {
        super.onStop()
        PlaybackHub.appVisible = false
        Store.saveLastSession(this, snapshot("last", "Last session", 0L))
        if (!Prefs.keepPlaying) tabs.tabs.forEach { it.webView.onPause() }
        PlaybackHub.update(this)
    }

    override fun onDestroy() {
        Prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (isFinishing) {
            if (Prefs.clearOnExit) tabs.clearBrowsingData()
            tabs.closeAll()
            PlaybackHub.service?.stopPlayback()
        }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && Prefs.autoPip && tabs.tabs.any { it.isPlaying }) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && wasInPip && lifecycle.currentState == Lifecycle.State.CREATED) {
            // You closed the floating window (rather than expanding it): stop the videos.
            PlaybackHub.stopAll()
        }
        wasInPip = isInPictureInPictureMode
        applyChromeVisibility()
    }

    // ------------------------------------------------------------------ intents

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val url: String? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { UrlTools.isWeb(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val m = Patterns.WEB_URL.matcher(text)
                val found = if (m.find()) m.group() else null
                if (found != null && UrlTools.isWeb(found)) found
                else UrlTools.resolveInput(text.take(500)).url
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.let { UrlTools.searchUrl(it) }
            else -> null
        }
        if (url == null) return false
        // Don't stack up blank tabs when a link opens the app.
        val blank = tabs.focused?.takeIf { it.isBlank && it.pendingUrl == null && !tabs.inMultiview }
        if (blank != null) tabs.load(blank, url) else tabs.newTab(url)
        return true
    }

    // ------------------------------------------------------------------ toolbar

    private fun setUpToolbar() {
        b.urlField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val text = b.urlField.text.toString()
                if (text.isNotBlank()) tabs.focused?.let { tabs.loadInput(it, text) }
                b.urlField.clearFocus()
                hideKeyboard()
                true
            } else false
        }
        b.urlField.setOnFocusChangeListener { _, hasFocus ->
            val show = if (hasFocus) View.GONE else View.VISIBLE
            b.multiviewBtn.visibility = show
            b.tabsBtn.visibility = show
            if (hasFocus) {
                b.mediaBtn.visibility = View.GONE
                tabs.focused?.let { t -> if (!t.isBlank) b.urlField.setText(t.url) }
                b.urlField.selectAll()
            } else {
                tabs.focused?.let { updateToolbar(it) }
            }
        }
        b.shieldBtn.setOnClickListener { showProtection() }
        b.mediaBtn.setOnClickListener { showMediaSheet() }
        b.multiviewBtn.setOnClickListener { toggleMultiview() }
        b.multiviewBtn.setOnLongClickListener {
            showTabSwitcher()
            true
        }
        b.tabsBtn.setOnClickListener { showTabSwitcher() }
        b.menuBtn.setOnClickListener { showMenu(it) }
        b.exitImmersive.setOnClickListener { setImmersiveMode(false) }
    }

    private fun setUpBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = tabs.focused
                when {
                    fullscreenView != null -> onExitFullscreen()
                    immersive -> setImmersiveMode(false)
                    b.urlField.hasFocus() -> {
                        b.urlField.clearFocus()
                        hideKeyboard()
                    }
                    t != null && tabs.goBack(t) -> {}
                    t != null && t.openerId != 0 && tabs.tabById(t.openerId) != null -> {
                        val opener = tabs.tabById(t.openerId)!!
                        tabs.closeTab(t)
                        tabs.focus(opener)
                    }
                    tabs.inMultiview -> tabs.exitMultiview()
                    // Leave the app without closing it, so videos keep playing.
                    else -> moveTaskToBack(true)
                }
            }
        })
    }

    private fun setUpCast() {
        CastSupport.setUpButton(applicationContext, b.castBtn)
        CastSupport.whenReady {
            val ctx = CastSupport.context ?: return@whenReady
            fun apply(state: Int) {
                b.castBtn.visibility = if (state == CastState.NO_DEVICES_AVAILABLE) View.GONE else View.VISIBLE
            }
            apply(ctx.castState)
            ctx.addCastStateListener { apply(it) }
        }
        CastSupport.onStatus = { msg -> snack(msg) }
    }

    private fun updateToolbar(tab: Tab) {
        if (tab !== tabs.focused) return
        if (!b.urlField.hasFocus()) b.urlField.setText(prettyUrl(tab))
        val loading = tab.progress in 1..99
        b.progress.visibility = if (loading && !immersive) View.VISIBLE else View.INVISIBLE
        b.progress.setProgressCompat(tab.progress, true)

        val allowlisted = ContentBlocker.isAllowlisted(tab.pageHost)
        val blocked = tab.blockedCount + tab.popupsBlocked
        b.shieldCount.visibility = if (blocked > 0 && !allowlisted) View.VISIBLE else View.GONE
        b.shieldCount.text = if (blocked > 99) "99+" else blocked.toString()
        val insecure = !tab.isBlank && !tab.isSecure && !tab.interstitial
        b.shieldBtn.setImageResource(if (allowlisted || insecure) R.drawable.ic_shield_off else R.drawable.ic_shield)
        b.shieldBtn.setColorFilter(
            ContextCompat.getColor(this, if (insecure) R.color.warn else if (allowlisted) R.color.text_dim else R.color.safe)
        )
        b.mediaBtn.visibility = if (!b.urlField.hasFocus() && (tab.candidates.isNotEmpty() || tab.hasMedia)) View.VISIBLE else View.GONE
        b.mediaBtn.setColorFilter(ContextCompat.getColor(this, if (tab.candidates.isNotEmpty()) R.color.accent else R.color.text_dim))
    }

    private fun prettyUrl(tab: Tab): String {
        if (tab.isBlank) return ""
        return tab.url.removePrefix("https://").removePrefix("www.")
    }

    private fun updateTabsButton() {
        b.tabsCount.text = if (tabs.tabs.size > 99) ":D" else tabs.tabs.size.toString()
        b.tabsAudioDot.visibility = if (tabs.tabs.any { it.isPlaying && it !== tabs.focused }) View.VISIBLE else View.GONE
        b.multiviewBtn.setColorFilter(ContextCompat.getColor(this, if (tabs.inMultiview) R.color.accent else R.color.text))
        // The tab counter takes the colour of the current tab's group.
        val g = tabs.focused?.let { tabs.groupOf(it) }
        b.tabsCount.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setStroke(dp(2), g?.argb ?: ContextCompat.getColor(this@MainActivity, R.color.text))
            if (g != null) setColor(GroupColors.soft(g.color, 0x55))
        }
        updateGroupBar()
    }

    private var groupBarSignature = ""

    /**
     * When the current tab is in a group, a strip under the address bar lists
     * the group's tabs for quick switching, with a + to add a tab to it.
     */
    private fun updateGroupBar() {
        val focused = tabs.focused
        val g = focused?.let { tabs.groupOf(it) }
        val hidden = g == null || !Prefs.groupBar || immersive || isInPictureInPictureMode || fullscreenView != null
        if (hidden) {
            b.groupBar.visibility = View.GONE
            groupBarSignature = ""
            return
        }
        val members = tabs.tabsIn(g!!)
        val sig = g.id.toString() + g.name + g.color + focused!!.id + members.joinToString { "${it.id}:${it.displayTitle()}:${it.isPlaying}:${it.muted}" }
        b.groupBar.visibility = View.VISIBLE
        if (sig == groupBarSignature) return
        groupBarSignature = sig
        val row = b.groupChips
        row.removeAllViews()
        fun chip(text: String, fill: Int, stroke: Int, textColor: Int): TextView = TextView(this).apply {
            this.text = text
            textSize = 12f
            maxLines = 1
            setTextColor(textColor)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(fill)
                if (stroke != 0) setStroke(dp(1), stroke)
            }
            row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply { marginEnd = dp(6) })
        }
        val label = chip("● " + g.name, 0, 0, g.argb)
        label.typeface = android.graphics.Typeface.DEFAULT_BOLD
        label.setOnClickListener { v -> GroupDialogs.groupMenu(this, tabs, g, v) }
        val dim = ContextCompat.getColor(this, R.color.text)
        for (t in members) {
            val title = t.displayTitle().let { if (it.length > 18) it.take(17) + "…" else it }
            val prefix = if (t.isPlaying) (if (t.muted) "▶🔇 " else "▶ ") else ""
            val c = if (t === focused) chip(prefix + title, GroupColors.soft(g.color, 0x66), g.argb, dim)
            else chip(prefix + title, ContextCompat.getColor(this, R.color.surface2), 0, ContextCompat.getColor(this, R.color.text_dim))
            c.setOnClickListener { tabs.focus(t) }
            c.setOnLongClickListener {
                GroupDialogs.moveTab(this, tabs, t)
                true
            }
        }
        val add = chip("+", ContextCompat.getColor(this, R.color.surface2), 0, g.argb)
        add.contentDescription = "New tab in ${g.name}"
        add.setOnClickListener {
            tabs.newTabInGroup(g)
            focusAddressBar()
        }
        // Keep the current tab's chip in view.
        val idx = members.indexOf(focused) + 1
        b.groupBar.post { row.getChildAt(idx)?.let { b.groupBar.smoothScrollTo((it.left - dp(40)).coerceAtLeast(0), 0) } }
    }

    fun focusAddressBar() {
        b.urlField.requestFocus()
        showKeyboard(b.urlField)
    }

    fun closeSwitcher() {
        switcher?.dismiss()
    }

    fun onGroupWatched(count: Int) {
        closeSwitcher()
        if (count > 1) snack("Watching $count tabs from the group together")
    }

    fun saveGroupAsSession(g: TabGroup) {
        promptSaveSession(null, only = tabs.tabsIn(g), defaultName = g.name)
    }

    fun openNewTab() {
        val t = tabs.newTab(null)
        tabs.focus(t)
        b.urlField.requestFocus()
        showKeyboard(b.urlField)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.urlField.windowToken, 0)
    }

    private fun showKeyboard(v: View) {
        v.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun snack(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val s = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG)
        if (action != null && onAction != null) s.setAction(action) { onAction() }
        s.show()
    }

    // ------------------------------------------------------------------ multiview & tabs

    private fun toggleMultiview() {
        if (tabs.inMultiview) {
            tabs.exitMultiview()
            return
        }
        if (tabs.enterMultiview()) return
        // Nothing picked yet: start with this tab plus the other tabs that are playing.
        val focused = tabs.focused ?: return
        val picks = (listOf(focused) + tabs.tabs.filter { it !== focused && it.isPlaying } +
            tabs.tabs.filter { it !== focused && !it.isPlaying }).take(TabManager.MAX_TILES)
        if (picks.size < 2) {
            // Only one tab open: add a second, empty one beside it.
            if (!tabs.isInMultiviewSet(focused)) tabs.toggleMultiviewMember(focused)
            val t = tabs.newTab(null, focus = false)
            tabs.toggleMultiviewMember(t)
            tabs.enterMultiview()
            tabs.focus(t)
            b.urlField.requestFocus()
            showKeyboard(b.urlField)
            return
        }
        for (t in picks) if (!tabs.isInMultiviewSet(t)) tabs.toggleMultiviewMember(t)
        tabs.enterMultiview()
        snack("Showing ${tabs.multiviewIds.size} tabs together", "Choose") { showTabSwitcher() }
    }

    private fun showTabSwitcher() {
        captureThumbnails {
            switcher = TabSwitcher(this, tabs).also { it.show() }
        }
    }

    fun onSwitcherClosed() {
        switcher = null
    }

    private fun captureThumbnails(done: () -> Unit) {
        val shown = tabs.tabs.filter { (tabs.inMultiview && tabs.isInMultiviewSet(it)) || it === tabs.focused }
            .filter { !it.startPageVisible }
        if (shown.isEmpty()) return done()
        var left = shown.size
        for (t in shown) {
            Capture.view(this, t.webView, 480) { bmp ->
                if (bmp != null) t.thumbnail = bmp
                if (--left == 0) done()
            }
        }
    }

    // ------------------------------------------------------------------ TabManager.Listener

    override fun onTabsChanged() {
        updateTabsButton()
        switcher?.refresh()
        tabs.focused?.let { updateToolbar(it) }
    }

    override fun onFocusChanged(tab: Tab) {
        updateToolbar(tab)
        updateTabsButton()
    }

    override fun onTabUpdated(tab: Tab) {
        if (tab === tabs.focused) updateToolbar(tab)
        if (tab.groupId != 0 && tab.groupId == tabs.focused?.groupId) updateGroupBar()
    }

    override fun onMediaStateChanged() {
        updateTabsButton()
        switcher?.refresh()
        tabs.focused?.let { updateToolbar(it) }
        val anyPlaying = tabs.tabs.any { it.isPlaying }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                setPictureInPictureParams(pipParams(autoEnter = Prefs.autoPip && anyPlaying))
            } catch (e: Exception) {
            }
        }
        if (anyPlaying && Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onEnterFullscreen(tab: Tab, view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        b.fullscreenHost.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        b.fullscreenHost.visibility = View.VISIBLE
        setSystemBarsHidden(true)
        updateGroupBar()
        tab.mainVideo()?.let { v ->
            if (v.width > 0 && v.height > 0) {
                requestedOrientation = if (v.width >= v.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }

    override fun onExitFullscreen() {
        val v = fullscreenView ?: return
        b.fullscreenHost.removeView(v)
        b.fullscreenHost.visibility = View.GONE
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (!immersive) setSystemBarsHidden(false)
        updateGroupBar()
    }

    override fun onPopupBlocked(tab: Tab, url: String) {
        snack("Blocked a pop-up to ${UrlTools.hostOf(url)}", "Open") { tabs.newTab(url) }
    }

    override fun onExternalApp(tab: Tab, intent: Intent, fallbackUrl: String?) {
        fun launch() {
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                if (fallbackUrl != null) tabs.load(tab, fallbackUrl) else snack("No app can open this link")
            } catch (e: SecurityException) {
                snack("This link can't be opened")
            }
        }
        if (!Prefs.confirmExternalApps) return launch()
        val target = intent.`package` ?: intent.data?.scheme ?: "another app"
        MaterialAlertDialogBuilder(this)
            .setTitle("Open another app?")
            .setMessage("${UrlTools.hostOf(tab.url).ifEmpty { "This page" }} wants to open $target.")
            .setPositiveButton(R.string.open) { _, _ -> launch() }
            .setNegativeButton(R.string.cancel) { _, _ -> if (fallbackUrl != null) tabs.load(tab, fallbackUrl) }
            .show()
    }

    override fun onDownloadRequested(tab: Tab, url: String, mimeType: String, contentDisposition: String) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val isVideo = mimeType.startsWith("video/") || MediaCandidate.kindFor(url) != null ||
            name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "webm", "mov", "m3u8")
        if (!isVideo || !UrlTools.isWeb(url)) {
            snack("Blocked download of “$name”. Multiview only saves videos, as clean encrypted copies.")
            return
        }
        val kind = MediaCandidate.kindFor(url) ?: MediaCandidate.Kind.FILE
        saveVideo(tab, MediaCandidate(url, kind, tab.title.ifBlank { name }, tab.url))
    }

    override fun onFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        val type = params.acceptTypes?.firstOrNull { it.isNotBlank() && !it.startsWith(".") } ?: "*/*"
        return try {
            pickFiles.launch(type)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    override fun onProtectedMedia(tab: Tab, request: PermissionRequest) {
        val host = UrlTools.hostOf(request.origin.toString())
        if (host in drmAllowed) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Play protected video?")
            .setMessage("$host wants to play copy-protected (DRM) video. This lets the site get a licence for this device.")
            .setPositiveButton("Allow") { _, _ ->
                drmAllowed.add(host)
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            }
            .setNegativeButton("Block") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    override fun createStartPage(tab: Tab): View = StartPageView(this) { bm ->
        tabs.load(tab, bm.url)
        if (bm.videoTime > 5) tab.pendingSeek = bm.videoTime
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val tab = tabs.focused ?: return
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        val bookmarked = !tab.isBlank && Store.findBookmark(this, tab.url) != null
        val group = tabs.groupOf(tab)
        m.add(0, 1, 0, R.string.new_tab)
        if (group != null) m.add(0, 16, 0, "New tab in “${group.name}”")
        m.add(0, 17, 0, if (group == null) "Add tab to group…" else "Move tab to another group…")
        if (group != null) m.add(0, 18, 0, "Remove tab from “${group.name}”")
        m.add(0, 2, 0, R.string.bookmarks)
        if (!tab.isBlank) m.add(0, 3, 0, if (bookmarked) "Remove bookmark" else "Bookmark with preview")
        m.add(0, 4, 0, R.string.sessions)
        m.add(0, 5, 0, R.string.vault)
        if (CastSupport.isConnected) m.add(0, 6, 0, "Stop casting to ${CastSupport.deviceName ?: "TV"}")
        else if (tab.candidates.isNotEmpty()) m.add(0, 7, 0, "Cast video")
        m.add(0, 8, 0, "Mirror screen")
        m.add(0, 9, 0, "Picture-in-picture")
        m.add(0, 10, 0, if (tabs.inMultiview) "Hide browser bars" else "Full screen")
        m.add(0, 11, 0, "Desktop site").apply {
            isCheckable = true
            isChecked = tab.desktopMode
        }
        m.add(0, 12, 0, "Reload")
        if (!tab.isBlank) m.add(0, 13, 0, "Share link")
        m.add(0, 14, 0, R.string.settings)
        m.add(0, 15, 0, if (Prefs.clearOnExit) "Exit and clear data" else "Exit")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openNewTab()
                2 -> openBookmarks.launch(Intent(this, BookmarksActivity::class.java))
                3 -> if (bookmarked) removeBookmark(tab) else addBookmark(tab)
                4 -> openSessions.launch(Intent(this, SessionsActivity::class.java))
                5 -> startActivity(Intent(this, VaultActivity::class.java))
                6 -> CastSupport.stop()
                7 -> tab.candidates.values.maxByOrNull { it.score() }?.let { castMedia(tab, it) }
                8 -> mirrorScreen()
                9 -> enterPip()
                10 -> setImmersiveMode(true)
                11 -> tabs.setDesktopMode(tab, !tab.desktopMode)
                12 -> tabs.reload(tab)
                13 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), null))
                14 -> startActivity(Intent(this, SettingsActivity::class.java))
                15 -> exitApp()
                16 -> group?.let {
                    tabs.newTabInGroup(it)
                    focusAddressBar()
                }
                17 -> GroupDialogs.moveTab(this, tabs, tab)
                18 -> tabs.setGroup(tab, null)
            }
            true
        }
        popup.show()
    }

    private fun exitApp() {
        Store.saveLastSession(this, snapshot("last", "Last session", 0L))
        PlaybackHub.stopAll()
        PlaybackHub.service?.stopPlayback()
        if (Prefs.clearOnExit) tabs.clearBrowsingData()
        tabs.closeAll()
        finishAndRemoveTask()
    }

    // ------------------------------------------------------------------ protection panel

    private fun showProtection() {
        val tab = tabs.focused ?: return
        val host = tab.pageHost
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        fun line(text: String, color: Int = R.color.text) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            setPadding(0, dp(6), 0, dp(6))
        }
        when {
            tab.isBlank -> col.addView(line("New tab"))
            tab.interstitial -> col.addView(line("⚠ Multiview stopped this page and is showing a warning instead.", R.color.warn))
            tab.isSecure -> col.addView(line("🔒 Secure connection (HTTPS)", R.color.safe))
            else -> col.addView(line("⚠ Not secure: this page isn't encrypted (HTTP)", R.color.warn))
        }
        col.addView(line("${tab.blockedCount} ads, trackers and threats blocked on this page"))
        if (tab.popupsBlocked > 0) col.addView(line("${tab.popupsBlocked} pop-ups or app launches blocked"))
        col.addView(
            line(
                "Safe Browsing ${if (Prefs.safeBrowsing) "on" else "off"} · HTTPS-only ${if (Prefs.httpsOnly) "on" else "off"} · " +
                    "%,d blocked domains".format(ContentBlocker.domainCount),
                R.color.text_dim
            )
        )
        val toggle = SwitchCompat(this).apply {
            text = "Block ads and trackers on ${host.ifEmpty { "this site" }}"
            isChecked = !ContentBlocker.isAllowlisted(host)
            isEnabled = host.isNotEmpty()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setPadding(0, dp(10), 0, dp(10))
        }
        col.addView(toggle)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(host.ifEmpty { getString(R.string.protection) })
            .setView(col)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.settings) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .show()
        toggle.setOnCheckedChangeListener { _, checked ->
            val set = Prefs.allowlist.toMutableSet()
            if (checked) {
                var h = host
                while (true) {
                    set.remove(h)
                    val i = h.indexOf('.')
                    if (i < 0) break
                    h = h.substring(i + 1)
                }
            } else {
                set.add(host)
            }
            Prefs.allowlist = set
            settingsDirty = false
            tabs.applySettings()
            tabs.reload(tab)
            dialog.dismiss()
        }
    }

    // ------------------------------------------------------------------ media: save & cast

    private fun showMediaSheet() {
        val tab = tabs.focused ?: return
        tabs.requestReport(tab)
        MediaSheet(this, tab, onSave = { saveVideo(tab, it) }, onCast = { castMedia(tab, it) }, onMirror = { mirrorScreen() }).show()
    }

    private fun saveVideo(tab: Tab, c: MediaCandidate) {
        val input = EditText(this).apply {
            setText(c.title.ifBlank { tab.displayTitle() })
            setSelection(text.length)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save video to the vault")
            .setMessage("Multiview downloads the video, rebuilds it as a new clean file and encrypts it. It plays only inside this app.")
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val host = Uri.parse(c.url).host?.lowercase() ?: ""
                Downloads.enqueue(
                    this,
                    Downloads.Job(
                        candidate = c,
                        title = input.text.toString().trim(),
                        userAgent = tab.webView.settings.userAgentString,
                        referer = tab.url,
                        allowHttp = !Prefs.httpsOnly || host in tabs.httpAllowed,
                    )
                )
                snack("Saving to the vault…", "View") { startActivity(Intent(this, VaultActivity::class.java)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun castMedia(tab: Tab, c: MediaCandidate) {
        if (CastSupport.context == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Casting isn't available")
                .setMessage("Chromecast needs Google Play services. You can still mirror your screen to a TV.")
                .setPositiveButton("Mirror screen") { _, _ -> mirrorScreen() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val startAt = tab.mainVideo()?.takeIf { it.src == c.url }?.time ?: 0.0
        if (tab.isPlaying) {
            tab.held = true
            tab.postToFrames("""{"cmd":"pauseAll"}""")
        }
        if (!CastSupport.cast(c, startAt)) {
            snack("Pick a TV to cast to")
            b.castBtn.visibility = View.VISIBLE
            if (!b.castBtn.showDialog()) snack("No TVs found on this Wi-Fi network")
        }
    }

    private fun mirrorScreen() {
        val intents = listOf(Intent(Settings.ACTION_CAST_SETTINGS), Intent("android.settings.WIFI_DISPLAY_SETTINGS"))
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        snack("Open Quick Settings and tap Screen Cast / Smart View")
    }

    // ------------------------------------------------------------------ bookmarks

    private fun addBookmark(tab: Tab) {
        val video = tab.mainVideo()
        val id = Store.newId()
        val frames = ArrayList<Bitmap>()
        val wv = tab.webView
        // Crop to the video if it's in the page itself and big enough.
        var crop: Rect? = null
        if (video != null && video.x >= 0 && video.rectW > 160 && video.rectH > 90) {
            val r = Rect(video.x.toInt(), video.y.toInt(), (video.x + video.rectW).toInt(), (video.y + video.rectH).toInt())
            if (r.intersect(0, 0, wv.width, wv.height) && r.width() > 160 && r.height() > 90) crop = r
        }
        val count = if (tab.isPlaying) 8 else 1
        snack(if (count > 1) "Capturing a preview…" else "Saving bookmark…")
        fun grab(i: Int) {
            Capture.view(this, wv, 360, crop) { bmp ->
                if (bmp != null) frames.add(bmp)
                if (i + 1 < count) main.postDelayed({ grab(i + 1) }, 380) else finishBookmark(tab, id, frames, video?.time ?: 0.0, video?.duration ?: 0.0)
            }
        }
        grab(0)
    }

    private fun finishBookmark(tab: Tab, id: String, frames: List<Bitmap>, time: Double, duration: Double) {
        Thread {
            Store.savePreviewFrames(this, id, frames)
            Store.addBookmark(
                this,
                Bookmark(id, tab.url, tab.displayTitle(), System.currentTimeMillis(), frames.size,
                    if (duration > 30 && time > 5) time else 0.0, duration)
            )
            main.post {
                snack("Bookmarked" + if (time > 5 && duration > 30) " at ${formatDuration(time)}" else "", "View") {
                    openBookmarks.launch(Intent(this, BookmarksActivity::class.java))
                }
            }
        }.start()
    }

    private fun removeBookmark(tab: Tab) {
        Store.findBookmark(this, tab.url)?.let {
            Store.deleteBookmark(this, it.id)
            BookmarkAdapter.forget(it.id)
            snack("Bookmark removed")
        }
    }

    private fun openBookmark(url: String, time: Double, multiview: Boolean) {
        val current = tabs.focused
        val t = if (current != null && current.isBlank && !multiview) {
            tabs.load(current, url)
            current
        } else {
            tabs.newTab(url, focus = !multiview)
        }
        if (time > 5) t.pendingSeek = time
        if (multiview) {
            current?.let { if (!tabs.isInMultiviewSet(it)) tabs.toggleMultiviewMember(it) }
            if (!tabs.toggleMultiviewMember(t)) snack("Multiview is full (4 tabs)")
            tabs.enterMultiview()
            tabs.focus(t)
        }
    }

    // ------------------------------------------------------------------ sessions

    private fun snapshot(id: String, name: String, created: Long, only: List<Tab>? = null): Session {
        val list = (only ?: tabs.tabs).filter { !it.isBlank || it.pendingUrl != null }
        val focusedIdx = list.indexOf(tabs.focused).coerceAtLeast(0)
        val usedGroups = list.mapNotNull { tabs.groupOf(it) }.distinct()
        return Session(
            id, name, if (created == 0L) System.currentTimeMillis() else created, System.currentTimeMillis(),
            list.map { t ->
                val v = t.mainVideo()
                SessionTab(
                    t.errorFor ?: t.pendingUrl ?: t.url, t.displayTitle(), t.muted, tabs.isInMultiviewSet(t),
                    if (v != null && v.duration > 30 && v.time > 5) v.time else if (t.pendingSeek > 0) t.pendingSeek else 0.0,
                    t.groupId,
                )
            },
            focusedIdx, tabs.inMultiview && only == null,
            usedGroups.map { SessionGroup(it.id, it.name, it.color, it.collapsed) },
        )
    }

    private fun promptSaveSession(existing: Session?, only: List<Tab>? = null, defaultName: String? = null) {
        val input = EditText(this).apply {
            setText(existing?.name ?: defaultName ?: ("Session · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())))
            setSelection(text.length)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing != null) "Update session" else if (only != null) "Save group as a session" else "Save open tabs as a session")
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val s = snapshot(existing?.id ?: Store.newId(), input.text.toString().trim().ifEmpty { "Session" }, existing?.createdAt ?: 0L, only)
                Store.upsertSession(this, s)
                Capture.view(this, b.tiles, 640) { bmp ->
                    if (bmp != null) Thread { Store.saveSessionPreview(this, s.id, bmp) }.start()
                }
                snack("Saved ${s.tabs.size} tabs as “${s.name}”")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreSession(s: Session, replace: Boolean, focusRestored: Boolean = true) {
        if (replace) tabs.closeAll()
        val created = s.tabs.map { st ->
            tabs.newTab(st.url, focus = false, lazy = true).also { t ->
                t.title = st.title
                t.muted = st.muted
                if (st.videoTime > 5) t.pendingSeek = st.videoTime
            }
        }
        if (created.isEmpty()) {
            if (tabs.tabs.isEmpty()) tabs.newTab(null)
            return
        }
        // Recreate the session's tab groups.
        for (sg in s.groups) {
            val members = created.filterIndexed { i, _ -> s.tabs[i].group == sg.id }
            if (members.isNotEmpty()) tabs.createGroup(sg.name, sg.color, members).collapsed = sg.collapsed
        }
        if (focusRestored) {
            val mv = created.filterIndexed { i, _ -> s.tabs[i].inMultiview }
            tabs.multiviewIds.clear()
            mv.take(TabManager.MAX_TILES).forEach { tabs.toggleMultiviewMember(it) }
            tabs.focus(created.getOrElse(s.focusedIndex) { created[0] })
            if (s.multiview) tabs.enterMultiview()
        }
        onTabsChanged()
    }

    // ------------------------------------------------------------------ full screen, immersive, PiP

    private fun setSystemBarsHidden(hidden: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setImmersiveMode(on: Boolean) {
        immersive = on
        setSystemBarsHidden(on)
        applyChromeVisibility()
        if (on) snack("Tap the button in the corner to show the browser bars again")
    }

    private fun applyChromeVisibility() {
        val pip = isInPictureInPictureMode
        val hide = pip || immersive
        b.toolbar.visibility = if (hide) View.GONE else View.VISIBLE
        b.progress.visibility = if (hide) View.GONE else View.INVISIBLE
        b.exitImmersive.visibility = if (immersive && !pip) View.VISIBLE else View.GONE
        tabs.chromeHidden = hide
        updateGroupBar()
        tabs.focused?.let { updateToolbar(it) }
    }

    private fun pipParams(autoEnter: Boolean = false): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val ratio = if (tabs.inMultiview) {
            Rational(b.tiles.width.coerceAtLeast(1), b.tiles.height.coerceAtLeast(1))
        } else {
            val v = tabs.focused?.mainVideo()
            if (v != null && v.width > 0 && v.height > 0) Rational(v.width, v.height) else Rational(16, 9)
        }
        // Android only accepts ratios between 1:2.39 and 2.39:1.
        val r = ratio.toFloat().coerceIn(0.42f, 2.38f)
        builder.setAspectRatio(Rational((r * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            snack("This phone doesn't support picture-in-picture")
            return
        }
        try {
            enterPictureInPictureMode(pipParams())
        } catch (e: Exception) {
            snack("Picture-in-picture is turned off for Multiview in Android settings")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tabs.arrange()
    }
}
