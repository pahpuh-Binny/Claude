package io.github.pahpuhbinny.multiview.download

import android.net.Uri
import io.github.pahpuhbinny.multiview.ui.formatDuration
import java.util.Locale

/** A video the browser spotted on a page that can be saved or cast. */
data class MediaCandidate(
    val url: String,
    val kind: Kind,
    var title: String,
    val pageUrl: String,
    var duration: Double = 0.0,
    var width: Int = 0,
    var height: Int = 0,
    var poster: String = "",
    val foundAt: Long = System.currentTimeMillis(),
) {
    enum class Kind { FILE, HLS }

    val mimeType: String
        get() = when {
            kind == Kind.HLS -> "application/x-mpegURL"
            extension == "webm" -> "video/webm"
            else -> "video/mp4"
        }

    private val extension: String
        get() = (Uri.parse(url).lastPathSegment ?: "").substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun describe(): String {
        val parts = ArrayList<String>()
        parts.add(if (kind == Kind.HLS) "Stream (HLS)" else extension.uppercase(Locale.ROOT).ifEmpty { "Video" })
        if (width > 0 && height > 0) parts.add("${width}×$height")
        formatDuration(duration).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.add(Uri.parse(url).host ?: "")
        return parts.joinToString(" · ")
    }

    /** Rank: streams and long, large videos first. */
    fun score(): Double = (if (kind == Kind.HLS) 1e9 else 0.0) + duration * 1000 + width * height / 1000.0

    companion object {
        private val fileExt = setOf("mp4", "m4v", "webm", "mov", "3gp", "ogv")

        fun kindFor(uri: Uri): Kind? {
            val path = uri.path ?: return null
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                ext == "m3u8" -> Kind.HLS
                ext in fileExt -> Kind.FILE
                else -> null
            }
        }

        fun kindFor(url: String): Kind? = try { kindFor(Uri.parse(url)) } catch (e: Exception) { null }
    }
}
