package io.github.pahpuhbinny.multiview.ui

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.browser.Tab
import io.github.pahpuhbinny.multiview.browser.TabManager

/** The tab overview: switch, close, mute, and pick the tabs to watch side by side. */
class TabSwitcher(private val activity: MainActivity, private val tabs: TabManager) {

    private val dialog = BottomSheetDialog(activity)
    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_tabs, null)
    private val adapter = Adapter()
    private val title: TextView = view.findViewById(R.id.sheetTitle)
    private val watch: MaterialButton = view.findViewById(R.id.watchTogether)

    init {
        val list: RecyclerView = view.findViewById(R.id.list)
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        list.layoutManager = GridLayoutManager(activity, if (landscape) 4 else 2)
        list.adapter = adapter
        view.findViewById<MaterialButton>(R.id.newTab).setOnClickListener {
            dialog.dismiss()
            activity.openNewTab()
        }
        watch.setOnClickListener {
            if (tabs.inMultiview) {
                tabs.exitMultiview()
                dialog.dismiss()
            } else if (tabs.enterMultiview()) {
                dialog.dismiss()
            } else {
                activity.toast("Pick at least 2 tabs with the Multiview button on each card")
            }
        }
        view.findViewById<TextView>(R.id.closeAll).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setMessage("Close all ${tabs.tabs.size} tabs?")
                .setPositiveButton("Close all") { _, _ ->
                    for (t in tabs.tabs.toList()) tabs.closeTab(t)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        dialog.setContentView(view)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.setOnDismissListener { activity.onSwitcherClosed() }
        refresh()
    }

    fun show() = dialog.show()

    fun refresh() {
        title.text = if (tabs.tabs.size == 1) "1 tab" else "${tabs.tabs.size} tabs"
        val n = tabs.multiviewIds.size
        watch.text = when {
            tabs.inMultiview -> "Single view"
            n >= 2 -> "Watch $n together"
            else -> "Multiview ($n/4)"
        }
        adapter.notifyDataSetChanged()
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val audio: ImageButton = v.findViewById(R.id.audio)
        val close: ImageButton = v.findViewById(R.id.close)
        val mv: TextView = v.findViewById(R.id.multiviewToggle)
        val playing: TextView = v.findViewById(R.id.playing)
        val titleView: TextView = v.findViewById(R.id.title)
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

        override fun getItemCount() = tabs.tabs.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val t: Tab = tabs.tabs[position]
            val ctx = h.itemView.context
            h.titleView.text = t.displayTitle()
            if (t.thumbnail != null) h.thumb.setImageBitmap(t.thumbnail) else {
                h.thumb.setImageBitmap(null)
                t.favicon?.let { h.thumb.setImageBitmap(it) }
            }
            h.itemView.background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            h.itemView.foreground = if (t === tabs.focused) ContextCompat.getDrawable(ctx, R.drawable.bg_tile_focus) else null
            h.playing.visibility = if (t.isPlaying) View.VISIBLE else View.GONE
            h.playing.text = if (t.isPlaying) (if (t.muted) "▶ Playing (muted)" else "▶ Playing") else ""
            h.audio.visibility = if (t.hasMedia || t.muted) View.VISIBLE else View.GONE
            h.audio.setImageResource(if (t.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            h.audio.contentDescription = ctx.getString(if (t.muted) R.string.unmute else R.string.mute)
            h.audio.setOnClickListener {
                tabs.toggleAudio(t)
                notifyDataSetChanged()
            }
            val inSet = tabs.isInMultiviewSet(t)
            h.mv.text = if (inSet) "✓ Multiview" else "+ Multiview"
            h.mv.setTextColor(ContextCompat.getColor(ctx, if (inSet) R.color.safe else R.color.text))
            h.mv.setOnClickListener {
                if (!tabs.toggleMultiviewMember(t)) activity.toast("Multiview shows up to ${TabManager.MAX_TILES} tabs")
                refresh()
            }
            h.close.setOnClickListener {
                tabs.closeTab(t)
                refresh()
            }
            h.itemView.setOnClickListener {
                tabs.focus(t)
                dialog.dismiss()
            }
        }
    }
}
