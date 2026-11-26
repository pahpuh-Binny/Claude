package io.github.pahpuhbinny.multiview.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.browser.Tab
import io.github.pahpuhbinny.multiview.download.MediaCandidate

/** Lists the videos found on the page, with Save and Cast buttons. */
class MediaSheet(
    private val activity: MainActivity,
    private val tab: Tab,
    private val onSave: (MediaCandidate) -> Unit,
    private val onCast: (MediaCandidate) -> Unit,
    private val onMirror: () -> Unit,
) {
    fun show() {
        val dialog = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(16), 0, activity.dp(16))
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = s
            textSize = size
            setTextColor(ContextCompat.getColor(activity, color))
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(activity.dp(18), 0, activity.dp(18), activity.dp(6))
        }
        col.addView(text(activity.getString(R.string.media_on_page), 18f, R.color.text, true))
        col.addView(
            text(
                "Saved videos are rebuilt into a new, clean file and encrypted. Nothing from the site is kept as-is.",
                12f, R.color.text_dim
            )
        )
        val items = tab.candidates.values.sortedByDescending { it.score() }
        if (items.isEmpty()) {
            col.addView(
                text(
                    if (tab.hasMedia) "This video is streamed in a protected or split-up format (YouTube and most big " +
                        "streaming sites do this), so there's no file to save or send to a TV. You can still mirror your screen."
                    else "No videos found on this page yet. Start playing one and try again.",
                    14f, R.color.text
                ).apply { setPadding(activity.dp(18), activity.dp(12), activity.dp(18), activity.dp(12)) }
            )
        }
        for (c in items) {
            val v = LayoutInflater.from(activity).inflate(R.layout.item_media, col, false)
            v.findViewById<TextView>(R.id.title).text = c.title.ifBlank { "Video" }
            v.findViewById<TextView>(R.id.details).text = c.describe()
            v.findViewById<MaterialButton>(R.id.save).setOnClickListener {
                dialog.dismiss()
                onSave(c)
            }
            v.findViewById<MaterialButton>(R.id.cast).setOnClickListener {
                dialog.dismiss()
                onCast(c)
            }
            col.addView(v)
        }
        val mirror = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Mirror screen to a TV"
            setIconResource(R.drawable.ic_cast_screen)
            setOnClickListener {
                dialog.dismiss()
                onMirror()
            }
        }
        col.addView(mirror, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = activity.dp(10)
        })
        val scroll = NestedScrollView(activity)
        scroll.addView(col)
        dialog.setContentView(scroll)
        dialog.show()
    }
}
