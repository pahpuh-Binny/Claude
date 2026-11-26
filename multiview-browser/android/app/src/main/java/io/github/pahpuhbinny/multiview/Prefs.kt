package io.github.pahpuhbinny.multiview

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.security.SecureRandom

/** Typed access to the user's settings (backed by the Settings screen). */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, false)
        sp = PreferenceManager.getDefaultSharedPreferences(context)
    }

    val prefs: SharedPreferences get() = sp

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)

    // Playback
    val keepPlaying get() = b("keep_playing", true)
    val pauseGuard get() = b("pause_guard", true)
    val soloAudio get() = b("solo_audio", true)
    val autoPip get() = b("auto_pip", false)

    // Protection
    val blockAds get() = b("block_ads", true)
    val blockTrackers get() = b("block_trackers", true)
    val blockMalware get() = b("block_malware", true)
    val safeBrowsing get() = b("safe_browsing", true)
    val httpsOnly get() = b("https_only", true)
    val blockThirdPartyCookies get() = b("block_3p_cookies", true)
    val stripTrackingParams get() = b("strip_params", true)
    val sendGpc get() = b("gpc", true)
    val skipVideoAds get() = b("skip_video_ads", true)
    val confirmExternalApps get() = b("confirm_external_apps", true)
    val clearOnExit get() = b("clear_on_exit", false)
    val autoUpdateFilters get() = b("auto_update_filters", true)

    // Search
    val searchEngine: String get() = sp.getString("search_engine", "duckduckgo") ?: "duckduckgo"

    // Downloads
    val vaultLock get() = b("vault_lock", true)
    val vaultSecureScreen get() = b("vault_secure_screen", true)

    // Sessions
    val restoreTabs get() = b("restore_tabs", true)

    /** Sites where you've switched ad and tracker blocking off. */
    var allowlist: Set<String>
        get() = sp.getStringSet("allowlist", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("allowlist", v).apply()

    var desktopModeDefault: Boolean
        get() = b("desktop_default", false)
        set(v) = sp.edit().putBoolean("desktop_default", v).apply()

    var filtersUpdatedAt: Long
        get() = sp.getLong("filters_updated_at", 0L)
        set(v) = sp.edit().putLong("filters_updated_at", v).apply()

    /**
     * Name of the page-to-app message object. Random per install so pages
     * can't recognise this browser by looking for a fixed name.
     */
    val bridgeName: String
        get() {
            sp.getString("bridge_name", null)?.let { return it }
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            val name = "_" + bytes.joinToString("") { "%02x".format(it) }
            sp.edit().putString("bridge_name", name).apply()
            return name
        }

    var askedNotifications: Boolean
        get() = b("asked_notifications", false)
        set(v) = sp.edit().putBoolean("asked_notifications", v).apply()
}
