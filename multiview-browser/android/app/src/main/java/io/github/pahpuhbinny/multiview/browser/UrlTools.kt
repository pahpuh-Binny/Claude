package io.github.pahpuhbinny.multiview.browser

import android.net.Uri
import android.util.Patterns
import android.webkit.URLUtil
import io.github.pahpuhbinny.multiview.Prefs
import java.net.IDN
import java.util.Locale

object UrlTools {

    data class Engine(val id: String, val label: String, val template: String)

    /** Every engine is set to SafeSearch / family filter OFF. */
    val engines = listOf(
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s&kp=-2&kl=wt-wt"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q=%s&safesearch=off"),
        Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s&qadf=none"),
        Engine("qwant", "Qwant", "https://www.qwant.com/?q=%s&s=0"),
        Engine("google", "Google", "https://www.google.com/search?q=%s&safe=off"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s&adlt=off"),
    )

    fun searchUrl(query: String): String {
        val e = engines.firstOrNull { it.id == Prefs.searchEngine } ?: engines[0]
        return e.template.replace("%s", Uri.encode(query.trim()))
    }

    /**
     * What to load for something typed in the address bar. [httpFallback] is
     * the plain-http address to offer if the site turns out not to support
     * HTTPS (only for bare addresses like "example.com").
     */
    data class Resolved(val url: String, val httpFallback: String?)

    fun resolveInput(raw: String): Resolved {
        val input = raw.trim()
        if (input.isEmpty()) return Resolved("about:blank", null)
        val lower = input.lowercase(Locale.ROOT)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return Resolved(cleanUrl(input), null)
        if (lower == "about:blank") return Resolved("about:blank", null)
        if (!input.contains(' ') && looksLikeHost(input)) {
            return Resolved(cleanUrl("https://$input"), "http://$input")
        }
        return Resolved(searchUrl(input), null)
    }

    private fun looksLikeHost(s: String): Boolean {
        val hostPart = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.startsWith("localhost")) return true
        val ascii = try { IDN.toASCII(hostPart.substringBefore(':')) } catch (e: Exception) { return false }
        if (Patterns.IP_ADDRESS.matcher(ascii).matches()) return true
        return Patterns.DOMAIN_NAME.matcher(ascii).matches() && ascii.contains('.') &&
            !ascii.endsWith('.') && ascii.substringAfterLast('.').length >= 2 &&
            ascii.substringAfterLast('.').all { it.isLetter() }
    }

    // Query parameters that only exist to track you across sites.
    private val trackingParams = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
        "igshid", "igsh", "yclid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id",
        "vero_id", "wickedid", "twclid", "ttclid", "srsltid", "rb_clickid", "s_cid", "_openstat",
        "ref_src", "ref_url", "spm", "scm", "epik", "li_fat_id", "mbid", "trk_contact",
    )

    fun cleanUrl(url: String): String {
        if (!Prefs.stripTrackingParams) return url
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        if (uri.isOpaque || uri.encodedQuery.isNullOrEmpty()) return url
        val host = uri.host?.lowercase(Locale.ROOT) ?: return url
        val names = try { uri.queryParameterNames } catch (e: Exception) { return url }
        val isYouTube = host == "youtu.be" || host.endsWith("youtube.com")
        val drop = names.filter { n ->
            val l = n.lowercase(Locale.ROOT)
            l.startsWith("utm_") || l in trackingParams || (isYouTube && (l == "si" || l == "pp" || l == "feature"))
        }
        if (drop.isEmpty()) return url
        // Rebuild the query keeping the original encoding and order of the parameters we keep.
        val kept = uri.encodedQuery!!.split('&').filter { part ->
            val name = Uri.decode(part.substringBefore('='))
            name !in drop
        }
        return uri.buildUpon().encodedQuery(kept.joinToString("&").ifEmpty { null }).build().toString()
    }

    fun hostOf(url: String?): String =
        try { Uri.parse(url ?: "").host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }

    fun displayUrl(url: String?): String {
        if (url.isNullOrEmpty() || url == "about:blank") return ""
        return url
    }

    fun isWeb(url: String?): Boolean = url != null && (URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url))

    /** Approximate "registrable domain" (example.co.uk, example.com) for first/third-party checks. */
    fun siteOf(host: String): String {
        val parts = host.trimEnd('.').split('.')
        if (parts.size <= 2) return host
        val tld = parts.last()
        val sld = parts[parts.size - 2]
        val twoPart = tld.length == 2 && sld in setOf("co", "com", "net", "org", "gov", "edu", "ac", "gob", "ne", "or", "go", "nic", "ltd", "plc")
        return if (twoPart) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    // ---- User agent: look like regular Chrome instead of an app's WebView.
    private val chromeVersion = Regex("Chrome/(\\d+)")

    fun mobileUserAgent(webViewUa: String): String {
        val major = chromeVersion.find(webViewUa)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    fun desktopUserAgent(webViewUa: String): String {
        val major = chromeVersion.find(webViewUa)?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}
