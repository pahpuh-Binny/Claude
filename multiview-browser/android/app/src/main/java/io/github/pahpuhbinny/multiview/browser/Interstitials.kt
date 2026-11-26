package io.github.pahpuhbinny.multiview.browser

import android.text.TextUtils
import java.security.SecureRandom

/**
 * Warning and error pages the browser shows in place of a site. Their
 * buttons use one-time random tokens (mvb://go/<token>), so a web page can't
 * press "continue anyway" on your behalf.
 */
object Interstitials {

    sealed class Action {
        data class LoadHttp(val url: String) : Action()
        data class ProceedUnsafe(val url: String, val host: String) : Action()
        data class Retry(val url: String) : Action()
        object Back : Action()
    }

    private val actions = HashMap<String, Action>()
    private val random = SecureRandom()

    private fun token(action: Action): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        synchronized(actions) {
            if (actions.size > 200) actions.clear()
            actions[t] = action
        }
        return "mvb://go/$t"
    }

    fun take(url: String): Action? {
        if (!url.startsWith("mvb://go/")) return null
        val t = url.removePrefix("mvb://go/")
        return synchronized(actions) { actions.remove(t) }
    }

    private fun esc(s: String) = TextUtils.htmlEncode(s)

    private fun page(icon: String, color: String, title: String, body: String, buttons: String) = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>${esc(title)}</title>
<style>
 body{margin:0;background:#0D0F14;color:#E8EAF0;font:16px/1.5 system-ui,sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center}
 main{max-width:520px;padding:28px}
 .icon{font-size:44px;width:72px;height:72px;border-radius:20px;background:$color;display:flex;align-items:center;justify-content:center;margin-bottom:18px}
 h1{font-size:22px;margin:0 0 10px}
 p{color:#9AA1B2;margin:0 0 14px;word-break:break-word}
 code{color:#E8EAF0;background:#1F2330;padding:2px 6px;border-radius:6px}
 .row{display:flex;flex-wrap:wrap;gap:10px;margin-top:22px}
 a{display:inline-block;text-decoration:none;padding:11px 18px;border-radius:12px;font-weight:600}
 .primary{background:#5B8CFF;color:#fff}
 .ghost{color:#9AA1B2;border:1px solid #323848}
</style></head><body><main>
<div class="icon">$icon</div><h1>${esc(title)}</h1>$body<div class="row">$buttons</div>
</main></body></html>
""".trimIndent()

    fun malware(url: String, host: String): String = page(
        "&#9763;", "#5A1E26",
        "Dangerous site blocked",
        "<p><code>${esc(host)}</code> is on a list of sites that spread malware or steal passwords.</p>" +
            "<p>Multiview stopped the page before anything loaded.</p>",
        "<a class=\"primary\" href=\"${token(Action.Back)}\">Go back to safety</a>" +
            "<a class=\"ghost\" href=\"${token(Action.ProceedUnsafe(url, host))}\">Open anyway (not recommended)</a>"
    )

    fun noHttps(httpUrl: String, host: String): String = page(
        "&#128275;", "#4A3A14",
        "This site doesn't support a secure connection",
        "<p><code>${esc(host)}</code> couldn't be reached over HTTPS. Over plain HTTP, anyone on your network " +
            "can see and change what you send and receive.</p>",
        "<a class=\"primary\" href=\"${token(Action.Back)}\">Go back</a>" +
            "<a class=\"ghost\" href=\"${token(Action.LoadHttp(httpUrl))}\">Continue over HTTP</a>"
    )

    fun certError(url: String, host: String): String = page(
        "&#128274;", "#5A1E26",
        "Connection isn't private",
        "<p>The security certificate for <code>${esc(host)}</code> isn't valid. Someone may be trying to " +
            "impersonate the site, so Multiview won't open it.</p>",
        "<a class=\"primary\" href=\"${token(Action.Back)}\">Go back</a>" +
            "<a class=\"ghost\" href=\"${token(Action.Retry(url))}\">Try again</a>"
    )

    fun loadError(url: String, host: String, description: String): String = page(
        "&#128268;", "#1F2330",
        "Can't reach this page",
        "<p><code>${esc(host.ifEmpty { url })}</code> didn't respond.</p><p>${esc(description)}</p>",
        "<a class=\"primary\" href=\"${token(Action.Retry(url))}\">Try again</a>" +
            "<a class=\"ghost\" href=\"${token(Action.Back)}\">Go back</a>"
    )
}
