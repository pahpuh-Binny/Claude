package io.github.pahpuhbinny.multiview.security

import android.content.Context
import android.util.Log
import io.github.pahpuhbinny.multiview.Prefs
import io.github.pahpuhbinny.multiview.browser.UrlTools
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Domain-based blocking of ads, trackers and malicious hosts. Lists are
 * plain domain lists, hosts files or the "||domain^" rules of Adblock
 * Plus-style lists (EasyList, EasyPrivacy). Subdomains of a listed domain
 * are blocked too.
 */
object ContentBlocker {

    enum class Category { ADS, TRACKERS, MALWARE }

    private class Lists(
        val ads: Set<String>,
        val trackers: Set<String>,
        val malware: Set<String>,
        val thirdPartyOnly: Set<String>,
        val exceptions: Set<String>,
    )

    @Volatile private var lists = Lists(emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
    @Volatile var domainCount = 0
        private set
    @Volatile var cosmeticCss: String = ""
        private set

    fun loadAsync(context: Context) {
        val app = context.applicationContext
        thread(name = "filters-load", priority = Thread.MIN_PRIORITY + 1) { load(app) }
    }

    @Synchronized
    fun load(context: Context) {
        val ads = HashSet<String>(4096)
        val trackers = HashSet<String>(4096)
        val malware = HashSet<String>(1024)
        val thirdParty = HashSet<String>()
        val exceptions = HashSet<String>()
        fun target(c: Category) = when (c) {
            Category.ADS -> ads
            Category.TRACKERS -> trackers
            Category.MALWARE -> malware
        }
        try {
            context.assets.open("filters/ads.txt").use { parse(it.bufferedReader(), ads, thirdParty, exceptions) }
            context.assets.open("filters/trackers.txt").use { parse(it.bufferedReader(), trackers, thirdParty, exceptions) }
            context.assets.open("filters/malware.txt").use { parse(it.bufferedReader(), malware, thirdParty, exceptions) }
            cosmeticCss = context.assets.open("css/cosmetic.css").use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "built-in lists", e)
        }
        for (list in FilterLists.all) {
            if (!FilterLists.isEnabled(list)) continue
            val f = FilterLists.fileFor(context, list)
            if (!f.isFile) continue
            try {
                BufferedReader(InputStreamReader(f.inputStream(), Charsets.UTF_8), 1 shl 16).use {
                    parse(it, target(list.category), thirdParty, exceptions)
                }
            } catch (e: Exception) {
                Log.w(TAG, "list ${list.id}", e)
            }
        }
        // Never block what a list explicitly marks as required.
        lists = Lists(ads, trackers, malware, thirdParty, exceptions)
        domainCount = ads.size + trackers.size + malware.size
    }

    private val abpRule = Regex("^(@@)?\\|\\|([a-z0-9.\\-_]+)\\^(?:\\$(.*))?$")
    private val plainDomain = Regex("^[a-z0-9][a-z0-9.\\-_]*\\.[a-z0-9\\-]{2,}$")
    private val ignoredHosts = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost", "ip6-loopback")

    internal fun parse(reader: BufferedReader, into: MutableSet<String>, thirdParty: MutableSet<String>, exceptions: MutableSet<String>) {
        reader.forEachLine { raw ->
            val line = raw.trim().lowercase(Locale.ROOT)
            if (line.isEmpty() || line[0] == '!' || line[0] == '#' || line[0] == '[') return@forEachLine
            if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") || line.startsWith("::")) {
                val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val d = parts[1]
                    if (d !in ignoredHosts && plainDomain.matches(d) && d.length <= 253) into.add(d)
                }
                return@forEachLine
            }
            if (line.startsWith("||") || line.startsWith("@@||")) {
                val m = abpRule.matchEntire(line) ?: return@forEachLine
                val isException = m.groupValues[1].isNotEmpty()
                val domain = m.groupValues[2]
                val options = m.groupValues[3]
                if (!plainDomain.matches(domain)) return@forEachLine
                // Rules limited to certain sites, or that only apply to pop-ups, aren't domain-wide.
                if (options.contains("domain=") || options.contains("popup") || options.contains("~third-party") ||
                    options.contains("denyallow") || options.contains("redirect") || options.contains("removeparam") ||
                    options.contains("csp") || options.contains("badfilter")
                ) return@forEachLine
                if (isException) {
                    exceptions.add(domain)
                } else if (options.contains("third-party") || options.contains("3p")) {
                    thirdParty.add(domain)
                } else {
                    into.add(domain)
                }
                return@forEachLine
            }
            if (plainDomain.matches(line) && line.length <= 253) into.add(line)
        }
    }

    internal fun matches(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        var h = host
        while (true) {
            if (set.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
            if (h.indexOf('.') < 0) return false
        }
    }

    fun isAllowlisted(pageHost: String?): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        val host = pageHost.removePrefix("www.")
        val allow = Prefs.allowlist
        if (allow.isEmpty()) return false
        var h = host
        while (true) {
            if (h in allow) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    fun isMalicious(host: String): Boolean = Prefs.blockMalware && matches(lists.malware, host.lowercase(Locale.ROOT))

    /** What, if anything, a request to [host] made by a page on [pageHost] should be blocked as. */
    fun check(host: String, pageHost: String?, isMainFrame: Boolean): Category? {
        val l = lists
        val h = host.lowercase(Locale.ROOT)
        if (Prefs.blockMalware && matches(l.malware, h)) return Category.MALWARE
        if (isMainFrame) return null
        if (isAllowlisted(pageHost)) return null
        if (matches(l.exceptions, h)) return null
        val blockAds = Prefs.blockAds
        val blockTrackers = Prefs.blockTrackers
        if (!blockAds && !blockTrackers) return null
        if (blockAds && matches(l.ads, h)) return Category.ADS
        if (blockTrackers && matches(l.trackers, h)) return Category.TRACKERS
        if (matches(l.thirdPartyOnly, h)) {
            val thirdParty = pageHost == null || UrlTools.siteOf(h) != UrlTools.siteOf(pageHost)
            if (thirdParty) return if (blockAds) Category.ADS else Category.TRACKERS
        }
        return null
    }

    private const val TAG = "ContentBlocker"
}
