package io.github.pahpuhbinny.multiview.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.pahpuhbinny.multiview.MultiviewApp
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.ui.MainActivity

/**
 * Keeps the app alive while videos play in the background and shows the
 * "Playing in N tabs" notification with Pause all / Play all. Headset
 * buttons and the lock screen control it through the media session.
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var noisyRegistered = false
    private var started = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Headphones unplugged: don't suddenly play out loud.
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) PlaybackHub.pauseAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "Multiview").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { PlaybackHub.playAll() }
                override fun onPause() { PlaybackHub.pauseAll() }
                override fun onStop() { PlaybackHub.pauseAll() }
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        PlaybackHub.service = this
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> PlaybackHub.pauseAll()
            ACTION_PLAY -> PlaybackHub.playAll()
            ACTION_STOP -> {
                PlaybackHub.stopAll()
                stopPlayback()
                return START_NOT_STICKY
            }
        }
        val s = PlaybackHub.summary()
        if (!started) {
            started = true
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(s),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        }
        refresh(s)
        return START_NOT_STICKY
    }

    fun refresh(s: PlaybackHub.Summary) {
        val playing = s.playingTabs > 0
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title(s))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.titles.joinToString(" · "))
                .build()
        )
        if (started) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(s))
            } catch (e: SecurityException) {
            }
        }
        holdLocks(playing && !PlaybackHub.appVisible)
    }

    private fun title(s: PlaybackHub.Summary): String = when {
        s.playingTabs == 1 -> "Playing in 1 tab"
        s.playingTabs > 1 -> "Playing in ${s.playingTabs} tabs"
        else -> "Paused"
    }

    private fun buildNotification(s: PlaybackHub.Summary): Notification {
        val playing = s.playingTabs > 0
        val toggle = if (playing) {
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.pause_all), action(ACTION_PAUSE, 1))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, getString(R.string.play_all), action(ACTION_PLAY, 2))
        }
        val stop = NotificationCompat.Action(R.drawable.ic_close, getString(R.string.stop), action(ACTION_STOP, 3))
        return NotificationCompat.Builder(this, MultiviewApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title(s))
            .setContentText(s.titles.joinToString(" · ").ifEmpty { getString(R.string.app_name) })
            .setContentIntent(contentIntent())
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Keeps the CPU and Wi-Fi awake while videos stream with the screen off. */
    @SuppressLint("WakelockTimeout")
    private fun holdLocks(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Multiview:playback").apply {
                        setReferenceCounted(false)
                        acquire(3 * 60 * 60 * 1000L)
                    }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm?.createWifiLock(mode, "Multiview:playback")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    fun stopPlayback() {
        holdLocks(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (PlaybackHub.service === this) PlaybackHub.service = null
        holdLocks(false)
        if (noisyRegistered) unregisterReceiver(noisy)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 41
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_PLAY = "play"
        private const val ACTION_STOP = "stop"
    }
}
