package io.github.pahpuhbinny.multiview

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.pahpuhbinny.multiview.cast.CastSupport
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import io.github.pahpuhbinny.multiview.security.FilterLists

class MultiviewApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        createChannels()
        ContentBlocker.loadAsync(this)
        if (Prefs.autoUpdateFilters) FilterLists.updateIfStale(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            WebViewCompat.startSafeBrowsing(this) { }
        }
        CastSupport.init(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: MultiviewApp
            private set
    }
}
