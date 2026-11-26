package io.github.pahpuhbinny.multiview.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import io.github.pahpuhbinny.multiview.download.ChannelMediaDataSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Encrypted storage for downloaded videos. Videos are encrypted with
 * AES-256-GCM in 1 MB segments (Tink streaming AEAD), so they can be played
 * and seeked without ever being written to disk unencrypted. The keys are
 * wrapped by a key in the phone's Android Keystore, which never leaves the
 * device's secure hardware. The list of videos and the thumbnails are
 * encrypted too.
 */
object Vault {

    data class Item(
        val id: String,
        var title: String,
        val sourceHost: String,
        val pageUrl: String,
        val createdAt: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val mime: String,
        val hasThumb: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("host", sourceHost).put("page", pageUrl)
            .put("created", createdAt).put("duration", durationMs).put("w", width).put("h", height)
            .put("size", sizeBytes).put("mime", mime).put("thumb", hasThumb)

        val extension get() = if (mime == "video/webm") "webm" else if (mime.startsWith("audio/")) "m4a" else "mp4"

        companion object {
            fun fromJson(o: JSONObject) = Item(
                o.getString("id"), o.optString("title"), o.optString("host"), o.optString("page"),
                o.optLong("created"), o.optLong("duration"), o.optInt("w"), o.optInt("h"),
                o.optLong("size"), o.optString("mime", "video/mp4"), o.optBoolean("thumb"),
            )
        }
    }

    private const val MASTER_KEY = "android-keystore://multiview_vault_master"
    private const val PREFS = "vault_keys"
    private val INDEX_AAD = "multiview-vault-index".toByteArray()

    @Volatile private var streaming: StreamingAead? = null
    @Volatile private var aead: Aead? = null
    private val items = ArrayList<Item>()
    private val listeners = LinkedHashSet<() -> Unit>()

    fun dir(context: Context) = File(context.filesDir, "vault").apply { mkdirs() }
    private fun videoFile(context: Context, id: String) = File(dir(context), "$id.mvv")
    private fun thumbFile(context: Context, id: String) = File(dir(context), "$id.thm")

    /** Sets up keys and reads the index. Slow the first time: call off the main thread. */
    @Synchronized
    fun init(context: Context) {
        if (streaming != null) return
        val app = context.applicationContext
        AeadConfig.register()
        StreamingAeadConfig.register()
        val streamHandle = AndroidKeysetManager.Builder()
            .withSharedPref(app, "stream_keyset", PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
            .withMasterKeyUri(MASTER_KEY)
            .build().keysetHandle
        val aeadHandle = AndroidKeysetManager.Builder()
            .withSharedPref(app, "aead_keyset", PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY)
            .build().keysetHandle
        aead = aeadHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        streaming = streamHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        loadIndex(app)
    }

    private fun stream() = streaming ?: error("vault not initialised")
    private fun aead() = aead ?: error("vault not initialised")

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    fun items(): List<Item> = synchronized(items) { items.sortedByDescending { it.createdAt } }

    fun item(id: String): Item? = synchronized(items) { items.firstOrNull { it.id == id } }

    private fun loadIndex(context: Context) {
        val f = AtomicFile(File(dir(context), "index.bin"))
        val list = try {
            val plain = aead().decrypt(f.readFully(), INDEX_AAD)
            val arr = JSONArray(String(plain, Charsets.UTF_8))
            (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
        } catch (e: java.io.FileNotFoundException) {
            emptyList()
        }
        synchronized(items) {
            items.clear()
            // Drop entries whose video file is gone.
            items.addAll(list.filter { videoFile(context, it.id).isFile })
        }
    }

    private fun saveIndex(context: Context) {
        val arr = JSONArray()
        synchronized(items) { items.forEach { arr.put(it.toJson()) } }
        val data = aead().encrypt(arr.toString().toByteArray(Charsets.UTF_8), INDEX_AAD)
        val f = AtomicFile(File(dir(context), "index.bin"))
        val out = f.startWrite()
        try {
            out.write(data)
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    /** Encrypts [plain] into the vault as a new item. [plain] is left for the caller to delete. */
    fun add(context: Context, item: Item, plain: File, thumb: Bitmap?, progress: (Float) -> Unit) {
        val dest = videoFile(context, item.id)
        val total = plain.length().coerceAtLeast(1)
        FileOutputStream(dest).channel.use { outCh ->
            stream().newEncryptingChannel(outCh, item.id.toByteArray()).use { enc ->
                FileInputStream(plain).channel.use { inCh ->
                    val buf = ByteBuffer.allocate(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inCh.read(buf)
                        if (n < 0) break
                        buf.flip()
                        while (buf.hasRemaining()) enc.write(buf)
                        buf.clear()
                        done += n
                        progress(done.toFloat() / total)
                    }
                }
            }
        }
        var hasThumb = false
        if (thumb != null) {
            val bos = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            thumbFile(context, item.id).writeBytes(aead().encrypt(bos.toByteArray(), ("thumb:" + item.id).toByteArray()))
            hasThumb = true
        }
        synchronized(items) { items.add(item.copy(hasThumb = hasThumb)) }
        saveIndex(context)
        changed()
    }

    /** A seekable, decrypting view of a video. The caller closes it. */
    fun openChannel(context: Context, id: String): SeekableByteChannel {
        val ch = FileInputStream(videoFile(context, id)).channel
        return ChannelMediaDataSource.prime(stream().newSeekableDecryptingChannel(ch, id.toByteArray()))
    }

    /** True once the vault has been unlocked and its keys loaded in this run of the app. */
    val isOpen: Boolean get() = streaming != null

    /** The video's thumbnail; [sample] 2 or 4 decodes it smaller to save memory. */
    fun thumbnail(context: Context, id: String, sample: Int = 1): Bitmap? = try {
        val f = thumbFile(context, id)
        if (!f.isFile) null else {
            val plain = aead().decrypt(f.readBytes(), ("thumb:$id").toByteArray())
            BitmapFactory.decodeByteArray(plain, 0, plain.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    fun rename(context: Context, id: String, title: String) {
        synchronized(items) { items.firstOrNull { it.id == id }?.title = title.take(200) }
        saveIndex(context)
        changed()
    }

    fun delete(context: Context, id: String) {
        synchronized(items) { items.removeAll { it.id == id } }
        videoFile(context, id).delete()
        thumbFile(context, id).delete()
        saveIndex(context)
        changed()
    }

    /** Writes a decrypted copy to [out] (for "Export"). */
    fun export(context: Context, id: String, out: OutputStream) {
        stream().newDecryptingStream(FileInputStream(videoFile(context, id)), id.toByteArray()).use { input ->
            input.copyTo(out, 256 * 1024)
        }
    }
}
