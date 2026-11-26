package io.github.pahpuhbinny.multiview.vault

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.databinding.ActivityPlayerBinding
import io.github.pahpuhbinny.multiview.ui.formatDuration
import io.github.pahpuhbinny.multiview.ui.toast
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The vault's video player, with VLC-style gestures. All saved videos form
 * the playlist, so next/previous move through the vault. Videos are
 * decrypted as they play; nothing is written to disk.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), VideoGestures.Callbacks {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var items: List<Vault.Item> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val positions by lazy { getSharedPreferences("player_positions", Context.MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }

    private var controlsShown = true
    private var locked = false
    private var userSeeking = false
    private var speed = 1f
    private var speedBeforeHold = 1f
    private var resizeIndex = 0
    private var abStart = -1L
    private var abEnd = -1L
    private var brightness = -1f
    private var volumeLevel = -1f
    private var boostPercent = 0
    private var boost: LoudnessEnhancer? = null
    private var seekStartMs = -1L
    private var doubleTapTotal = 0
    private var lastDoubleTap = 0L
    private var lastIndex = -1

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val hideControls = Runnable { setControlsShown(false) }
    private val hideBubble = Runnable { b.bubble.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.vaultSecureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        volumeControlStream = AudioManager.STREAM_MUSIC
        hideSystemBars()

        if (!Vault.isOpen) {
            toast("Open the downloads vault first")
            finish()
            return
        }
        items = Vault.items()
        val id = intent.getStringExtra(EXTRA_ID)
        val start = items.indexOfFirst { it.id == id }
        if (items.isEmpty() || start < 0) {
            finish()
            return
        }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(VaultDataSource.Factory(this)))
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        b.video.player = p
        p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        val mediaItems = items.map {
            MediaItem.Builder()
                .setUri("mvvault://${it.id}")
                .setMediaId(it.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                .build()
        }
        val startPos = savedInstanceState?.getLong("pos")
            ?: intent.getLongExtra(EXTRA_POSITION, -1L).takeIf { it >= 0 }
            ?: savedPosition(items[start])
        p.setMediaItems(mediaItems, start, startPos)
        p.repeatMode = settings.getInt("repeat", Player.REPEAT_MODE_OFF)
        resizeIndex = settings.getInt("resize", 0)
        applyResize(announce = false)
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshPlayButton()
                if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControls)
            }

            override fun onPlaybackStateChanged(state: Int) {
                refreshPlayButton()
                if (state == Player.STATE_ENDED) {
                    currentItem()?.let { positions.edit().remove(it.id).apply() }
                    setControlsShown(true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onItemChanged(reason)
            }

            override fun onPlayerError(error: PlaybackException) {
                toast("Can't play this video (${error.errorCodeName})")
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    p.prepare()
                }
            }
        })
        p.prepare()
        p.playWhenReady = true

        setUpControls()
        VideoGestures(b.touch, this).attach()
        onItemChanged(-1)
        refreshLoop()
        refreshSpeed()
        handler.post(ticker)
        scheduleHide()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) {
                    showUnlock()
                    toast("Controls are locked: tap the lock to unlock")
                } else {
                    finish()
                }
            }
        })
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            boost?.release()
        } catch (e: Exception) {
        }
        player?.release()
        player = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ playlist & resume

    private fun currentItem(): Vault.Item? = player?.currentMediaItemIndex?.let { items.getOrNull(it) }

    private fun savedPosition(item: Vault.Item): Long {
        val pos = positions.getLong(item.id, 0L)
        return if (pos > 10_000 && (item.durationMs <= 0 || pos < item.durationMs - 10_000)) pos else 0L
    }

    private fun savePosition() {
        val p = player ?: return
        val item = currentItem() ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: item.durationMs
        positions.edit().apply {
            if (pos > 10_000 && (dur <= 0 || pos < dur - 10_000)) putLong(item.id, pos) else remove(item.id)
        }.apply()
    }

    private fun onItemChanged(reason: Int) {
        val p = player ?: return
        val idx = p.currentMediaItemIndex
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && lastIndex >= 0 && lastIndex != idx) {
            // The previous video played to the end: start it from the beginning next time.
            items.getOrNull(lastIndex)?.let { prev -> positions.edit().remove(prev.id).apply() }
        }
        lastIndex = idx
        val item = items.getOrNull(idx) ?: return
        b.title.text = item.title
        b.subtitle.text = "${idx + 1} of ${items.size}" + if (item.sourceHost.isNotEmpty()) " · ${item.sourceHost}" else ""
        clearAb(announce = false)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            val resume = savedPosition(item)
            if (resume > 0) {
                p.seekTo(resume)
                bubble("⏯", "Resumed at ${formatDuration(resume / 1000.0)}")
            }
        }
        refreshNav()
    }

    private fun refreshNav() {
        val p = player ?: return
        b.next.alpha = if (p.hasNextMediaItem()) 1f else 0.35f
        b.prev.alpha = if (p.hasPreviousMediaItem() || p.currentPosition > 3000) 1f else 0.35f
    }

    // ------------------------------------------------------------------ on-screen controls

    private fun setUpControls() {
        b.back.setOnClickListener { finish() }
        b.play.setOnClickListener { togglePlay(); poke() }
        b.next.setOnClickListener {
            val p = player ?: return@setOnClickListener
            savePosition()
            if (p.hasNextMediaItem()) p.seekToNextMediaItem() else toast("This is the last video")
            poke()
        }
        b.prev.setOnClickListener {
            val p = player ?: return@setOnClickListener
            savePosition()
            if (p.currentPosition > 5000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
            poke()
        }
        b.rewind.setOnClickListener { seekBy(-10_000); poke() }
        b.forward.setOnClickListener { seekBy(10_000); poke() }
        b.lock.setOnClickListener { setLocked(true) }
        b.unlock.setOnClickListener { setLocked(false) }
        b.loop.setOnClickListener { cycleLoop(); poke() }
        b.ab.setOnClickListener { stepAb(); poke() }
        b.speed.setOnClickListener { chooseSpeed() }
        b.aspect.setOnClickListener {
            resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
            applyResize(announce = true)
            poke()
        }
        b.more.setOnClickListener { showMore(it) }
        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = player?.duration?.takeIf { it > 0 } ?: return
                b.position.text = formatDuration(dur * progress / 1000 / 1000.0).ifEmpty { "0:00" }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                val p = player ?: return
                if (p.duration > 0) p.seekTo(p.duration * seekBar.progress / 1000)
                scheduleHide()
            }
        })
    }

    private fun togglePlay() {
        val p = player ?: return
        when {
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(0)
                p.play()
            }
            p.isPlaying -> p.pause()
            else -> p.play()
        }
    }

    private fun refreshPlayButton() {
        val p = player ?: return
        b.play.setImageResource(
            when {
                p.playbackState == Player.STATE_ENDED -> R.drawable.ic_replay
                p.playWhenReady -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
        )
    }

    private fun tick() {
        val p = player ?: return
        val pos = p.currentPosition
        // A–B repeat: jump back to A when playback reaches B.
        if (abStart >= 0 && abEnd > abStart && pos >= abEnd) p.seekTo(abStart)
        if (!controlsShown || userSeeking) return
        val dur = p.duration
        b.position.text = formatDuration(pos / 1000.0).ifEmpty { "0:00" }
        b.duration.text = if (dur > 0) formatDuration(dur / 1000.0) else ""
        b.seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        b.seek.secondaryProgress = if (dur > 0) (p.bufferedPosition * 1000 / dur).toInt() else 0
        refreshNav()
    }

    private fun setControlsShown(show: Boolean) {
        controlsShown = show && !locked
        b.controls.animate().cancel()
        if (controlsShown) {
            b.controls.visibility = View.VISIBLE
            b.controls.alpha = 1f
            tick()
            scheduleHide()
        } else {
            b.controls.animate().alpha(0f).setDuration(180).withEndAction { b.controls.visibility = View.GONE }
        }
    }

    /** Keeps the controls up a little longer after you touch one. */
    private fun poke() {
        if (!controlsShown) setControlsShown(true) else scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true && !userSeeking) handler.postDelayed(hideControls, 3500)
    }

    // ------------------------------------------------------------------ lock

    private fun setLocked(on: Boolean) {
        locked = on
        if (on) {
            setControlsShown(false)
            showUnlock()
            toast("Controls locked")
        } else {
            b.unlock.visibility = View.GONE
            setControlsShown(true)
        }
    }

    private val hideUnlock = Runnable { b.unlock.visibility = View.GONE }

    private fun showUnlock() {
        b.unlock.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3000)
    }

    override fun isLocked() = locked

    // ------------------------------------------------------------------ loop & A–B repeat

    private fun cycleLoop() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        settings.edit().putInt("repeat", p.repeatMode).apply()
        refreshLoop()
        bubble(
            "🔁", when (p.repeatMode) {
                Player.REPEAT_MODE_ALL -> "Loop all videos"
                Player.REPEAT_MODE_ONE -> "Loop this video"
                else -> "Loop off"
            }
        )
        refreshNav()
    }

    private fun refreshLoop() {
        val mode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        b.loop.setImageResource(if (mode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        b.loop.setColorFilter(ContextCompat.getColor(this, if (mode == Player.REPEAT_MODE_OFF) R.color.text_dim else R.color.accent))
    }

    /** First press marks A, second marks B (and starts looping), third clears. */
    private fun stepAb() {
        val p = player ?: return
        val pos = p.currentPosition
        when {
            abStart < 0 -> {
                abStart = pos
                bubble("A", "Loop start set at ${formatDuration(pos / 1000.0).ifEmpty { "0:00" }} · press again at the end")
            }
            abEnd < 0 -> {
                if (pos <= abStart + 500) {
                    toast("Play past point A first")
                    return
                }
                abEnd = pos
                p.seekTo(abStart)
                bubble("A–B", "Looping ${formatDuration(abStart / 1000.0).ifEmpty { "0:00" }} – ${formatDuration(abEnd / 1000.0)}")
            }
            else -> clearAb(announce = true)
        }
        refreshAb()
    }

    private fun clearAb(announce: Boolean) {
        val had = abStart >= 0
        abStart = -1
        abEnd = -1
        refreshAb()
        if (announce && had) bubble("A–B", "A–B loop off")
    }

    private fun refreshAb() {
        b.ab.text = when {
            abStart >= 0 && abEnd < 0 -> "A–?"
            else -> "A–B"
        }
        b.ab.setTextColor(ContextCompat.getColor(this, if (abStart >= 0) R.color.accent else R.color.text))
    }

    // ------------------------------------------------------------------ speed & aspect

    private fun setSpeed(s: Float, announce: Boolean = true) {
        speed = s
        player?.playbackParameters = PlaybackParameters(s)
        refreshSpeed()
        if (announce) bubble("⏩", "Speed ${speedLabel(s)}")
    }

    private fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else "%.2f".format(s).trimEnd('0')) + "×"

    private fun refreshSpeed() {
        b.speed.text = speedLabel(speed)
        b.speed.setTextColor(ContextCompat.getColor(this, if (speed != 1f) R.color.accent else R.color.text))
    }

    private fun chooseSpeed() {
        val labels = SPEEDS.map { speedLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_speed)
            .setSingleChoiceItems(labels, SPEEDS.indexOfFirst { abs(it - speed) < 0.01f }) { d, which ->
                setSpeed(SPEEDS[which])
                d.dismiss()
            }
            .show()
    }

    private fun applyResize(announce: Boolean) {
        val (mode, label) = RESIZE_MODES[resizeIndex.mod(RESIZE_MODES.size)]
        b.video.resizeMode = mode
        settings.edit().putInt("resize", resizeIndex).apply()
        if (announce) bubble("▣", label)
    }

    private fun showMore(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Rotation: automatic")
        pm.menu.add(0, 2, 0, "Rotation: landscape")
        pm.menu.add(0, 3, 0, "Rotation: portrait")
        pm.menu.add(0, 4, 0, "Reset brightness")
        if (boostPercent > 0) pm.menu.add(0, 5, 0, "Turn off volume boost")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                2 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                3 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                4 -> {
                    brightness = -1f
                    window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                }
                5 -> setBoost(0)
            }
            true
        }
        pm.show()
    }

    // ------------------------------------------------------------------ gestures

    override fun onTap() {
        if (locked) showUnlock() else setControlsShown(!controlsShown)
    }

    override fun onDoubleTap(zone: Int) {
        if (zone == 0) {
            togglePlay()
            bubble(if (player?.playWhenReady == true) "▶" else "⏸", if (player?.playWhenReady == true) "Play" else "Pause")
            return
        }
        val now = System.currentTimeMillis()
        // Keep double-tapping to jump further, like VLC.
        doubleTapTotal = if (now - lastDoubleTap < 1200 && doubleTapTotal * zone > 0) doubleTapTotal + zone * 10 else zone * 10
        lastDoubleTap = now
        seekBy(zone * 10_000L)
        bubble(if (zone > 0) "⏩" else "⏪", (if (doubleTapTotal > 0) "+" else "−") + "${abs(doubleTapTotal)} s")
    }

    override fun onHoldStart() {
        speedBeforeHold = speed
        setSpeed(2f, announce = false)
        bubble("⏩", "2× while holding", sticky = true)
    }

    override fun onHoldEnd() {
        setSpeed(speedBeforeHold, announce = false)
        hideBubbleSoon()
    }

    override fun onBrightness(delta: Float) {
        if (brightness < 0) brightness = currentBrightness()
        brightness = (brightness + delta * 1.3f).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = brightness }
        bubble("☀", "Brightness ${(brightness * 100).roundToInt()}%", level = brightness * 100, sticky = true)
    }

    private fun currentBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0) return current
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }.coerceIn(0.05f, 1f)
    }

    override fun onVolume(delta: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (volumeLevel < 0) volumeLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max + boostPercent / 100f
        // 0–1 is the phone's volume; 1–2 boosts it up to 200%, like VLC.
        volumeLevel = (volumeLevel + delta * 1.3f).coerceIn(0f, 2f)
        val system = min(1f, volumeLevel)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (system * max).roundToInt(), 0)
        } catch (e: SecurityException) {
        }
        setBoost(((volumeLevel - 1f).coerceAtLeast(0f) * 100).roundToInt())
        val pct = (volumeLevel * 100).roundToInt()
        bubble(if (pct == 0) "🔇" else "🔊", "Volume $pct%" + if (boostPercent > 0) " (boost)" else "", level = volumeLevel * 100, sticky = true)
    }

    private fun setBoost(percent: Int) {
        if (percent == boostPercent) return
        boostPercent = percent
        try {
            if (percent <= 0) {
                boost?.enabled = false
                return
            }
            val session = player?.audioSessionId ?: return
            if (session == C.AUDIO_SESSION_ID_UNSET) return
            val e = boost ?: LoudnessEnhancer(session).also { boost = it }
            // +100% loudness ≈ +6 dB.
            e.setTargetGain((2000 * log10(1 + percent / 100.0)).roundToInt())
            e.enabled = true
        } catch (e: Exception) {
            boostPercent = 0
            toast("Volume boost isn't available on this phone")
        }
    }

    override fun onSeekPreview(fraction: Float) {
        val p = player ?: return
        if (seekStartMs < 0) seekStartMs = p.currentPosition
        val dur = p.duration.takeIf { it > 0 } ?: return
        val target = seekTarget(fraction, dur)
        val delta = (target - seekStartMs) / 1000
        bubble(
            if (delta >= 0) "⏩" else "⏪",
            (if (delta >= 0) "+" else "−") + "${abs(delta)} s\n${formatDuration(target / 1000.0).ifEmpty { "0:00" }} / ${formatDuration(dur / 1000.0)}",
            sticky = true,
        )
    }

    override fun onSeekCommit(fraction: Float) {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0 && seekStartMs >= 0) p.seekTo(seekTarget(fraction, dur))
        seekStartMs = -1
    }

    /** A full-width swipe covers half the video (between 1 and 5 minutes). */
    private fun seekTarget(fraction: Float, dur: Long): Long {
        val range = (dur / 2).coerceIn(60_000L, 300_000L)
        return (seekStartMs + (fraction * range).toLong()).coerceIn(0L, dur)
    }

    override fun onPinch(zoomIn: Boolean) {
        resizeIndex = if (zoomIn) 1 else 0
        applyResize(announce = true)
    }

    override fun onGestureEnd() {
        volumeLevel = -1f
        seekStartMs = -1
        hideBubbleSoon()
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val dur = p.duration
        val target = (p.currentPosition + ms).coerceAtLeast(0L).let { if (dur > 0) it.coerceAtMost(dur) else it }
        p.seekTo(target)
    }

    // ------------------------------------------------------------------ feedback bubble

    private fun bubble(icon: String, text: String, level: Float = -1f, sticky: Boolean = false) {
        b.bubbleIcon.text = icon
        b.bubbleText.text = text
        if (level >= 0) {
            b.bubbleBar.visibility = View.VISIBLE
            b.bubbleBar.progress = level.roundToInt()
        } else {
            b.bubbleBar.visibility = View.GONE
        }
        b.bubble.visibility = View.VISIBLE
        handler.removeCallbacks(hideBubble)
        if (!sticky) handler.postDelayed(hideBubble, 1200)
    }

    private fun hideBubbleSoon() {
        handler.removeCallbacks(hideBubble)
        handler.postDelayed(hideBubble, 500)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_POSITION = "position"
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f).toList()
        private val RESIZE_MODES = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit to screen",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop to fill",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
        )
    }
}
