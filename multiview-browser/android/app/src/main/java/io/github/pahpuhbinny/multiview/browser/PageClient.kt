package io.github.pahpuhbinny.multiview.browser

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.download.MediaCandidate
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import java.io.ByteArrayInputStream
import java.util.Locale

/** Navigation, blocking and error handling for one tab. */
class PageClient(private val m: TabManager, private val tab: Tab) : WebViewClientCompat() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        Interstitials.take(url)?.let {
            m.runInterstitialAction(tab, it)
            return true
        }
        val scheme = request.url.scheme?.lowercase(Locale.ROOT) ?: return true
        when (scheme) {
            "http", "https" -> {
                if (!request.isForMainFrame) return false
                val isGet = request.method.equals("GET", ignoreCase = true)
                if (scheme == "http" && Prefs.httpsOnly && isGet &&
                    request.url.host?.lowercase(Locale.ROOT) !in m.httpAllowed
                ) {
                    m.load(tab, url)
                    return true
                }
                if (isGet && Prefs.stripTrackingParams) {
                    val clean = UrlTools.cleanUrl(url)
                    if (clean != url) {
                        m.load(tab, clean)
                        return true
                    }
                }
                return false
            }
            "about", "blob", "javascript" -> return false
            // Top-level data: pages are a phishing trick; never allow navigating to them.
            "data" -> return request.isForMainFrame
            "mvb", "file", "content" -> return true
            else -> {
                if (request.isForMainFrame) m.openExternal(tab, request, url)
                return true
            }
        }
    }

    // Runs on a background thread for every request the page makes.
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val scheme = uri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (request.isForMainFrame) {
            val url = uri.toString()
            tab.loadingUrl = url
            if (tab.checkFirstNav && m.checkPopupTarget(tab, host)) return TabManager.emptyResponse()
            if (ContentBlocker.isMalicious(host) && host !in m.unsafeAllowed) {
                tab.blockedMainUrl = url
                m.onBlocked(tab)
                val html = Interstitials.malware(url, host)
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
            }
            tab.blockedMainUrl = ""
            return null
        }
        val verdict = ContentBlocker.check(host, tab.pageHost, false)
        if (verdict != null && !(verdict == ContentBlocker.Category.MALWARE && host in m.unsafeAllowed)) {
            m.onBlocked(tab)
            return TabManager.emptyResponse()
        }
        MediaCandidate.kindFor(uri)?.let { m.onNetworkMedia(tab, uri.toString(), it) }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        m.onPageStarted(tab, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        m.onPageFinished(tab)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        m.onUrlChanged(tab, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
        if (!request.isForMainFrame) return
        val code = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) error.errorCode
        else WebViewClient.ERROR_UNKNOWN
        val desc = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION)) error.description.toString()
        else ""
        m.onMainFrameError(tab, request.url.toString(), code, desc)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never load a page with a bad certificate.
        handler.cancel()
        val main = tab.loadingUrl
        if (main.isNotEmpty() && UrlTools.hostOf(main) == UrlTools.hostOf(error.url)) m.onCertError(tab, error.url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        m.onRendererGone(tab)
        return true
    }
}
