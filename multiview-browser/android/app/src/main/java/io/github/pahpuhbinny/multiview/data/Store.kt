package io.github.pahpuhbinny.multiview.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A saved page with a short moving preview of its video. */
data class Bookmark(
    val id: String,
    var url: String,
    var title: String,
    val createdAt: Long,
    var frames: Int,
    /** Where you were in the video, in seconds (0 if none). */
    var videoTime: Double,
    var videoDuration: Double,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("title", title)
        .put("created", createdAt).put("frames", frames).put("t", videoTime).put("d", videoDuration)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            o.getString("id"), o.getString("url"), o.optString("title"), o.optLong("created"),
            o.optInt("frames"), o.optDouble("t", 0.0), o.optDouble("d", 0.0),
        )
    }
}

data class SessionTab(
    val url: String,
    val title: String,
    val muted: Boolean,
    val inMultiview: Boolean,
    val videoTime: Double,
    /** Id of its [SessionGroup] within the session (0 = none). */
    val group: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("title", title).put("muted", muted)
        .put("mv", inMultiview).put("t", videoTime).put("g", group)

    companion object {
        fun fromJson(o: JSONObject) = SessionTab(
            o.getString("url"), o.optString("title"), o.optBoolean("muted"), o.optBoolean("mv"), o.optDouble("t", 0.0),
            o.optInt("g", 0),
        )
    }
}

data class SessionGroup(val id: Int, val name: String, val color: Int, val collapsed: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color).put("collapsed", collapsed)

    companion object {
        fun fromJson(o: JSONObject) = SessionGroup(o.getInt("id"), o.optString("name"), o.optInt("color"), o.optBoolean("collapsed"))
    }
}

/** A saved set of tabs (and which ones were in multiview). */
data class Session(
    val id: String,
    var name: String,
    val createdAt: Long,
    var updatedAt: Long,
    var tabs: List<SessionTab>,
    var focusedIndex: Int,
    var multiview: Boolean,
    var groups: List<SessionGroup> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("created", createdAt)
        .put("updated", updatedAt).put("focused", focusedIndex).put("multiview", multiview)
        .put("tabs", JSONArray().also { a -> tabs.forEach { a.put(it.toJson()) } })
        .put("groups", JSONArray().also { a -> groups.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Session {
            val arr = o.optJSONArray("tabs") ?: JSONArray()
            val groups = o.optJSONArray("groups") ?: JSONArray()
            return Session(
                o.getString("id"), o.optString("name"), o.optLong("created"), o.optLong("updated"),
                (0 until arr.length()).map { SessionTab.fromJson(arr.getJSONObject(it)) },
                o.optInt("focused"), o.optBoolean("multiview"),
                (0 until groups.length()).mapNotNull { runCatching { SessionGroup.fromJson(groups.getJSONObject(it)) }.getOrNull() },
            )
        }
    }
}

/** Bookmarks, sessions and their preview images, kept in the app's private storage. */
object Store {

    private fun read(context: Context, name: String): JSONArray = try {
        JSONArray(String(AtomicFile(File(context.filesDir, name)).readFully(), Charsets.UTF_8))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun write(context: Context, name: String, arr: JSONArray) {
        val f = AtomicFile(File(context.filesDir, name))
        val out = f.startWrite()
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---- bookmarks

    fun bookmarks(context: Context): MutableList<Bookmark> {
        val arr = read(context, "bookmarks.json")
        return (0 until arr.length()).mapNotNull { runCatching { Bookmark.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.createdAt }.toMutableList()
    }

    fun saveBookmarks(context: Context, list: List<Bookmark>) =
        write(context, "bookmarks.json", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })

    fun findBookmark(context: Context, url: String) = bookmarks(context).firstOrNull { it.url == url }

    fun addBookmark(context: Context, b: Bookmark) {
        val list = bookmarks(context)
        list.removeAll { it.url == b.url }.also { if (it) Unit }
        list.add(0, b)
        saveBookmarks(context, list)
    }

    fun deleteBookmark(context: Context, id: String) {
        saveBookmarks(context, bookmarks(context).filter { it.id != id })
        previewDir(context, id).deleteRecursively()
    }

    fun previewDir(context: Context, id: String) = File(File(context.filesDir, "previews"), id)

    fun savePreviewFrames(context: Context, id: String, frames: List<Bitmap>) {
        val dir = previewDir(context, id)
        dir.deleteRecursively()
        dir.mkdirs()
        frames.forEachIndexed { i, b ->
            File(dir, "$i.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        }
    }

    fun previewFrame(context: Context, id: String, index: Int): Bitmap? {
        val f = File(previewDir(context, id), "$index.jpg")
        return if (f.isFile) BitmapFactory.decodeFile(f.path) else null
    }

    // ---- sessions

    fun sessions(context: Context): MutableList<Session> {
        val arr = read(context, "sessions.json")
        return (0 until arr.length()).mapNotNull { runCatching { Session.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.updatedAt }.toMutableList()
    }

    fun saveSessions(context: Context, list: List<Session>) =
        write(context, "sessions.json", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } })

    fun upsertSession(context: Context, s: Session) {
        val list = sessions(context)
        list.removeAll { it.id == s.id }
        list.add(0, s)
        saveSessions(context, list)
    }

    fun deleteSession(context: Context, id: String) {
        saveSessions(context, sessions(context).filter { it.id != id })
        sessionPreview(context, id).delete()
    }

    fun sessionPreview(context: Context, id: String) = File(File(context.filesDir, "session-previews").apply { mkdirs() }, "$id.jpg")

    fun saveSessionPreview(context: Context, id: String, bmp: Bitmap) {
        sessionPreview(context, id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
    }

    /** The tabs that were open last time, restored on start. */
    fun lastSession(context: Context): Session? = read(context, "last-session.json").let {
        if (it.length() > 0) runCatching { Session.fromJson(it.getJSONObject(0)) }.getOrNull() else null
    }

    fun saveLastSession(context: Context, s: Session) = write(context, "last-session.json", JSONArray().put(s.toJson()))

    fun clearLastSession(context: Context) = File(context.filesDir, "last-session.json").delete()
}
