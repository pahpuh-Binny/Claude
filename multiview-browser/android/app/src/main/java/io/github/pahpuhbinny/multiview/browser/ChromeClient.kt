package io.github.pahpuhbinny.multiview.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/** Titles, progress, full screen video, pop-ups and permissions for one tab. */
class ChromeClient(private val m: TabManager, private val tab: Tab) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) = m.onProgress(tab, newProgress)

    override fun onReceivedTitle(view: WebView, title: String?) = m.onTitle(tab, title)

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) = m.onIcon(tab, icon)

    override fun onShowCustomView(view: View, callback: CustomViewCallback) = m.onShowCustomView(tab, view, callback)

    override fun onHideCustomView() = m.onHideCustomView()

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        m.onCreateWindow(tab, isUserGesture, resultMsg)

    override fun onCloseWindow(window: WebView) {
        if (tab.openerId != 0) m.closeTab(tab)
    }

    override fun onPermissionRequest(request: PermissionRequest) = m.onPermission(tab, request)

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = m.listener.onFileChooser(filePathCallback, fileChooserParams)

    // A blank poster instead of WebView's grey play-button placeholder.
    override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
}
