package io.github.pahpuhbinny.multiview.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.security.ContentBlocker.Category
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Blocklists the app can download and keep up to date. */
object FilterLists {

    data class FilterList(
        val id: String,
        val name: String,
        val url: String,
        val category: Category,
        val defaultOn: Boolean,
    )

    val all = listOf(
        FilterList("stevenblack", "StevenBlack unified hosts (ads and malware)",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", Category.ADS, true),
        FilterList("easylist", "EasyList (ads)",
            "https://easylist.to/easylist/easylist.txt", Category.ADS, true),
        FilterList("easyprivacy", "EasyPrivacy (trackers)",
            "https://easylist.to/easylist/easyprivacy.txt", Category.TRACKERS, true),
        FilterList("peterlowe", "Peter Lowe's ad and tracking servers",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext", Category.ADS, false),
        FilterList("urlhaus", "URLhaus malware sites (abuse.ch)",
            "https://urlhaus.abuse.ch/downloads/hostfile/", Category.MALWARE, true),
        FilterList("phishing", "Phishing sites (malware-filter)",
            "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt", Category.MALWARE, true),
    )

    fun prefKey(list: FilterList) = "list_${list.id}"

    fun isEnabled(list: FilterList) = Prefs.prefs.getBoolean(prefKey(list), list.defaultOn)

    fun fileFor(context: Context, list: FilterList) = File(File(context.filesDir, "filters"), "${list.id}.txt")

    private val running = AtomicBoolean(false)
    private const val WEEK = 7L * 24 * 3600 * 1000
    private const val MAX_BYTES = 40L * 1024 * 1024

    fun updateIfStale(context: Context) {
        if (System.currentTimeMillis() - Prefs.filtersUpdatedAt > WEEK) update(context, null)
    }

    /** Downloads every enabled list in the background, then reloads the blocker. */
    fun update(context: Context, done: ((ok: Int, failed: Int) -> Unit)?) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            done?.let { Handler(Looper.getMainLooper()).post { it(0, 0) } }
            return
        }
        thread(name = "filters-update", priority = Thread.MIN_PRIORITY + 1) {
            var ok = 0
            var failed = 0
            try {
                File(app.filesDir, "filters").mkdirs()
                for (list in all) {
                    val f = fileFor(app, list)
                    if (!isEnabled(list)) {
                        f.delete()
                        continue
                    }
                    try {
                        download(list.url, f)
                        ok++
                    } catch (e: Exception) {
                        Log.w("FilterLists", "${list.id}: ${e.message}")
                        failed++
                    }
                }
                ContentBlocker.load(app)
                if (ok > 0) Prefs.filtersUpdatedAt = System.currentTimeMillis()
            } finally {
                running.set(false)
                done?.let { cb -> Handler(Looper.getMainLooper()).post { cb(ok, failed) } }
            }
        }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var current = URL(url)
        var redirects = 0
        while (true) {
            if (current.protocol != "https") throw IOException("refusing non-HTTPS list URL")
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) Multiview-filter-update")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without location")
                    if (++redirects > 5) throw IOException("too many redirects")
                    current = URL(current, loc)
                    continue
                }
                if (code != 200) throw IOException("HTTP $code")
                var total = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IOException("list too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
                // A login page or error page instead of a list: keep the old copy.
                val head = tmp.inputStream().use { s -> ByteArray(256).let { b -> val n = s.read(b); if (n > 0) String(b, 0, n) else "" } }
                if (head.trimStart().startsWith("<")) throw IOException("got a web page, not a list")
                if (!tmp.renameTo(dest)) throw IOException("could not save list")
                return
            } finally {
                conn.disconnect()
                tmp.delete()
            }
        }
    }
}
