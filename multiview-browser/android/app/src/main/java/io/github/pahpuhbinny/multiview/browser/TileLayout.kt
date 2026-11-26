package io.github.pahpuhbinny.multiview.browser

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import io.github.pahpuhbinny.multiview.ui.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * Holds every tab's [TileFrame]. Tabs being watched are laid out in a grid
 * shaped to give 16:9 videos as much room as possible. The other tabs stay
 * attached and running, just moved off screen, so their videos keep playing.
 */
class TileLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private var shown: List<View> = emptyList()
    private val gap = context.dp(3)
    var headerHeight = context.dp(32)
    private var rects: List<Rect> = emptyList()

    fun setShown(views: List<View>) {
        shown = views
        requestLayout()
        invalidate()
    }

    /** Where tile [index] currently sits, relative to this layout. */
    fun rectOf(view: View): Rect? {
        val i = shown.indexOf(view)
        return if (i in rects.indices) Rect(rects[i]) else null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        rects = computeRects(shown.size, w, h, if (shown.size > 1) headerHeight else 0)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val idx = shown.indexOf(c)
            val r = if (idx >= 0 && idx < rects.size) rects[idx] else Rect(0, 0, w, h)
            c.measure(
                MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val idx = shown.indexOf(c)
            if (idx >= 0 && idx < rects.size) {
                val rc = rects[idx]
                c.layout(rc.left, rc.top, rc.right, rc.bottom)
                c.translationX = 0f
            } else {
                // Off screen but still "visible" to the page, so it keeps playing.
                c.layout(0, 0, w, h)
                c.translationX = -(w * 2f + 64f)
            }
        }
    }

    private fun computeRects(n: Int, w: Int, h: Int, header: Int): List<Rect> {
        if (n <= 1) return listOf(Rect(0, 0, w, h))
        var bestCols = 1
        var bestScore = -1.0
        for (cols in 1..n) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val tw = (w - (cols - 1) * gap) / cols.toDouble()
            val th = (h - (rows - 1) * gap) / rows.toDouble() - header
            if (tw <= 0 || th <= 0) continue
            val vw = min(tw, th * 16.0 / 9.0)
            val area = vw * vw * 9.0 / 16.0
            val empty = cols * rows - n
            val score = area * (1.0 - 0.02 * empty)
            if (score > bestScore) {
                bestScore = score
                bestCols = cols
            }
        }
        val cols = bestCols
        val rows = ceil(n / cols.toDouble()).toInt()
        val th = (h - (rows - 1) * gap) / rows
        val out = ArrayList<Rect>(n)
        var placed = 0
        for (row in 0 until rows) {
            val inRow = min(cols, n - placed)
            val tw = (w - (inRow - 1) * gap) / inRow
            val top = row * (th + gap)
            val bottom = if (row == rows - 1) h else top + th
            for (c in 0 until inRow) {
                val left = c * (tw + gap)
                val right = if (c == inRow - 1) w else left + tw
                out.add(Rect(left, top, right, bottom))
            }
            placed += inRow
        }
        return out
    }
}
