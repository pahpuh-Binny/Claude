package io.github.pahpuhbinny.multiview.vault

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import io.github.pahpuhbinny.multiview.ui.dp
import kotlin.math.abs

/**
 * VLC-style touch controls over a video:
 * - swipe up/down on the left half: brightness
 * - swipe up/down on the right half: volume
 * - swipe left/right: seek (preview while dragging, jump on release)
 * - double-tap the left/right third: back/forward 10 s; the middle: play/pause
 * - press and hold: 2× speed while held
 * - pinch: crop to fill / fit to screen
 * - tap: show or hide the controls
 */
class VideoGestures(private val view: View, private val cb: Callbacks) {

    interface Callbacks {
        fun isLocked(): Boolean
        fun onTap()
        fun onDoubleTap(zone: Int)
        fun onHoldStart()
        fun onHoldEnd()
        /** [delta] is the share of the screen height moved; positive = up. */
        fun onBrightness(delta: Float)
        fun onVolume(delta: Float)
        /** [fraction] of the screen width moved since the swipe began; positive = right. */
        fun onSeekPreview(fraction: Float)
        fun onSeekCommit(fraction: Float)
        fun onPinch(zoomIn: Boolean)
        fun onGestureEnd()
    }

    private enum class Mode { NONE, BRIGHTNESS, VOLUME, SEEK, PINCH }

    private var mode = Mode.NONE
    private var holding = false
    private var lastSeekFraction = 0f
    private var pinchTotal = 1f
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    // Swipes that start at the very top or bottom belong to Android (notifications, home).
    private val edge = view.context.dp(40)

    private val scale = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (cb.isLocked()) return false
            mode = Mode.PINCH
            pinchTotal = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchTotal *= detector.scaleFactor
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (pinchTotal > 1.15f) cb.onPinch(true) else if (pinchTotal < 0.87f) cb.onPinch(false)
        }
    })

    private val gestures = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            cb.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (cb.isLocked()) return true
            val third = view.width / 3f
            cb.onDoubleTap(if (e.x < third) -1 else if (e.x > 2 * third) 1 else 0)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (cb.isLocked() || mode != Mode.NONE) return
            holding = true
            cb.onHoldStart()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (cb.isLocked() || e1 == null || mode == Mode.PINCH || holding || e2.pointerCount > 1) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (mode == Mode.NONE) {
                if (abs(dx) < slop && abs(dy) < slop) return false
                mode = when {
                    abs(dx) > abs(dy) -> Mode.SEEK
                    e1.y < edge || e1.y > view.height - edge -> return false
                    e1.x < view.width / 2f -> Mode.BRIGHTNESS
                    else -> Mode.VOLUME
                }
            }
            when (mode) {
                Mode.BRIGHTNESS -> cb.onBrightness(distanceY / view.height)
                Mode.VOLUME -> cb.onVolume(distanceY / view.height)
                Mode.SEEK -> {
                    lastSeekFraction = dx / view.width
                    cb.onSeekPreview(lastSeekFraction)
                }
                else -> {}
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        view.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) cb.onSeekCommit(lastSeekFraction)
                if (holding) cb.onHoldEnd()
                if (mode != Mode.NONE || holding) cb.onGestureEnd()
                holding = false
                mode = Mode.NONE
                lastSeekFraction = 0f
            }
            true
        }
    }
}
