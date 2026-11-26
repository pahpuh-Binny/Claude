package io.github.pahpuhbinny.multiview.data

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View

/** Screenshots of what's on screen (including playing video), used for previews and thumbnails. */
object Capture {

    /**
     * Copies the on-screen pixels of [view] (optionally only [crop], in the
     * view's own pixels), scaled down to at most [maxWidth] wide.
     */
    fun view(activity: Activity, view: View, maxWidth: Int, crop: Rect? = null, done: (Bitmap?) -> Unit) {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return done(null)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val src = if (crop != null) {
            Rect(loc[0] + crop.left, loc[1] + crop.top, loc[0] + crop.right, loc[1] + crop.bottom)
        } else {
            Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        }
        val decor = activity.window.decorView
        if (!src.intersect(0, 0, decor.width, decor.height) || src.width() < 8 || src.height() < 8) return done(null)
        val scale = minOf(1f, maxWidth.toFloat() / src.width())
        val w = (src.width() * scale).toInt().coerceAtLeast(1)
        val h = (src.height() * scale).toInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return done(null)
        }
        try {
            PixelCopy.request(activity.window, src, bmp, { result ->
                done(if (result == PixelCopy.SUCCESS) bmp else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            done(null)
        }
    }
}
