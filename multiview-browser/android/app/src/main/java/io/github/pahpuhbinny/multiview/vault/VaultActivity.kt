package io.github.pahpuhbinny.multiview.vault

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.R
import io.github.pahpuhbinny.multiview.download.Downloads
import io.github.pahpuhbinny.multiview.ui.dp
import io.github.pahpuhbinny.multiview.ui.formatBytes
import io.github.pahpuhbinny.multiview.ui.formatDuration
import io.github.pahpuhbinny.multiview.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Your saved videos (encrypted) and the downloads in progress. */
class VaultActivity : AppCompatActivity() {

    private sealed class Row {
        class Job(val job: Downloads.Job) : Row()
        class Item(val item: Vault.Item) : Row()
    }

    private var rows: List<Row> = emptyList()
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = Adapter()
    private val thumbs = HashMap<String, android.graphics.Bitmap?>()
    private var unlocked = false
    private var exportId: String? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("video/*")) { uri ->
        val id = exportId ?: return@registerForActivityResult
        exportId = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { Vault.export(this@VaultActivity, id, it) } ?: error("no output")
                }.isSuccess
            }
            toast(if (ok) "Exported a decrypted copy" else "Export failed")
        }
    }

    private val vaultListener: () -> Unit = { runOnUiThread { reload() } }
    private val jobsListener: () -> Unit = { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.vaultSecureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@VaultActivity, R.color.bg))
        }
        val note = TextView(this).apply {
            text = "🔒 Encrypted on this phone. Each video was rebuilt from its picture and sound only, so nothing else from the site was kept."
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@VaultActivity, R.color.text_dim))
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        col.addView(note)
        empty = TextView(this).apply {
            text = getString(R.string.vault_empty)
            setTextColor(ContextCompat.getColor(this@VaultActivity, R.color.text_dim))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        col.addView(empty)
        list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        list.visibility = View.INVISIBLE
        unlock()
    }

    private fun unlock() {
        val bm = BiometricManager.from(this)
        if (!Prefs.vaultLock || bm.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (Prefs.vaultLock) toast("Set a screen lock on your phone to lock the vault")
            onUnlocked()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                toast(errString)
                finish()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock downloads")
                .setSubtitle("Your saved videos are encrypted")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }

    private fun onUnlocked() {
        unlocked = true
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { Vault.init(applicationContext) }.exceptionOrNull() }
            if (error != null) {
                MaterialAlertDialogBuilder(this@VaultActivity)
                    .setTitle("Can't open the vault")
                    .setMessage("The encryption keys on this phone couldn't be used (${error.javaClass.simpleName}).")
                    .setPositiveButton(R.string.close) { _, _ -> finish() }
                    .show()
                return@launch
            }
            Vault.addListener(vaultListener)
            Downloads.addListener(jobsListener)
            list.visibility = View.VISIBLE
            reload()
        }
    }

    override fun onDestroy() {
        Vault.removeListener(vaultListener)
        Downloads.removeListener(jobsListener)
        super.onDestroy()
    }

    private fun reload() {
        if (!unlocked) return
        rows = Downloads.jobs.filter { it.state != Downloads.State.DONE }.reversed().map { Row.Job(it) } +
            Vault.items().map { Row.Item(it) }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun play(item: Vault.Item) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_ID, item.id))
    }

    private fun options(item: Vault.Item, anchor: View) {
        val p = PopupMenu(this, anchor)
        p.menu.add(0, 1, 0, "Play")
        p.menu.add(0, 2, 0, R.string.rename)
        p.menu.add(0, 3, 0, "Export decrypted copy…")
        p.menu.add(0, 4, 0, R.string.delete)
        p.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> play(item)
                2 -> rename(item)
                3 -> MaterialAlertDialogBuilder(this)
                    .setTitle("Export without encryption?")
                    .setMessage("The copy you export won't be encrypted, and other apps that can see where you save it will be able to open it. It's the clean rebuilt file, not the original download.")
                    .setPositiveButton("Export") { _, _ ->
                        exportId = item.id
                        exportLauncher.launch(item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "." + item.extension)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                4 -> MaterialAlertDialogBuilder(this)
                    .setMessage("Delete “${item.title}”? This can't be undone.")
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) { Vault.delete(applicationContext, item.id) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }
        p.show()
    }

    private fun rename(item: Vault.Item) {
        val input = EditText(this).apply {
            setText(item.title)
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
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) { Vault.rename(applicationContext, item.id, t) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val thumb = ImageView(this@VaultActivity)
        val title = TextView(this@VaultActivity)
        val details = TextView(this@VaultActivity)
        val bar = ProgressBar(this@VaultActivity, null, android.R.attr.progressBarStyleHorizontal)
        val action = TextView(this@VaultActivity)

        init {
            val ctx = this@VaultActivity
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.background = ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            root.setPadding(dp(10), dp(10), dp(10), dp(10))
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface))
            root.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(63)))
            val text = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            title.setTextColor(ContextCompat.getColor(ctx, R.color.text))
            title.textSize = 14f
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            details.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            details.textSize = 12f
            text.addView(title)
            text.addView(details)
            text.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
            bar.max = 1000
            root.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            action.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            action.textSize = 20f
            action.gravity = Gravity.CENTER
            root.addView(action, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(5), dp(12), dp(5))
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LinearLayout(parent.context))
        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            when (val row = rows[position]) {
                is Row.Job -> {
                    val j = row.job
                    h.thumb.setImageResource(R.drawable.ic_download)
                    h.thumb.scaleType = ImageView.ScaleType.CENTER
                    h.title.text = j.title.ifBlank { "Video" }
                    h.details.text = j.statusText() + if (j.bytes > 0 && !j.finished) " · " + formatBytes(j.bytes) else ""
                    h.details.setTextColor(ContextCompat.getColor(this@VaultActivity, if (j.state == Downloads.State.FAILED) R.color.danger else R.color.text_dim))
                    h.bar.visibility = if (j.finished) View.GONE else View.VISIBLE
                    h.bar.isIndeterminate = j.progress < 0
                    if (j.progress >= 0) h.bar.progress = (j.progress * 1000).toInt()
                    h.action.text = if (j.finished) "✕" else "■"
                    h.action.contentDescription = if (j.finished) "Dismiss" else "Cancel"
                    h.action.setOnClickListener {
                        if (j.finished) Downloads.jobs.remove(j) else Downloads.cancel(j)
                        reload()
                    }
                    h.root.setOnClickListener(null)
                    h.root.setOnLongClickListener(null)
                }
                is Row.Item -> {
                    val it = row.item
                    h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                    h.title.text = it.title
                    h.details.setTextColor(ContextCompat.getColor(this@VaultActivity, R.color.text_dim))
                    val parts = listOfNotNull(
                        formatDuration(it.durationMs / 1000.0).ifEmpty { null },
                        if (it.width > 0) "${it.width}×${it.height}" else null,
                        formatBytes(it.sizeBytes),
                        it.sourceHost.ifEmpty { null },
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.createdAt)),
                    )
                    h.details.text = parts.joinToString(" · ")
                    h.bar.visibility = View.GONE
                    h.action.text = "⋮"
                    h.action.contentDescription = "Options"
                    h.action.setOnClickListener { v -> options(it, v) }
                    h.root.setOnClickListener { _ -> play(it) }
                    h.root.setOnLongClickListener { v -> options(it, v); true }
                    if (thumbs.containsKey(it.id)) {
                        h.thumb.setImageBitmap(thumbs[it.id])
                    } else {
                        h.thumb.setImageBitmap(null)
                        val id = it.id
                        lifecycleScope.launch {
                            val bmp = withContext(Dispatchers.IO) { if (it.hasThumb) Vault.thumbnail(applicationContext, id) else null }
                            thumbs[id] = bmp
                            notifyItemChanged(rows.indexOfFirst { r -> r is Row.Item && r.item.id == id }.coerceAtLeast(0))
                        }
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
