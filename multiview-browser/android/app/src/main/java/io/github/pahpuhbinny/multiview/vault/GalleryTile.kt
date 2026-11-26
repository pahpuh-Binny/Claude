package io.github.pahpuhbinny.multiview.vault

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.ui.dp

/**
 * One video in the gallery: a still (its thumbnail) until it has a player,
 * then live video in a TextureView, which keeps working when the gallery
 * is zoomed. Shows a sound badge and, when selected, an outline.
 */
@SuppressLint("ViewConstructor")
class GalleryTile(context: Context, val item: Vault.Item) : ViewGroup(context) {

    /** Size in grid cells (1 = one cell, 2 = 2×2, …). */
    var span = 1
    var muted = true
        set(v) {
            field = v
            refreshBadges()
        }
    var chosen = false
        set(v) {
            field = v
            refreshBadges()
        }
    var fill = true
        set(v) {
            field = v
            requestLayout()
        }

    /** Where playback was, so a tile resumes where it left off when it goes live again. */
    var positionMs = 0L
    var player: ExoPlayer? = null
    var paused = false
    /** The player failed (usually: the phone ran out of video decoders). */
    var failedAt = 0L

    private val poster = ImageView(context)
    var texture: TextureView? = null
        private set
    private val sound = TextView(context)
    private val title = TextView(context)
    private val ring = View(context)
    private var videoW = item.width
    private var videoH = item.height

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(poster)
        sound.textSize = 11f
        sound.setTextColor(Color.WHITE)
        sound.gravity = Gravity.CENTER
        sound.background = GradientDrawable().apply {
            cornerRadius = context.dp(9).toFloat()
            setColor(0xAA000000.toInt())
        }
        addView(sound)
        title.textSize = 11f
        title.setTextColor(Color.WHITE)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        title.text = item.title
        addView(title)
        ring.background = GradientDrawable().apply {
            setStroke(context.dp(3), ContextCompat.getColor(context, R.color.accent))
        }
        addView(ring)
        refreshBadges()
    }

    fun setPoster(b: Bitmap?) {
        poster.setImageBitmap(b)
    }

    /** Creates the surface the player draws into. */
    fun attachTexture(): TextureView {
        texture?.let { return it }
        val t = TextureView(context)
        addView(t, 1)
        texture = t
        return t
    }

    fun detachTexture() {
        texture?.let { removeView(it) }
        texture = null
    }

    fun setVideoSize(w: Int, h: Int, pixelRatio: Float) {
        if (w <= 0 || h <= 0) return
        videoW = (w * pixelRatio).toInt()
        videoH = h
        requestLayout()
    }

    private fun refreshBadges() {
        sound.text = if (muted) "🔇" else "🔊"
        sound.visibility = if (muted && !chosen) View.GONE else View.VISIBLE
        ring.visibility = if (chosen) View.VISIBLE else View.GONE
        if (!muted) {
            (sound.background as GradientDrawable).setColor(ContextCompat.getColor(context, R.color.accent))
        } else {
            (sound.background as GradientDrawable).setColor(0xAA000000.toInt())
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val exact = { v: Int -> MeasureSpec.makeMeasureSpec(v.coerceAtLeast(0), MeasureSpec.EXACTLY) }
        poster.measure(exact(w), exact(h))
        ring.measure(exact(w), exact(h))
        val (vw, vh) = videoRect(w, h)
        texture?.measure(exact(vw), exact(vh))
        val badge = context.dp(22)
        sound.measure(exact(badge + context.dp(6)), exact(badge - context.dp(4)))
        title.measure(exact((w - context.dp(12)).coerceAtLeast(0)), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    /** Size of the video inside a w×h tile: cropped to fill it, or fitted inside it. */
    private fun videoRect(w: Int, h: Int): Pair<Int, Int> {
        if (videoW <= 0 || videoH <= 0 || w <= 0 || h <= 0) return w to h
        val videoAspect = videoW.toFloat() / videoH
        val tileAspect = w.toFloat() / h
        val wider = videoAspect > tileAspect
        return if (wider == fill) {
            // Match heights.
            (h * videoAspect).toInt() to h
        } else {
            w to (w / videoAspect).toInt()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        poster.layout(0, 0, w, h)
        ring.layout(0, 0, w, h)
        texture?.let {
            val vw = it.measuredWidth
            val vh = it.measuredHeight
            val left = (w - vw) / 2
            val top = (h - vh) / 2
            it.layout(left, top, left + vw, top + vh)
        }
        val m = context.dp(6)
        sound.layout(m, m, m + sound.measuredWidth, m + sound.measuredHeight)
        // Only show the title when the tile is big enough to read it.
        title.visibility = if (w > context.dp(130)) View.VISIBLE else View.GONE
        title.layout(m, h - m - title.measuredHeight, m + title.measuredWidth, h - m)
    }
}
