package io.github.pahpuhbinny.multiview.browser

import android.content.Context
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import org.json.JSONArray
import org.json.JSONObject

/** Builds the page script (assets/js/inject.js) with the current settings baked in. */
object Injector {
    private var template: String? = null

    fun script(context: Context): String {
        val t = template ?: context.assets.open("js/inject.js").use { it.readBytes().toString(Charsets.UTF_8) }
            .also { template = it }
        val cfg = JSONObject()
            .put("keepAlive", Prefs.keepPlaying)
            .put("guard", Prefs.pauseGuard)
            .put("gpc", Prefs.sendGpc)
            .put("css", if (Prefs.blockAds) ContentBlocker.cosmeticCss else "")
            .put("skipAds", Prefs.blockAds && Prefs.skipVideoAds)
            .put("allow", JSONArray(Prefs.allowlist.toList()))
        return t.replace("__MV_BRIDGE__", Prefs.bridgeName)
            .replace("/*__MV_CONFIG__*/ null", cfg.toString())
    }
}
