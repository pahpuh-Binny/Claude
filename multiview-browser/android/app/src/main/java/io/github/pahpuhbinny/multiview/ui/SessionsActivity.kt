package io.github.pahpuhbinny.multiview.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.data.Session
import io.github.pahpuhbinny.multiview.data.Store
import java.text.DateFormat
import java.util.Date

/** Saved sets of tabs you can reopen later, multiview layout included. */
class SessionsActivity : AppCompatActivity() {

    private var sessions: List<Session> = emptyList()
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@SessionsActivity, R.color.bg))
        }
        val save = MaterialButton(this).apply {
            text = "Save open tabs as a session"
            setIconResource(R.drawable.ic_add)
            setOnClickListener { finishWith(ACTION_SAVE, null, false) }
        }
        col.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(10), dp(14), dp(6))
        })
        empty = TextView(this).apply {
            text = getString(R.string.no_sessions)
            setTextColor(ContextCompat.getColor(this@SessionsActivity, R.color.text_dim))
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        col.addView(empty)
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        load()
    }

    private fun load() {
        sessions = Store.sessions(this)
        empty.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        list.adapter?.notifyDataSetChanged()
    }

    private fun finishWith(action: String, id: String?, replace: Boolean) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ACTION, action).putExtra(EXTRA_ID, id).putExtra(EXTRA_REPLACE, replace))
        finish()
    }

    private inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val preview = ImageView(this@SessionsActivity)
        val name = TextView(this@SessionsActivity)
        val meta = TextView(this@SessionsActivity)
        val titles = TextView(this@SessionsActivity)
        val restore = MaterialButton(this@SessionsActivity)
        val add = MaterialButton(this@SessionsActivity, null, com.google.android.material.R.attr.borderlessButtonStyle)
        val more = MaterialButton(this@SessionsActivity, null, com.google.android.material.R.attr.borderlessButtonStyle)

        init {
            val ctx = this@SessionsActivity
            root.orientation = LinearLayout.VERTICAL
            root.background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            root.setPadding(dp(12), dp(12), dp(12), dp(6))
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            preview.scaleType = ImageView.ScaleType.CENTER_CROP
            preview.setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
            top.addView(preview, LinearLayout.LayoutParams(dp(112), dp(70)))
            val text = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            name.textSize = 16f
            name.setTextColor(ContextCompat.getColor(ctx, R.color.text))
            name.typeface = android.graphics.Typeface.DEFAULT_BOLD
            meta.textSize = 12f
            meta.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            titles.textSize = 12f
            titles.maxLines = 2
            titles.ellipsize = TextUtils.TruncateAt.END
            titles.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            text.addView(name)
            text.addView(meta)
            text.addView(titles)
            top.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(top)
            val buttons = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            restore.text = "Open"
            add.text = "Add to tabs"
            more.text = "⋯"
            buttons.addView(more)
            buttons.addView(add)
            buttons.addView(restore)
            root.addView(buttons)
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(6), dp(12), dp(6))
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LinearLayout(parent.context))
        override fun getItemCount() = sessions.size
        override fun onBindViewHolder(h: VH, position: Int) {
            val s = sessions[position]
            h.name.text = s.name
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.updatedAt))
            h.meta.text = "${s.tabs.size} tabs" + (if (s.multiview) " · multiview" else "") + " · $date"
            h.titles.text = s.tabs.joinToString(" · ") { it.title.ifBlank { it.url } }
            val f = Store.sessionPreview(this@SessionsActivity, s.id)
            h.preview.setImageBitmap(if (f.isFile) BitmapFactory.decodeFile(f.path) else null)
            h.restore.setOnClickListener { finishWith(ACTION_RESTORE, s.id, true) }
            h.add.setOnClickListener { finishWith(ACTION_RESTORE, s.id, false) }
            h.more.setOnClickListener { v ->
                val p = PopupMenu(this@SessionsActivity, v)
                p.menu.add(0, 1, 0, "Replace with open tabs")
                p.menu.add(0, 2, 0, R.string.rename)
                p.menu.add(0, 3, 0, R.string.delete)
                p.setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> finishWith(ACTION_UPDATE, s.id, false)
                        2 -> rename(s)
                        3 -> MaterialAlertDialogBuilder(this@SessionsActivity)
                            .setMessage("Delete “${s.name}”?")
                            .setPositiveButton(R.string.delete) { _, _ ->
                                Store.deleteSession(this@SessionsActivity, s.id)
                                load()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                    true
                }
                p.show()
            }
        }
    }

    private fun rename(s: Session) {
        val input = EditText(this).apply {
            setText(s.name)
            setSingleLine()
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(FrameLayout(this).apply {
                setPadding(dp(22), dp(8), dp(22), 0)
                addView(input)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                s.name = input.text.toString().trim().ifEmpty { s.name }
                Store.upsertSession(this, s)
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_ID = "id"
        const val EXTRA_REPLACE = "replace"
        const val ACTION_SAVE = "save"
        const val ACTION_UPDATE = "update"
        const val ACTION_RESTORE = "restore"
    }
}
