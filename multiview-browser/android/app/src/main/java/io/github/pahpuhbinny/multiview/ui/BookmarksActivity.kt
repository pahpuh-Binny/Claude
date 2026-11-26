package io.github.pahpuhbinny.multiview.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.data.Bookmark
import io.github.pahpuhbinny.multiview.data.Store

/** Your bookmarks as a grid of moving video previews. */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var adapter: BookmarkAdapter
    private lateinit var empty: TextView
    private var all: List<Bookmark> = emptyList()
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@BookmarksActivity, R.color.bg))
        }
        val search = EditText(this).apply {
            hint = "Search bookmarks"
            setSingleLine()
            background = ContextCompat.getDrawable(this@BookmarksActivity, R.drawable.bg_url)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(ContextCompat.getColor(this@BookmarksActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@BookmarksActivity, R.color.text_dim))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString()?.trim()?.lowercase() ?: ""
                    apply()
                }
            })
        }
        col.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(12), dp(8), dp(12), dp(4))
        })
        empty = TextView(this).apply {
            text = getString(R.string.no_bookmarks)
            setTextColor(ContextCompat.getColor(this@BookmarksActivity, R.color.text_dim))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            textSize = 15f
        }
        col.addView(empty)
        val list = RecyclerView(this)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        list.layoutManager = GridLayoutManager(this, if (landscape) 4 else 2)
        list.clipToPadding = false
        list.setPadding(dp(6), 0, dp(6), dp(12))
        adapter = BookmarkAdapter(this, onOpen = { open(it, false) }, onLongPress = { bm, v -> options(bm, v) })
        list.adapter = adapter
        col.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        load()
    }

    private fun load() {
        all = Store.bookmarks(this)
        apply()
    }

    private fun apply() {
        val items = if (filter.isEmpty()) all else all.filter { it.title.lowercase().contains(filter) || it.url.lowercase().contains(filter) }
        adapter.items = items
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (all.isNotEmpty() && items.isEmpty()) empty.text = "No bookmarks match “$filter”." else empty.text = getString(R.string.no_bookmarks)
    }

    private fun open(b: Bookmark, multiview: Boolean) {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_URL, b.url).putExtra(EXTRA_TIME, b.videoTime).putExtra(EXTRA_MULTIVIEW, multiview)
        )
        finish()
    }

    private fun options(b: Bookmark, anchor: View) {
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, "Open")
        p.menu.add(0, 2, 0, "Open in multiview")
        p.menu.add(0, 3, 0, R.string.rename)
        p.menu.add(0, 4, 0, R.string.delete)
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(b, false)
                2 -> open(b, true)
                3 -> rename(b)
                4 -> {
                    Store.deleteBookmark(this, b.id)
                    BookmarkAdapter.forget(b.id)
                    load()
                }
            }
            true
        }
        p.show()
    }

    private fun rename(b: Bookmark) {
        val input = EditText(this).apply {
            setText(b.title)
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
                val list = Store.bookmarks(this)
                list.firstOrNull { it.id == b.id }?.title = input.text.toString().trim()
                Store.saveBookmarks(this, list)
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        adapter.start()
    }

    override fun onStop() {
        adapter.stop()
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TIME = "time"
        const val EXTRA_MULTIVIEW = "multiview"
    }
}
