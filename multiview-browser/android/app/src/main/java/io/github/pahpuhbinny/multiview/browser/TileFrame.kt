package io.github.pahpuhbinny.multiview.browser

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.ui.dp

/**
 * One tab's area on screen: its WebView plus, in multiview, a slim header
 * with the tab's sound, full-size and remove buttons, and a ring around the
 * tile that the address bar is controlling.
 */
@SuppressLint("ViewConstructor")
class TileFrame(context: Context, val tab: Tab) : FrameLayout(context) {

    interface Actions {
        fun onTileAudio(tab: Tab)
        fun onTileMaximize(tab: Tab)
        fun onTileRemove(tab: Tab)
        fun onTileTitle(tab: Tab)
        fun onTileTouched(tab: Tab)
    }

    val headerHeight = context.dp(32)
    private val header = LinearLayout(context)
    private val audioBtn = ImageButton(context)
    private val titleView = TextView(context)
    private val maxBtn = ImageButton(context)
    private val removeBtn = ImageButton(context)
    private val focusRing = View(context)
    private var webContainer = FrameLayout(context)
    var startPage: View? = null
        private set
    private var multiview = false

    var actions: Actions? = null

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        addView(webContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        fun small(btn: ImageButton, icon: Int, desc: String) {
            btn.setImageResource(icon)
            btn.contentDescription = desc
            btn.setBackgroundResource(android.R.color.transparent)
            btn.setColorFilter(ContextCompat.getColor(context, R.color.text))
            btn.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = context.dp(6)
            btn.setPadding(pad, pad, pad, pad)
            header.addView(btn, LinearLayout.LayoutParams(headerHeight, headerHeight))
        }
        small(audioBtn, R.drawable.ic_volume_up, context.getString(R.string.mute))
        titleView.setTextColor(ContextCompat.getColor(context, R.color.text))
        titleView.textSize = 12f
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        header.addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        small(maxBtn, R.drawable.ic_fullscreen, "Show this tab alone")
        small(removeBtn, R.drawable.ic_close, "Remove from multiview")
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, headerHeight, Gravity.TOP))
        header.visibility = GONE

        focusRing.setBackgroundResource(R.drawable.bg_tile_focus)
        focusRing.isClickable = false
        focusRing.isFocusable = false
        addView(focusRing, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        focusRing.visibility = GONE

        audioBtn.setOnClickListener { actions?.onTileAudio(tab) }
        maxBtn.setOnClickListener { actions?.onTileMaximize(tab) }
        removeBtn.setOnClickListener { actions?.onTileRemove(tab) }
        titleView.setOnClickListener { actions?.onTileTitle(tab) }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) actions?.onTileTouched(tab)
        return false
    }

    fun attachWebView(view: BrowserWebView) {
        webContainer.removeAllViews()
        webContainer.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setStartPage(view: View?) {
        startPage?.let { webContainer.removeView(it) }
        startPage = view
        if (view != null) webContainer.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun showStartPage(show: Boolean) {
        startPage?.visibility = if (show) VISIBLE else GONE
    }

    fun setMode(multiview: Boolean, focused: Boolean) {
        this.multiview = multiview
        header.visibility = if (multiview) VISIBLE else GONE
        focusRing.visibility = if (multiview && focused) VISIBLE else GONE
        (webContainer.layoutParams as LayoutParams).topMargin = if (multiview) headerHeight else 0
        webContainer.requestLayout()
        refreshHeader()
    }

    fun refreshHeader() {
        if (!multiview) return
        titleView.text = tab.displayTitle()
        audioBtn.setImageResource(if (tab.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        audioBtn.setColorFilter(
            ContextCompat.getColor(context, if (tab.muted) R.color.text_dim else if (tab.isAudible) R.color.safe else R.color.text)
        )
        audioBtn.contentDescription = context.getString(if (tab.muted) R.string.unmute else R.string.mute)
    }

    fun setChromeHidden(hidden: Boolean) {
        header.visibility = if (multiview && !hidden) VISIBLE else GONE
        (webContainer.layoutParams as LayoutParams).topMargin = if (multiview && !hidden) headerHeight else 0
        webContainer.requestLayout()
        if (hidden) focusRing.visibility = GONE
    }
}
