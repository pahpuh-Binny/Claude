package io.github.pahpuhbinny.multiview.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.data.Bookmark
import io.github.pahpuhbinny.multiview.data.Store

/** What a new tab shows: a short intro and your bookmarks with their moving previews. */
@SuppressLint("ViewConstructor")
class StartPageView(context: Context, private val onOpen: (Bookmark) -> Unit) : FrameLayout(context) {

    private val adapter = BookmarkAdapter(context, context.dp(190), onOpen)
    private val empty = TextView(context)
    private val list = RecyclerView(context)

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        val pad = context.dp(20)
        col.setPadding(0, context.dp(40), 0, pad)

        val title = TextView(context).apply {
            text = context.getString(R.string.app_name)
            textSize = 30f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val tag = TextView(context).apply {
            text = context.getString(R.string.start_tagline)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            gravity = Gravity.CENTER
            setPadding(pad, context.dp(6), pad, context.dp(28))
        }
        val section = TextView(context).apply {
            text = context.getString(R.string.start_bookmarks)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(pad, 0, pad, context.dp(4))
        }
        list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = adapter
        list.clipToPadding = false
        list.setPadding(context.dp(14), 0, context.dp(14), 0)
        empty.apply {
            text = context.getString(R.string.no_bookmarks)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setPadding(pad, context.dp(4), pad, 0)
        }
        col.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        col.addView(tag, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        col.addView(section, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        col.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        col.addView(empty, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.addView(col)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun refresh() {
        val items = Store.bookmarks(context).take(20)
        adapter.items = items
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            if (visibility == VISIBLE) {
                refresh()
                adapter.start()
            } else {
                adapter.stop()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) {
            refresh()
            adapter.start()
        }
    }

    override fun onDetachedFromWindow() {
        adapter.stop()
        super.onDetachedFromWindow()
    }
}
