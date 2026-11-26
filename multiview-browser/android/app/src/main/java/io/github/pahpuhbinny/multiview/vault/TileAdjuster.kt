package io.github.pahpuhbinny.multiview.vault

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.ui.dp

/**
 * The controls drawn over the bottom of one gallery video: a seek bar, then
 * play/pause, sound, volume, speed, loop and more. They're drawn at normal
 * size whatever the gallery's zoom, and shrink to the essentials on small tiles.
 */
@SuppressLint("ViewConstructor")
class TileAdjuster(context: Context, val tile: GalleryTile, private val host: Host) : LinearLayout(context) {

    interface Host {
        fun adjTogglePlay(t: GalleryTile)
        fun adjToggleMute(t: GalleryTile)
        fun adjVolume(t: GalleryTile, volume: Float)
        fun adjSpeed(t: GalleryTile)
        fun adjLoop(t: GalleryTile)
        fun adjSeek(t: GalleryTile, fraction: Float)
        fun adjMore(t: GalleryTile, anchor: View)
        fun adjTouched()
    }

    enum class Size { FULL, COMPACT }

    private val seek = SeekBar(context)
    private val row = LinearLayout(context)
    private val play = icon(R.drawable.ic_pause, R.string.play_pause)
    private val sound = icon(R.drawable.ic_volume_off, R.string.mute)
    private val volume = SeekBar(context)
    private val speed = TextView(context)
    private val loop = icon(R.drawable.ic_repeat_one, R.string.loop)
    private val more = icon(R.drawable.ic_more, R.string.menu)
    private val spacer = View(context)
    private var dragging = false
    var size = Size.FULL
        private set

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_scrim_bottom)
        val accent = ContextCompat.getColorStateList(context, R.color.accent)
        seek.max = 1000
        seek.progressTintList = accent
        seek.thumbTintList = accent
        seek.setPadding(context.dp(10), 0, context.dp(10), 0)
        addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(24)))
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(play)
        row.addView(sound)
        row.addView(spacer, LayoutParams(0, 1, 1f))
        spacer.visibility = View.GONE
        volume.max = 100
        volume.progressTintList = accent
        volume.thumbTintList = accent
        volume.setPadding(context.dp(6), 0, context.dp(6), 0)
        row.addView(volume, LayoutParams(0, context.dp(32), 1f))
        speed.setTextColor(Color.WHITE)
        speed.textSize = 12f
        speed.gravity = Gravity.CENTER
        speed.minWidth = context.dp(34)
        speed.contentDescription = context.getString(R.string.playback_speed)
        speed.setBackgroundResource(android.R.color.transparent)
        row.addView(speed, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(32)))
        row.addView(loop)
        row.addView(more)
        // Leave room for the resize handle in the corner.
        row.setPadding(context.dp(2), 0, context.dp(30), 0)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(34)))

        play.setOnClickListener { host.adjTogglePlay(tile); refresh() }
        sound.setOnClickListener { host.adjToggleMute(tile); refresh() }
        speed.setOnClickListener { host.adjSpeed(tile); refresh() }
        loop.setOnClickListener { host.adjLoop(tile); refresh() }
        more.setOnClickListener { host.adjMore(tile, it) }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    host.adjVolume(tile, progress / 100f)
                    refreshSound()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = host.adjTouched()
            override fun onStopTrackingTouch(sb: SeekBar) = host.adjTouched()
        })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {
                dragging = true
                host.adjTouched()
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                host.adjSeek(tile, sb.progress / 1000f)
            }
        })
        refresh()
    }

    private fun icon(res: Int, desc: Int) = ImageButton(context).apply {
        setImageResource(res)
        contentDescription = context.getString(desc)
        setBackgroundResource(android.R.color.transparent)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setColorFilter(Color.WHITE)
        val pad = context.dp(6)
        setPadding(pad, pad, pad, pad)
        layoutParams = LayoutParams(context.dp(32), context.dp(32))
    }

    fun setSize(s: Size) {
        if (s == size) return
        size = s
        val full = s == Size.FULL
        seek.visibility = if (full) View.VISIBLE else View.GONE
        volume.visibility = if (full) View.VISIBLE else View.GONE
        speed.visibility = if (full) View.VISIBLE else View.GONE
        // Compact: just play, sound, loop and more, spread out.
        spacer.visibility = if (full) View.GONE else View.VISIBLE
    }

    fun heightFor(s: Size) = context.dp(if (s == Size.FULL) 58 else 34)

    private fun refreshSound() {
        sound.setImageResource(if (tile.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        sound.setColorFilter(if (tile.muted) Color.WHITE else ContextCompat.getColor(context, R.color.accent))
        sound.contentDescription = context.getString(if (tile.muted) R.string.unmute else R.string.mute)
    }

    /** Updates the buttons and the seek bar from the tile's player. */
    fun refresh() {
        val p = tile.player
        val ended = p?.playbackState == Player.STATE_ENDED
        play.setImageResource(
            when {
                ended -> R.drawable.ic_replay
                p != null && p.playWhenReady -> R.drawable.ic_pause
                p == null && !tile.paused -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
        )
        refreshSound()
        if (volume.visibility == View.VISIBLE) volume.progress = (tile.volume * 100).toInt()
        speed.text = (if (tile.speed == tile.speed.toInt().toFloat()) tile.speed.toInt().toString() else tile.speed.toString()) + "×"
        speed.setTextColor(if (tile.speed != 1f) ContextCompat.getColor(context, R.color.accent) else Color.WHITE)
        loop.setImageResource(if (tile.loop) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        loop.setColorFilter(if (tile.loop) ContextCompat.getColor(context, R.color.accent) else 0x99FFFFFF.toInt())
        loop.contentDescription = if (tile.loop) "Looping (tap to play once)" else "Plays once (tap to loop)"
        if (!dragging && seek.visibility == View.VISIBLE) {
            val dur = p?.duration ?: 0L
            val pos = p?.currentPosition ?: tile.positionMs
            val total = if (dur > 0) dur else tile.item.durationMs
            seek.progress = if (total > 0) (pos * 1000 / total).toInt().coerceIn(0, 1000) else 0
            seek.isEnabled = p != null
        }
    }
}
