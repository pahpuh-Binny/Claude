package io.github.pahpuhbinny.multiview.ui

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.browser.Tab
import io.github.pahpuhbinny.multiview.browser.TabGroup
import io.github.pahpuhbinny.multiview.browser.TabManager

/**
 * The tab overview: switch, close, mute, group tabs, and pick the tabs to
 * watch side by side. Grouped tabs are shown under their group's header.
 */
class TabSwitcher(private val activity: MainActivity, private val tabs: TabManager) {

    private sealed class Row {
        class Header(val group: TabGroup?, val members: List<Tab>) : Row()
        class Card(val tab: Tab) : Row()
    }

    private val dialog = BottomSheetDialog(activity)
    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_tabs, FrameLayout(activity), false)
    private val adapter = Adapter()
    private val title: TextView = view.findViewById(R.id.sheetTitle)
    private val watch: MaterialButton = view.findViewById(R.id.watchTogether)
    private var rows: List<Row> = emptyList()
    private val columns: Int

    init {
        val list: RecyclerView = view.findViewById(R.id.list)
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        columns = if (landscape) 4 else 2
        val glm = GridLayoutManager(activity, columns)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (rows.getOrNull(position) is Row.Header) columns else 1
        }
        list.layoutManager = glm
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
        view.findViewById<TextView>(R.id.newGroup).setOnClickListener {
            GroupDialogs.create(activity, tabs, setOfNotNull(tabs.focused?.takeIf { it.groupId == 0 }))
        }
        view.findViewById<TextView>(R.id.closeAll).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setMessage("Close all ${tabs.tabs.size} tabs?")
                .setPositiveButton(R.string.close_all) { _, _ ->
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

    fun dismiss() = dialog.dismiss()

    private fun buildRows(): List<Row> {
        val out = ArrayList<Row>()
        val groups = tabs.orderedGroups()
        for (g in groups) {
            val members = tabs.tabsIn(g)
            out.add(Row.Header(g, members))
            if (!g.collapsed) members.forEach { out.add(Row.Card(it)) }
        }
        val loose = tabs.tabs.filter { it.groupId == 0 }
        if (groups.isNotEmpty() && loose.isNotEmpty()) out.add(Row.Header(null, loose))
        loose.forEach { out.add(Row.Card(it)) }
        return out
    }

    fun refresh() {
        rows = buildRows()
        val n = tabs.tabs.size
        val g = tabs.groups.size
        title.text = (if (n == 1) "1 tab" else "$n tabs") + if (g > 0) " · " + (if (g == 1) "1 group" else "$g groups") else ""
        val picked = tabs.multiviewIds.size
        watch.text = when {
            tabs.inMultiview -> "Single view"
            picked >= 2 -> "Watch $picked together"
            else -> "Multiview ($picked/4)"
        }
        adapter.notifyDataSetChanged()
    }

    private fun cardMenu(t: Tab, anchor: View) {
        val p = PopupMenu(activity, anchor)
        val g = tabs.groupOf(t)
        p.menu.add(0, 1, 0, if (g == null) "Add to group…" else "Move to another group…")
        if (g != null) p.menu.add(0, 2, 0, "Remove from “${g.name}”")
        p.menu.add(0, 3, 0, if (tabs.isInMultiviewSet(t)) "Remove from multiview" else "Add to multiview")
        p.menu.add(0, 4, 0, if (t.muted) R.string.unmute else R.string.mute)
        p.menu.add(0, 5, 0, R.string.close)
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> GroupDialogs.moveTab(activity, tabs, t)
                2 -> tabs.setGroup(t, null)
                3 -> if (!tabs.toggleMultiviewMember(t)) activity.toast("Multiview shows up to ${TabManager.MAX_TILES} tabs")
                4 -> tabs.toggleAudio(t)
                5 -> tabs.closeTab(t)
            }
            refresh()
            true
        }
        p.show()
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.dot)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val chevron: TextView = v.findViewById(R.id.chevron)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    private class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        val strip: View = v.findViewById(R.id.groupStrip)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val audio: ImageButton = v.findViewById(R.id.audio)
        val close: ImageButton = v.findViewById(R.id.close)
        val mv: TextView = v.findViewById(R.id.multiviewToggle)
        val playing: TextView = v.findViewById(R.id.playing)
        val titleView: TextView = v.findViewById(R.id.title)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_group_header, parent, false))
            else CardVH(inf.inflate(R.layout.item_tab, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> bindHeader(holder as HeaderVH, row)
                is Row.Card -> bindCard(holder as CardVH, row.tab)
            }
        }

        private fun bindHeader(h: HeaderVH, row: Row.Header) {
            val g = row.group
            val playing = row.members.count { it.isPlaying }
            h.meta.text = (if (row.members.size == 1) "1 tab" else "${row.members.size} tabs") +
                if (playing > 0) " · ▶ $playing" else ""
            if (g == null) {
                h.dot.visibility = View.INVISIBLE
                h.name.text = activity.getString(R.string.other_tabs)
                h.name.setTextColor(ContextCompat.getColor(activity, R.color.text_dim))
                h.chevron.text = ""
                h.more.visibility = View.GONE
                h.itemView.setOnClickListener(null)
                h.itemView.isClickable = false
                return
            }
            h.dot.visibility = View.VISIBLE
            h.dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(g.argb)
            }
            h.name.text = g.name
            h.name.setTextColor(g.argb)
            h.chevron.text = if (g.collapsed) "▸" else "▾"
            h.more.visibility = View.VISIBLE
            h.more.setOnClickListener { v -> GroupDialogs.groupMenu(activity, tabs, g, v) { refresh() } }
            h.itemView.setOnClickListener { tabs.toggleCollapsed(g) }
        }

        private fun bindCard(h: CardVH, t: Tab) {
            val ctx = h.itemView.context
            h.titleView.text = t.displayTitle()
            if (t.thumbnail != null) h.thumb.setImageBitmap(t.thumbnail) else h.thumb.setImageBitmap(t.favicon)
            h.strip.visibility = if (t.groupColor != 0) View.VISIBLE else View.GONE
            if (t.groupColor != 0) h.strip.setBackgroundColor(t.groupColor)
            h.itemView.foreground = if (t === tabs.focused) ContextCompat.getDrawable(ctx, R.drawable.bg_tile_focus) else null
            h.playing.visibility = if (t.isPlaying) View.VISIBLE else View.GONE
            h.playing.text = if (t.isPlaying) (if (t.muted) "▶ Playing (muted)" else "▶ Playing") else ""
            h.audio.visibility = if (t.hasMedia || t.muted) View.VISIBLE else View.GONE
            h.audio.setImageResource(if (t.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            h.audio.contentDescription = ctx.getString(if (t.muted) R.string.unmute else R.string.mute)
            h.audio.setOnClickListener {
                tabs.toggleAudio(t)
                refresh()
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
            h.itemView.setOnLongClickListener { v ->
                cardMenu(t, v)
                true
            }
        }
    }
}
