package io.github.pahpuhbinny.multiview.vault

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.MediaCodecList
import android.media.MediaFormat
import android.annotation.SuppressLint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.databinding.ActivityGalleryBinding
import io.github.pahpuhbinny.multiview.ui.dp
import io.github.pahpuhbinny.multiview.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Gallery mode: watch 2, 4, 8, 16, 24 or all of your saved videos at once,
 * as a wall you can zoom and pan. Tap a video to hear it, resize any video,
 * double-tap to zoom in on one.
 *
 * Phones can only decode so many videos at the same time, so the videos
 * that are most visible (and any you're listening to) play live and the
 * rest show a still until you zoom in on them. The limit is estimated from
 * the phone's video decoders and lowered automatically if it runs out.
 */
@OptIn(UnstableApi::class)
class GalleryActivity : AppCompatActivity(), GalleryCanvas.Listener, TileAdjuster.Host {

    private lateinit var b: ActivityGalleryBinding
    private val prefs by lazy { getSharedPreferences("gallery", Context.MODE_PRIVATE) }

    private var items: List<Vault.Item> = emptyList()
    private val tilesById = HashMap<String, GalleryTile>()
    private var shown: List<GalleryTile> = emptyList()
    private var selected: GalleryTile? = null

    /** Videos shown at once; 0 = all. */
    private var count = 4
    private var page = 0
    private var budget = 8
    private var playEveryTile = false
    private var fill = true
    private var allPaused = false
    private var stopped = true
    private var warnedLimit = false
    private val spans = HashMap<String, Int>()
    private var adjustersOn = true
    private var loopDefault = true
    private val adjusters = HashMap<GalleryTile, TileAdjuster>()
    private val handles = HashMap<GalleryTile, TextView>()
    private lateinit var resizePreview: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val adjusterTicker = object : Runnable {
        override fun run() {
            if (b.adjusters.visibility == View.VISIBLE) {
                adjusters.values.forEach { if (it.visibility == View.VISIBLE) it.refresh() }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.vaultSecureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Only reachable from the unlocked vault; after a restart, go back through the lock.
        if (!Vault.isOpen) {
            toast("Open the downloads vault first")
            finish()
            return
        }
        items = Vault.items()
        if (items.isEmpty()) {
            toast("Save some videos to the vault first")
            finish()
            return
        }
        count = prefs.getInt("count", 4)
        fill = prefs.getBoolean("fill", true)
        playEveryTile = prefs.getBoolean("every_live", false)
        adjustersOn = prefs.getBoolean("adjusters", true)
        loopDefault = prefs.getBoolean("loop", true)
        loadSpans()
        val order = prefs.getString("order", null)?.split(',')
        if (order != null) {
            val pos = order.withIndex().associate { it.value to it.index }
            items = items.sortedBy { pos[it.id] ?: (-1 - items.indexOf(it)) }
        }
        budget = estimateBudget(items)

        b.canvas.listener = this
        resizePreview = TextView(this).apply {
            setBackgroundResource(R.drawable.bg_resize_preview)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text))
            textSize = 16f
            visibility = View.GONE
        }
        b.adjusters.addView(resizePreview, FrameLayout.LayoutParams(0, 0))
        setUpBars()
        rebuild(resetZoom = true)
        b.hint.postDelayed({ b.hint.animate().alpha(0f).withEndAction { b.hint.visibility = View.GONE } }, 5000)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        b.canvas.post { assignPlayers() }
        handler.post(adjusterTicker)
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        handler.removeCallbacks(adjusterTicker)
        shown.forEach { releasePlayer(it) }
        saveSpans()
    }

    override fun onDestroy() {
        tilesById.values.forEach { releasePlayer(it) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        b.canvas.relayout(resetZoom = true)
    }

    // ------------------------------------------------------------------ bars

    private fun setUpBars() {
        b.back.setOnClickListener { finish() }
        b.prev.setOnClickListener { turnPage(-1) }
        b.next.setOnClickListener { turnPage(1) }
        b.playAll.setOnClickListener {
            allPaused = !allPaused
            shown.forEach { t ->
                t.paused = allPaused
                t.player?.playWhenReady = !allPaused
            }
            updateBars()
        }
        b.muteAll.setOnClickListener {
            shown.forEach { setMuted(it, true) }
            assignPlayers()
            updateBars()
        }
        b.hideBars.setOnClickListener { setBarsHidden(true) }
        refreshAdjustersButton()
        b.adjustersBtn.setOnClickListener {
            adjustersOn = !adjustersOn
            prefs.edit().putBoolean("adjusters", adjustersOn).apply()
            refreshAdjustersButton()
            layoutAdjusters()
            toast(if (adjustersOn) "Controls on each video" else "Video controls hidden")
        }
        b.more.setOnClickListener { showMore(it) }

        val choices = listOf(2, 4, 8, 16, 24, 0)
        for (n in choices) {
            val chip = (layoutInflater.inflate(R.layout.item_count_chip, b.counts, false) as Chip).apply {
                id = View.generateViewId()
                text = if (n == 0) "All (${items.size})" else n.toString()
                isCheckable = true
                isChecked = n == count
                isEnabled = n == 0 || n <= items.size || n == 2
                setOnClickListener {
                    if (count != n) {
                        count = n
                        page = 0
                        prefs.edit().putInt("count", n).apply()
                        rebuild(resetZoom = true)
                    }
                }
            }
            b.counts.addView(chip)
        }
    }

    private fun pageCount(): Int = if (count == 0) 1 else max(1, (items.size + count - 1) / count)

    private fun turnPage(delta: Int) {
        val pages = pageCount()
        page = (page + delta).mod(pages)
        rebuild(resetZoom = true)
    }

    private fun updateBars() {
        val pages = pageCount()
        val first = if (count == 0) 1 else page * count + 1
        val last = if (count == 0) items.size else min(items.size, (page + 1) * count)
        b.title.text = if (pages > 1) "${getString(R.string.gallery)} · $first–$last of ${items.size}" else getString(R.string.gallery)
        b.prev.visibility = if (pages > 1) View.VISIBLE else View.GONE
        b.next.visibility = if (pages > 1) View.VISIBLE else View.GONE
        val live = shown.count { it.player != null }
        val listening = shown.count { !it.muted }
        b.status.text = buildString {
            append("$live of ${shown.size} playing live")
            if (live < shown.size && !playEveryTile) append(" · zoom in to play the rest")
            if (listening > 0) append(" · 🔊 $listening")
        }
        b.playAll.setImageResource(if (allPaused) R.drawable.ic_play else R.drawable.ic_pause)
        b.playAll.contentDescription = getString(if (allPaused) R.string.play_all else R.string.pause_all)
        b.muteAll.alpha = if (listening > 0) 1f else 0.4f
    }

    private fun setBarsHidden(hidden: Boolean) {
        b.topBar.visibility = if (hidden) View.GONE else View.VISIBLE
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            toast("Tap an empty spot to show the controls")
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showMore(anchor: View) {
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, "Crop videos to fill their tiles").apply {
            isCheckable = true
            isChecked = fill
        }
        p.menu.add(0, 2, 0, "Play every video live").apply {
            isCheckable = true
            isChecked = playEveryTile
        }
        p.menu.add(0, 7, 0, "Loop every video").apply {
            isCheckable = true
            isChecked = loopDefault
        }
        p.menu.add(0, 3, 0, "Shuffle")
        p.menu.add(0, 4, 0, "Newest first")
        p.menu.add(0, 5, 0, "Make all videos the same size")
        p.menu.add(0, 6, 0, "Zoom out to see everything")
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    fill = !fill
                    prefs.edit().putBoolean("fill", fill).apply()
                    tilesById.values.forEach { t -> t.fill = fill }
                }
                2 -> {
                    playEveryTile = !playEveryTile
                    prefs.edit().putBoolean("every_live", playEveryTile).apply()
                    if (playEveryTile) toast("Trying to play all ${shown.size} live. If your phone runs out of video decoders, some will show a still.")
                    assignPlayers()
                }
                3 -> reorder(items.shuffled())
                4 -> reorder(items.sortedByDescending { item -> item.createdAt })
                5 -> {
                    spans.clear()
                    tilesById.values.forEach { t -> t.span = 1 }
                    saveSpans()
                    b.canvas.relayout(resetZoom = true)
                }
                6 -> b.canvas.resetZoom()
                7 -> {
                    loopDefault = !loopDefault
                    prefs.edit().putBoolean("loop", loopDefault).apply()
                    tilesById.values.forEach { t -> setLoop(t, loopDefault) }
                    adjusters.values.forEach { a -> a.refresh() }
                }
            }
            true
        }
        p.show()
    }

    private fun reorder(list: List<Vault.Item>) {
        items = list
        prefs.edit().putString("order", list.joinToString(",") { it.id }).apply()
        page = 0
        rebuild(resetZoom = true)
    }

    // ------------------------------------------------------------------ tiles

    private fun rebuild(resetZoom: Boolean) {
        val pageItems = if (count == 0) items else items.drop(page * count).take(count)
        val next = pageItems.map { item ->
            tilesById.getOrPut(item.id) {
                GalleryTile(this, item).also { t ->
                    t.span = spans[item.id] ?: 1
                    t.fill = fill
                    t.loop = loopDefault
                    loadPoster(t)
                }
            }
        }
        // Videos leaving the screen stop playing.
        (shown - next.toSet()).forEach {
            releasePlayer(it)
            if (selected === it) select(null)
            adjusters.remove(it)?.let { a -> b.adjusters.removeView(a) }
            handles.remove(it)?.let { h -> b.adjusters.removeView(h) }
        }
        shown = next
        shown.forEach { it.paused = allPaused }
        b.canvas.setTiles(shown, resetZoom)
        b.canvas.post { assignPlayers() }
        updateBars()
    }

    private fun loadPoster(t: GalleryTile) {
        if (!t.item.hasThumb) return
        lifecycleScope.launch {
            val sample = if (count == 0 || count > 16) 2 else 1
            val bmp = withContext(Dispatchers.IO) { Vault.thumbnail(applicationContext, t.item.id, sample) }
            t.setPoster(bmp)
        }
    }

    // ------------------------------------------------------------------ live playback

    /**
     * Gives players to the tiles that matter most: ones you're listening to,
     * then the most visible. The rest show their still.
     */
    private fun assignPlayers() {
        if (stopped) return
        val now = System.currentTimeMillis()
        val ranked = shown
            .map { it to b.canvas.visibleArea(it) }
            .filter { (t, area) -> (area > 0f || !t.muted) && now - t.failedAt > 15_000 }
            .sortedByDescending { (t, area) -> (if (!t.muted) 1e12f else 0f) + area }
            .map { it.first }
        val limit = if (playEveryTile) ranked.size else min(budget, ranked.size)
        val live = ranked.take(limit).toSet()
        shown.filter { it !in live }.forEach { releasePlayer(it) }
        for (t in ranked.take(limit)) if (t.player == null) t.player = makePlayer(t)
        updateBars()
    }

    private fun makePlayer(tile: GalleryTile): ExoPlayer {
        // Small buffers: many players share the phone's memory.
        val many = shown.size > 8
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, if (many) 4000 else 8000, 400, 1000)
            .setTargetBufferBytes(if (many) 2 * 1024 * 1024 else 6 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(VaultDataSource.Factory(this)))
            .setLoadControl(load)
            // Several videos can have sound at once, so don't let them fight over audio focus.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.repeatMode = if (tile.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.playbackParameters = PlaybackParameters(tile.speed)
        p.setVideoTextureView(tile.attachTexture())
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                tile.setVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }

            override fun onPlayerError(error: PlaybackException) = onTileError(tile, error)

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (tile === selected) refreshTileBar()
                adjusters[tile]?.refresh()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                adjusters[tile]?.refresh()
            }
        })
        applySound(tile, p)
        p.setMediaItem(MediaItem.fromUri("mvvault://${tile.item.id}"), tile.positionMs)
        p.playWhenReady = !tile.paused
        p.prepare()
        return p
    }

    private fun releasePlayer(t: GalleryTile) {
        t.player?.let {
            t.positionMs = it.currentPosition
            it.release()
        }
        t.player = null
        t.detachTexture()
    }

    private fun onTileError(t: GalleryTile, e: PlaybackException) {
        val outOfDecoders = e.errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        releasePlayer(t)
        t.failedAt = System.currentTimeMillis()
        if (outOfDecoders) {
            val live = shown.count { it.player != null }
            budget = max(1, live)
            if (playEveryTile) {
                playEveryTile = false
                prefs.edit().putBoolean("every_live", false).apply()
            }
            if (!warnedLimit) {
                warnedLimit = true
                toast("This phone can play about $budget of these videos at once. The others show a still until you zoom in on them.")
            }
        } else {
            toast("Couldn't play “${t.item.title}”")
        }
        updateBars()
    }

    /**
     * Estimates how many videos the phone can decode at once: the hardware
     * decoder's instance limit, and a pixel budget of about two 4K streams.
     */
    private fun estimateBudget(list: List<Vault.Item>): Int {
        val webm = list.count { it.mime == "video/webm" } > list.size / 2
        val mime = if (webm) MediaFormat.MIMETYPE_VIDEO_VP9 else MediaFormat.MIMETYPE_VIDEO_AVC
        var instances = 0
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                instances = max(instances, info.getCapabilitiesForType(mime).maxSupportedInstances)
            }
        } catch (e: Exception) {
        }
        if (instances <= 0) instances = 8
        val sized = list.filter { it.width > 0 && it.height > 0 }
        val avgPixels = if (sized.isEmpty()) 1280.0 * 720 else sized.map { it.width.toDouble() * it.height }.average()
        val byPixels = (3840.0 * 2160 * 2.2 / max(avgPixels, 320.0 * 180)).toInt()
        return min(instances - 1, byPixels).coerceIn(2, 32)
    }

    // ------------------------------------------------------------------ sound

    private fun applySound(t: GalleryTile, p: ExoPlayer) {
        p.volume = if (t.muted) 0f else t.volume
        // Muted videos don't decode their sound at all, which saves decoders and battery.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, t.muted)
            .build()
    }

    private fun setMuted(t: GalleryTile, muted: Boolean) {
        if (t.muted == muted) return
        t.muted = muted
        t.player?.let { applySound(t, it) }
        adjusters[t]?.refresh()
    }

    private fun setLoop(t: GalleryTile, loop: Boolean) {
        t.loop = loop
        t.player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Hear only this video. */
    private fun listen(t: GalleryTile) {
        shown.forEach { if (it !== t) setMuted(it, true) }
        setMuted(t, false)
        assignPlayers()
    }

    /** Add this video's sound to the ones already playing. */
    private fun addToMix(t: GalleryTile) {
        setMuted(t, false)
        assignPlayers()
    }

    // ------------------------------------------------------------------ canvas events

    override fun onTileTap(tile: GalleryTile) {
        if (!tile.muted && shown.count { !it.muted } == 1) {
            setMuted(tile, true)
        } else {
            listen(tile)
        }
        if (!adjustersOn) select(tile)
        shown.forEach { adjusters[it]?.refresh() }
        updateBars()
    }

    override fun onTileLongPress(tile: GalleryTile) {
        select(tile)
    }

    override fun onEmptyTap() {
        when {
            b.topBar.visibility != View.VISIBLE -> setBarsHidden(false)
            selected != null -> select(null)
        }
    }

    override fun onViewportChanged(settled: Boolean) {
        if (settled) {
            assignPlayers()
            layoutAdjusters()
        } else if (b.adjusters.visibility == View.VISIBLE && resizePreview.visibility != View.VISIBLE) {
            // Hide the controls while the wall moves; they come back where the videos settle.
            b.adjusters.visibility = View.INVISIBLE
        }
    }

    // ------------------------------------------------------------------ per-video controls

    private fun refreshAdjustersButton() {
        b.adjustersBtn.setColorFilter(ContextCompat.getColor(this, if (adjustersOn) R.color.accent else R.color.text))
    }

    /** Places each video's controls and resize handle over where the video is on screen. */
    private fun layoutAdjusters() {
        val overlay = b.adjusters
        val w = overlay.width.toFloat()
        val h = overlay.height.toFloat()
        val full = dp(190)
        val compact = dp(120)
        for (t in shown) {
            val r = b.canvas.screenRect(t)
            val onScreen = r != null && r.right > 0 && r.bottom > 0 && r.left < w && r.top < h
            val adj = adjusters[t]
            val handle = handles[t]
            if (!adjustersOn || !onScreen || r!!.width() < compact || r.height() < dp(60)) {
                adj?.visibility = View.GONE
            } else {
                val a = adj ?: TileAdjuster(this, t, this).also {
                    adjusters[t] = it
                    overlay.addView(it, FrameLayout.LayoutParams(0, 0))
                }
                val size = if (r.width() >= full && r.height() >= dp(110)) TileAdjuster.Size.FULL else TileAdjuster.Size.COMPACT
                a.setSize(size)
                val ah = a.heightFor(size)
                // Keep the controls inside the visible part of the video.
                val left = r.left.coerceAtLeast(0f)
                val right = r.right.coerceAtMost(w)
                val bottom = r.bottom.coerceAtMost(h)
                val lp = a.layoutParams as FrameLayout.LayoutParams
                val aw = (right - left).toInt()
                if (lp.width != aw || lp.height != ah) {
                    lp.width = aw
                    lp.height = ah
                    a.layoutParams = lp
                }
                a.translationX = left
                a.translationY = bottom - ah
                a.visibility = View.VISIBLE
                a.refresh()
            }
            if (!adjustersOn || !onScreen || r!!.width() < dp(56)) {
                handle?.visibility = View.GONE
            } else {
                val hd = handle ?: makeHandle(t).also { handles[t] = it }
                val size = dp(28)
                hd.translationX = r.right.coerceAtMost(w) - size
                hd.translationY = r.bottom.coerceAtMost(h) - size
                hd.visibility = View.VISIBLE
                hd.bringToFront()
            }
        }
        resizePreview.bringToFront()
        overlay.visibility = View.VISIBLE
    }

    /** The ◢ corner you drag to make a video bigger or smaller. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeHandle(t: GalleryTile): TextView {
        val size = dp(28)
        val hd = TextView(this).apply {
            text = "◢"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text))
            setBackgroundResource(R.drawable.bg_resize_handle)
            contentDescription = "Drag to resize"
        }
        b.adjusters.addView(hd, FrameLayout.LayoutParams(size, size))
        var startX = 0f
        var startY = 0f
        var rect: RectF? = null
        var target = t.span
        hd.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    rect = b.canvas.screenRect(t)
                    target = t.span
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    showResizePreview(rect, target)
                }
                MotionEvent.ACTION_MOVE -> {
                    val r0 = rect ?: return@setOnTouchListener true
                    val dx = e.rawX - startX
                    val dyAsWidth = (e.rawY - startY) * 16f / 9f
                    val grow = if (kotlin.math.abs(dx) >= kotlin.math.abs(dyAsWidth)) dx else dyAsWidth
                    val cell = b.canvas.cellOnScreen()
                    val gap = b.canvas.gapOnScreen()
                    if (cell > 0) {
                        target = Math.round((r0.width() + grow + gap) / (cell + gap)).coerceIn(1, MAX_SPAN)
                        showResizePreview(r0, target)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizePreview.visibility = View.GONE
                    if (target != t.span) resize(t, target - t.span)
                }
            }
            true
        }
        return hd
    }

    private fun showResizePreview(r: RectF?, span: Int) {
        r ?: return
        val cell = b.canvas.cellOnScreen()
        val gap = b.canvas.gapOnScreen()
        val pw = (span * cell + (span - 1) * gap).toInt()
        val ph = (span * cell * 9f / 16f + (span - 1) * gap).toInt()
        val lp = resizePreview.layoutParams as FrameLayout.LayoutParams
        lp.width = pw
        lp.height = ph
        resizePreview.layoutParams = lp
        resizePreview.translationX = r.left
        resizePreview.translationY = r.top
        resizePreview.text = "$span×$span"
        resizePreview.visibility = View.VISIBLE
        resizePreview.bringToFront()
    }

    // TileAdjuster.Host

    override fun adjTogglePlay(t: GalleryTile) {
        val p = t.player
        when {
            p == null -> {
                t.paused = false
                t.failedAt = 0
                assignPlayers()
                if (t.player == null) toast("Zoom in on this video, or pause another, to play it")
            }
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(0)
                p.playWhenReady = true
                t.paused = false
            }
            else -> {
                t.paused = p.playWhenReady
                p.playWhenReady = !t.paused
            }
        }
        if (t === selected) refreshTileBar()
    }

    override fun adjToggleMute(t: GalleryTile) {
        if (t.muted) {
            if (t.volume < 0.05f) t.volume = 1f
            setMuted(t, false)
            assignPlayers()
        } else {
            setMuted(t, true)
        }
        updateBars()
    }

    override fun adjVolume(t: GalleryTile, volume: Float) {
        t.volume = volume
        // Turning a video up adds it to what you hear; turning it right down mutes it.
        if (volume > 0f && t.muted) {
            setMuted(t, false)
            assignPlayers()
        } else if (volume == 0f && !t.muted) {
            setMuted(t, true)
        }
        t.player?.let { applySound(t, it) }
        updateBars()
    }

    override fun adjSpeed(t: GalleryTile) {
        val i = TILE_SPEEDS.indexOfFirst { kotlin.math.abs(it - t.speed) < 0.01f }
        t.speed = TILE_SPEEDS[(i + 1).mod(TILE_SPEEDS.size)]
        t.player?.playbackParameters = PlaybackParameters(t.speed)
    }

    override fun adjLoop(t: GalleryTile) {
        setLoop(t, !t.loop)
        toast(if (t.loop) "Looping this video" else "This video plays once")
    }

    override fun adjSeek(t: GalleryTile, fraction: Float) {
        val p = t.player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: return
        p.seekTo((dur * fraction).toLong())
    }

    override fun adjMore(t: GalleryTile, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Listen to only this one")
        if (t.muted) pm.menu.add(0, 2, 0, "Add to what you hear")
        if (t.span < MAX_SPAN) pm.menu.add(0, 3, 0, "Bigger")
        if (t.span > 1) pm.menu.add(0, 4, 0, "Smaller")
        pm.menu.add(0, 5, 0, "Zoom in on it")
        pm.menu.add(0, 6, 0, "Open in player")
        pm.menu.add(0, 7, 0, "Restart")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> listen(t)
                2 -> addToMix(t)
                3 -> resize(t, +1)
                4 -> resize(t, -1)
                5 -> b.canvas.zoomTo(t)
                6 -> openInPlayer(t)
                7 -> {
                    t.player?.seekTo(0)
                    t.positionMs = 0
                }
            }
            adjusters[t]?.refresh()
            updateBars()
            true
        }
        pm.show()
    }

    override fun adjTouched() {}

    private fun openInPlayer(t: GalleryTile) {
        val pos = t.player?.currentPosition ?: t.positionMs
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_ID, t.item.id)
                .putExtra(PlayerActivity.EXTRA_POSITION, pos)
        )
    }

    // ------------------------------------------------------------------ selected tile

    private fun select(t: GalleryTile?) {
        selected?.chosen = false
        selected = t
        t?.chosen = true
        refreshTileBar()
    }

    private fun refreshTileBar() {
        val t = selected
        val row = b.tileActions
        row.removeAllViews()
        if (t == null) {
            b.tileBar.visibility = View.GONE
            return
        }
        b.tileBar.visibility = View.VISIBLE
        fun button(label: String, icon: Int?, onClick: () -> Unit) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(ContextCompat.getColor(this@GalleryActivity, R.color.text))
                if (icon != null) {
                    setIconResource(icon)
                    iconSize = dp(18)
                    iconPadding = dp(4)
                    iconTint = ContextCompat.getColorStateList(this@GalleryActivity, R.color.text)
                }
                setOnClickListener {
                    onClick()
                    refreshTileBar()
                    updateBars()
                }
            }
            row.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        val othersListening = shown.any { it !== t && !it.muted }
        if (t.muted) {
            button("Listen", R.drawable.ic_volume_up) { listen(t) }
            if (othersListening) button("Add to mix", R.drawable.ic_add) { addToMix(t) }
        } else {
            button("Mute", R.drawable.ic_volume_off) { setMuted(t, true) }
        }
        val playing = t.player?.isPlaying == true || (t.player == null && !t.paused)
        button(if (playing) "Pause" else "Play", if (playing) R.drawable.ic_pause else R.drawable.ic_play) {
            t.paused = playing
            t.player?.playWhenReady = !t.paused
            if (!t.paused && t.player == null) assignPlayers()
        }
        if (t.span > 1) button("Smaller", null) { resize(t, -1) }
        if (t.span < MAX_SPAN) button("Bigger", null) { resize(t, +1) }
        button("Zoom", R.drawable.ic_fullscreen) { b.canvas.zoomTo(t) }
        button("Open", null) { openInPlayer(t) }
        button("Done", R.drawable.ic_close) { select(null) }
    }

    private fun resize(t: GalleryTile, delta: Int) {
        t.span = (t.span + delta).coerceIn(1, MAX_SPAN)
        if (t.span == 1) spans.remove(t.item.id) else spans[t.item.id] = t.span
        saveSpans()
        b.canvas.relayout(resetZoom = !b.canvas.isZoomedIn())
        b.canvas.post { assignPlayers() }
    }

    private fun loadSpans() {
        try {
            val o = JSONObject(prefs.getString("spans", "{}") ?: "{}")
            o.keys().forEach { k -> spans[k] = o.getInt(k).coerceIn(1, MAX_SPAN) }
        } catch (e: Exception) {
        }
    }

    private fun saveSpans() {
        val ids = items.map { it.id }.toSet()
        val o = JSONObject()
        spans.filterKeys { it in ids }.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("spans", o.toString()).apply()
    }

    companion object {
        const val MAX_SPAN = 4
        private val TILE_SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)
    }
}
