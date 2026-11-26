package io.github.pahpuhbinny.multiview.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.browser.UrlTools
import io.github.pahpuhbinny.multiview.data.Bookmark
import io.github.pahpuhbinny.multiview.data.Store
import java.util.concurrent.Executors

/** Bookmark cards whose previews play as a short loop of frames captured from the video. */
class BookmarkAdapter(
    private val context: Context,
    private val cardWidthPx: Int = ViewGroup.LayoutParams.MATCH_PARENT,
    private val onOpen: (Bookmark) -> Unit,
    private val onLongPress: ((Bookmark, View) -> Unit)? = null,
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val preview: ImageView = v.findViewById(R.id.preview)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val host: TextView = v.findViewById(R.id.host)
        var item: Bookmark? = null
        var shownKey: String? = null
    }

    var items: List<Bookmark> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    private val attached = HashSet<VH>()
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            attached.forEach { show(it) }
            if (running) handler.postDelayed(this, 420)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(ticker, 420)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
        if (cardWidthPx > 0) v.layoutParams = (v.layoutParams as ViewGroup.MarginLayoutParams).apply { width = cardWidthPx }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val b = items[position]
        h.item = b
        h.shownKey = null
        h.title.text = b.title.ifBlank { UrlTools.hostOf(b.url) }
        h.host.text = UrlTools.hostOf(b.url)
        if (b.videoTime > 1) {
            h.time.visibility = View.VISIBLE
            h.time.text = "▶ " + formatDuration(b.videoTime) + (if (b.videoDuration > 0) " / " + formatDuration(b.videoDuration) else "")
        } else {
            h.time.visibility = View.GONE
        }
        h.preview.setImageDrawable(null)
        h.preview.setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        h.itemView.setOnClickListener { onOpen(b) }
        h.itemView.setOnLongClickListener { v -> onLongPress?.invoke(b, v); onLongPress != null }
        show(h)
    }

    override fun onViewAttachedToWindow(holder: VH) {
        attached.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        attached.remove(holder)
    }

    private fun show(h: VH) {
        val b = h.item ?: return
        if (b.frames <= 0) return
        val i = tick % b.frames
        val key = "${b.id}:$i"
        if (h.shownKey == key) return
        val cached = cache.get(key)
        if (cached != null) {
            h.preview.setImageBitmap(cached)
            h.shownKey = key
            return
        }
        if (!loading.add(key)) return
        val app = context.applicationContext
        io.execute {
            val bmp = Store.previewFrame(app, b.id, i)
            handler.post {
                loading.remove(key)
                if (bmp != null) {
                    cache.put(key, bmp)
                    if (h.item === b && (h.shownKey == null || tick % b.frames == i)) {
                        h.preview.setImageBitmap(bmp)
                        h.shownKey = key
                    }
                }
            }
        }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor()
        private val loading = HashSet<String>()
        private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        fun forget(id: String) {
            cache.snapshot().keys.filter { it.startsWith("$id:") }.forEach { cache.remove(it) }
        }
    }
}
