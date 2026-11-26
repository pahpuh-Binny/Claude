package io.github.pahpuhbinny.multiview.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.browser.GroupColors
import io.github.pahpuhbinny.multiview.browser.Tab
import io.github.pahpuhbinny.multiview.browser.TabGroup
import io.github.pahpuhbinny.multiview.browser.TabManager

/** Creating, editing and choosing tab groups. */
object GroupDialogs {

    /**
     * Name + colour editor. When [choices] is given, also shows a checklist
     * of tabs to put in the group ([preselected] start ticked).
     */
    fun editor(
        activity: MainActivity,
        title: String,
        name: String,
        color: Int,
        choices: List<Tab>? = null,
        preselected: Set<Tab> = emptySet(),
        onDone: (name: String, color: Int, picked: List<Tab>) -> Unit,
    ) {
        val ctx = activity
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), 0)
        }
        val input = EditText(ctx).apply {
            setText(name)
            hint = "Group name"
            setSingleLine()
            setSelection(text.length)
        }
        col.addView(input)

        var chosen = color
        val swatches = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(12), 0, ctx.dp(8))
        }
        val dots = ArrayList<View>()
        fun paint() {
            dots.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(GroupColors.argb(i))
                    if (i == chosen) setStroke(ctx.dp(3), ContextCompat.getColor(ctx, R.color.text))
                }
            }
        }
        for (i in 0 until GroupColors.count) {
            val v = View(ctx)
            v.contentDescription = GroupColors.names[i]
            v.setOnClickListener {
                chosen = i
                paint()
            }
            dots.add(v)
            swatches.addView(v, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).apply { marginEnd = ctx.dp(10) })
        }
        paint()
        col.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatches)
        })

        val boxes = ArrayList<Pair<CheckBox, Tab>>()
        if (choices != null && choices.isNotEmpty()) {
            col.addView(TextView(ctx).apply {
                text = "Tabs in this group"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
                textSize = 13f
                setPadding(0, ctx.dp(8), 0, ctx.dp(4))
            })
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            for (t in choices) {
                val cb = CheckBox(ctx).apply {
                    text = (if (t.isPlaying) "▶ " else "") + t.displayTitle()
                    isChecked = t in preselected
                    maxLines = 1
                    setTextColor(ContextCompat.getColor(ctx, R.color.text))
                }
                boxes.add(cb to t)
                list.addView(cb)
            }
            col.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(minOf(56 * choices.size, 260))))
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(col)
            .setPositiveButton(R.string.save) { _, _ ->
                onDone(input.text.toString().trim(), chosen, boxes.filter { it.first.isChecked }.map { it.second })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** "New group" with a checklist of tabs. */
    fun create(activity: MainActivity, tabs: TabManager, preselected: Set<Tab>) {
        editor(
            activity, activity.getString(R.string.new_group), "Group ${tabs.groups.size + 1}", tabs.nextFreeColor(),
            choices = tabs.tabs.toList(), preselected = preselected,
        ) { name, color, picked ->
            if (picked.isEmpty()) {
                activity.toast("Tick at least one tab for the group")
            } else {
                tabs.createGroup(name, color, picked)
                activity.toast("Grouped ${picked.size} tab" + if (picked.size == 1) "" else "s")
            }
        }
    }

    /** Move one tab into a group (or out of one). */
    fun moveTab(activity: MainActivity, tabs: TabManager, tab: Tab) {
        val groups = tabs.orderedGroups()
        val current = tabs.groupOf(tab)
        val labels = ArrayList<String>()
        groups.forEach { g -> labels.add((if (g === current) "✓ " else "") + g.name) }
        labels.add("+ New group…")
        if (current != null) labels.add("Remove from group")
        MaterialAlertDialogBuilder(activity)
            .setTitle("Add “${tab.displayTitle().take(40)}” to")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < groups.size -> tabs.setGroup(tab, groups[which])
                    which == groups.size -> create(activity, tabs, setOf(tab))
                    else -> tabs.setGroup(tab, null)
                }
            }
            .show()
    }

    /** Everything you can do with a whole group. */
    fun groupMenu(activity: MainActivity, tabs: TabManager, g: TabGroup, anchor: View, after: () -> Unit = {}) {
        val members = tabs.tabsIn(g)
        val anyPlaying = members.any { it.isPlaying }
        val anyHeld = members.any { it.held }
        val allMuted = members.isNotEmpty() && members.all { it.muted }
        val p = PopupMenu(activity, anchor)
        val m = p.menu
        if (members.size >= 2) m.add(0, 1, 0, "Watch together" + if (members.size > TabManager.MAX_TILES) " (first ${TabManager.MAX_TILES})" else "")
        m.add(0, 2, 0, "New tab in group")
        if (anyPlaying) m.add(0, 3, 0, "Pause group")
        if (anyHeld) m.add(0, 4, 0, "Play group")
        m.add(0, 5, 0, if (allMuted) "Unmute group" else "Mute group")
        m.add(0, 6, 0, "Rename or recolour")
        m.add(0, 7, 0, if (g.collapsed) "Expand" else "Collapse")
        m.add(0, 8, 0, "Save group as session")
        m.add(0, 9, 0, "Ungroup")
        m.add(0, 10, 0, "Close group")
        p.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> activity.onGroupWatched(tabs.watchGroup(g))
                2 -> {
                    activity.closeSwitcher()
                    tabs.newTabInGroup(g)
                    activity.focusAddressBar()
                }
                3 -> tabs.pauseGroup(g)
                4 -> tabs.playGroup(g)
                5 -> tabs.muteGroup(g, !allMuted)
                6 -> editor(activity, "Edit group", g.name, g.color) { name, color, _ ->
                    tabs.renameGroup(g, name)
                    tabs.recolorGroup(g, color)
                    after()
                }
                7 -> tabs.toggleCollapsed(g)
                8 -> activity.saveGroupAsSession(g)
                9 -> tabs.ungroup(g)
                10 -> MaterialAlertDialogBuilder(activity)
                    .setMessage("Close the ${members.size} tabs in “${g.name}”?")
                    .setPositiveButton("Close group") { _, _ ->
                        tabs.closeGroup(g)
                        after()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            after()
            true
        }
        p.show()
    }
}
