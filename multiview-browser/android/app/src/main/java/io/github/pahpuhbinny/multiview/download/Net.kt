package io.github.pahpuhbinny.multiview.download

import android.webkit.CookieManager
import io.github.pahpuhbinny.multiview.security.ContentBlocker
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** HTTP for downloads: sends the page's cookies, refuses downgrades to http and blocked hosts. */
object Net {

    class DownloadException(message: String) : IOException(message)

    fun open(url: String, job: Downloads.Job, range: String? = null): HttpURLConnection {
        var current = URL(url)
        var redirects = 0
        while (true) {
            check(current, job)
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", job.userAgent)
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (job.referer.startsWith("http")) c.setRequestProperty("Referer", job.referer)
            try {
                CookieManager.getInstance().getCookie(current.toString())?.let { c.setRequestProperty("Cookie", it) }
            } catch (e: Exception) {
            }
            range?.let { c.setRequestProperty("Range", it) }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null || ++redirects > 8) throw DownloadException("too many redirects")
                val next = URL(current, loc)
                if (current.protocol == "https" && next.protocol != "https") throw DownloadException("the server tried to switch to an insecure connection")
                current = next
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw DownloadException("the server answered HTTP $code")
            }
            return c
        }
    }

    private fun check(url: URL, job: Downloads.Job) {
        when (url.protocol) {
            "https" -> {}
            "http" -> if (!job.allowHttp) throw DownloadException("insecure (http) video links aren't downloaded while HTTPS-only mode is on")
            else -> throw DownloadException("unsupported link")
        }
        if (ContentBlocker.isMalicious(url.host)) throw DownloadException("${url.host} is on the malware list")
    }

    fun readText(url: String, job: Downloads.Job, max: Int = 8 * 1024 * 1024): String {
        val c = open(url, job)
        try {
            return readLimited(c.inputStream, max).toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    fun readBytes(url: String, job: Downloads.Job, range: String? = null, max: Int = 64 * 1024 * 1024): ByteArray {
        val c = open(url, job, range)
        try {
            return readLimited(c.inputStream, max)
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray = input.use {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw DownloadException("response too large")
        }
        out.toByteArray()
    }

    /**
     * Rejects things that are obviously not video, judged by their first
     * bytes: apps, programs, archives, documents, scripts and web pages.
     */
    fun rejectReason(head: ByteArray, n: Int): String? {
        if (n < 4) return null
        fun startsWith(vararg b: Int) = b.indices.all { i -> i < n && (head[i].toInt() and 0xff) == b[i] }
        val text = String(head, 0, minOf(n, 64), Charsets.ISO_8859_1).trimStart().lowercase()
        return when {
            startsWith(0x50, 0x4B, 0x03, 0x04) -> "it's a ZIP/APK archive, not a video"
            startsWith(0x4D, 0x5A) -> "it's a Windows program, not a video"
            startsWith(0x7F, 0x45, 0x4C, 0x46) -> "it's a Linux/Android program, not a video"
            startsWith(0x64, 0x65, 0x78, 0x0A) -> "it's Android code, not a video"
            startsWith(0xCA, 0xFE, 0xBA, 0xBE) -> "it's Java code, not a video"
            startsWith(0x25, 0x50, 0x44, 0x46) -> "it's a PDF, not a video"
            startsWith(0x52, 0x61, 0x72, 0x21) -> "it's a RAR archive, not a video"
            startsWith(0x37, 0x7A, 0xBC, 0xAF) -> "it's a 7-Zip archive, not a video"
            startsWith(0x23, 0x21) -> "it's a script, not a video"
            text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<?xml") || text.startsWith("<head") ->
                "the link returned a web page, not a video"
            else -> null
        }
    }
}
