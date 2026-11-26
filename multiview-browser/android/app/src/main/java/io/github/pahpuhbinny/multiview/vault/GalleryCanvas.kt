package io.github.pahpuhbinny.multiview.vault

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import io.github.pahpuhbinny.multiview.ui.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The gallery's video wall. Tiles are packed into a grid (bigger tiles take
 * 2×2, 3×3 or 4×4 cells) whose column count is chosen so the videos are as
 * large as possible while everything fits on screen. Pinch to zoom, drag
 * or fling to move around, double-tap a tile to zoom to it.
 */
class GalleryCanvas @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    interface Listener {
        fun onTileTap(tile: GalleryTile)
        fun onTileLongPress(tile: GalleryTile)
        fun onEmptyTap()
        /** The visible area changed; [settled] once movement has stopped for a moment. */
        fun onViewportChanged(settled: Boolean)
    }

    var listener: Listener? = null

    var tiles: List<GalleryTile> = emptyList()
        private set
    private val rects = HashMap<GalleryTile, Rect>()
    private var contentW = 0
    private var contentH = 0
    var columns = 1
        private set
    private val gap = context.dp(2)
    private val noRect = Rect()
    private var cellWidth = 0

    /** Holds the tiles at their un-zoomed positions; zoom and pan transform it as a whole. */
    private val content = object : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(contentW, contentH)
            for (i in 0 until childCount) {
                val c = getChildAt(i) as GalleryTile
                val r = rects[c] ?: noRect
                c.measure(
                    MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY)
                )
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i) as GalleryTile
                val rc = rects[c] ?: continue
                c.layout(rc.left, rc.top, rc.right, rc.bottom)
            }
        }
    }

    var scale = 1f
        private set
    private var tx = 0f
    private var ty = 0f
    private var minScale = 1f
    private var maxScale = 4f
    private var animator: ValueAnimator? = null
    private val scroller = OverScroller(context)
    private var pendingReset = true

    private val settle = Runnable { listener?.onViewportChanged(true) }

    init {
        content.pivotX = 0f
        content.pivotY = 0f
        content.clipChildren = true
        addView(content)
    }

    // ------------------------------------------------------------------ tiles & grid

    fun setTiles(list: List<GalleryTile>, resetZoom: Boolean) {
        content.removeAllViews()
        tiles = list
        list.forEach { content.addView(it) }
        relayout(resetZoom)
    }

    /** Re-packs the grid (after resizing a tile, rotating, or changing the set). */
    fun relayout(resetZoom: Boolean) {
        if (width == 0 || height == 0) {
            pendingReset = pendingReset || resetZoom
            return
        }
        rects.clear()
        if (tiles.isEmpty()) {
            contentW = width
            contentH = height
            content.requestLayout()
            return
        }
        val spans = tiles.map { it.span }
        columns = GalleryGrid.bestColumns(spans, width, height, gap)
        val packing = GalleryGrid.pack(spans, columns)
        val cellW = (width - (columns - 1) * gap) / columns
        val cellH = cellW * 9 / 16
        cellWidth = cellW
        tiles.forEachIndexed { i, t ->
            val c = packing.cells[i]
            val left = c.col * (cellW + gap)
            val top = c.row * (cellH + gap)
            rects[t] = Rect(left, top, left + c.span * cellW + (c.span - 1) * gap, top + c.span * cellH + (c.span - 1) * gap)
        }
        contentW = width
        contentH = (packing.rows * cellH + (packing.rows - 1) * gap).coerceAtLeast(1)
        minScale = min(1f, height.toFloat() / contentH)
        // Zoom in far enough that one small tile can fill the screen.
        maxScale = max(3f, columns * 1.25f)
        content.requestLayout()
        requestLayout()
        if (resetZoom || pendingReset) {
            pendingReset = false
            scale = minScale
            tx = 0f
            ty = 0f
        }
        clampAndApply()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        content.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, contentW, contentH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation or the bars appearing/disappearing: re-fit, keeping your zoom if you'd zoomed in.
        val refit = !isZoomedIn()
        post { relayout(resetZoom = refit) }
    }

    // ------------------------------------------------------------------ viewport

    private fun clampAndApply() {
        scale = scale.coerceIn(minScale, maxScale)
        val cw = contentW * scale
        val ch = contentH * scale
        tx = if (cw <= width) (width - cw) / 2 else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2 else ty.coerceIn(height - ch, 0f)
        content.scaleX = scale
        content.scaleY = scale
        content.translationX = tx
        content.translationY = ty
        listener?.onViewportChanged(false)
        removeCallbacks(settle)
        postDelayed(settle, 280)
    }

    /** How much of [tile] is on screen, in screen pixels². */
    fun visibleArea(tile: GalleryTile): Float {
        val r = rects[tile] ?: return 0f
        val s = RectF(tx + r.left * scale, ty + r.top * scale, tx + r.right * scale, ty + r.bottom * scale)
        return if (s.intersect(0f, 0f, width.toFloat(), height.toFloat())) s.width() * s.height() else 0f
    }

    fun isZoomedIn() = scale > minScale * 1.05f

    /** Where [tile] is on screen right now, relative to this view. */
    fun screenRect(tile: GalleryTile): RectF? {
        val r = rects[tile] ?: return null
        return RectF(tx + r.left * scale, ty + r.top * scale, tx + r.right * scale, ty + r.bottom * scale)
    }

    /** One grid cell's on-screen width, and the gap between cells. */
    fun cellOnScreen(): Float = cellWidth * scale

    fun gapOnScreen(): Float = gap * scale

    private fun tileAt(x: Float, y: Float): GalleryTile? {
        val cx = ((x - tx) / scale).toInt()
        val cy = ((y - ty) / scale).toInt()
        return tiles.firstOrNull { rects[it]?.contains(cx, cy) == true }
    }

    private fun animateTo(targetScale: Float, targetTx: Float, targetTy: Float) {
        animator?.cancel()
        scroller.forceFinished(true)
        val s0 = scale
        val x0 = tx
        val y0 = ty
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                scale = s0 + (targetScale - s0) * f
                tx = x0 + (targetTx - x0) * f
                ty = y0 + (targetTy - y0) * f
                clampAndApply()
            }
            start()
        }
    }

    /** Zooms so [tile] fills the screen. */
    fun zoomTo(tile: GalleryTile) {
        val r = rects[tile] ?: return
        val s = (min(width.toFloat() / r.width(), height.toFloat() / r.height()) * 0.98f).coerceIn(minScale, maxScale)
        animateTo(s, width / 2f - r.exactCenterX() * s, height / 2f - r.exactCenterY() * s)
    }

    fun resetZoom() = animateTo(minScale, 0f, 0f)

    // ------------------------------------------------------------------ gestures

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            animator?.cancel()
            scroller.forceFinished(true)
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val newScale = (scale * d.scaleFactor).coerceIn(minScale, maxScale)
            val f = newScale / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = newScale
            clampAndApply()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            tx -= dx
            ty -= dy
            clampAndApply()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            val minX = min(0f, width - contentW * scale).toInt()
            val minY = min(0f, height - contentH * scale).toInt()
            scroller.fling(tx.toInt(), ty.toInt(), vx.toInt(), vy.toInt(), minX, 0, minY, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val t = tileAt(e.x, e.y)
            if (t != null) listener?.onTileTap(t) else listener?.onEmptyTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val t = tileAt(e.x, e.y)
            if (t == null || (isZoomedIn() && visibleArea(t) > width * height * 0.45f)) resetZoom() else zoomTo(t)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            tileAt(e.x, e.y)?.let { listener?.onTileLongPress(it) }
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            tx = scroller.currX.toFloat()
            ty = scroller.currY.toFloat()
            clampAndApply()
            postInvalidateOnAnimation()
        }
    }

    // Tiles never handle touches themselves; the canvas does all gestures.
    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
}
