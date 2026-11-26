package io.github.pahpuhbinny.multiview.download

import java.io.OutputStream
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS stream (.m3u8): picks the best quality, fetches the
 * segments (decrypting standard AES-128 streams), and writes them in order.
 * DRM-protected streams and live broadcasts are refused.
 */
object Hls {

    internal data class Variant(val uri: String, val bandwidth: Long, val audioGroup: String?)
    internal data class Rendition(val type: String, val groupId: String, val uri: String?, val isDefault: Boolean)
    data class Key(val method: String, val uri: String?, val iv: ByteArray?)
    data class Segment(val uri: String, val range: LongRange?, val key: Key?, val sequence: Long, val mapUri: String?, val mapRange: LongRange?)
    class Playlist(val segments: List<Segment>, val ended: Boolean)

    class Plan(val video: Playlist, val audio: Playlist?)

    private val attrRegex = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    private fun attrs(s: String): Map<String, String> =
        attrRegex.findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun resolve(base: String, ref: String) = URL(URL(base), ref).toString()

    internal class Master(val variants: List<Variant>, val renditions: List<Rendition>)

    fun plan(url: String, job: Downloads.Job): Plan {
        val text = Net.readText(url, job)
        if (!text.trimStart().startsWith("#EXTM3U")) throw Net.DownloadException("the stream link didn't return a playlist")
        if (!text.contains("#EXT-X-STREAM-INF")) return Plan(parseMedia(text, url), null)
        val (videoUri, audioUri) = choose(parseMaster(text, url))
        val video = parseMedia(Net.readText(videoUri, job), videoUri)
        val audio = audioUri?.let { parseMedia(Net.readText(it, job), it) }
        return Plan(video, audio)
    }

    internal fun parseMaster(text: String, url: String): Master {
        val variants = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val a = attrs(line.substringAfter(':'))
                    var j = i + 1
                    while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                    if (j < lines.size) {
                        variants.add(Variant(resolve(url, lines[j]), a["BANDWIDTH"]?.toLongOrNull() ?: 0L, a["AUDIO"]))
                    }
                    i = j
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line.substringAfter(':'))
                    renditions.add(
                        Rendition(a["TYPE"] ?: "", a["GROUP-ID"] ?: "", a["URI"]?.let { resolve(url, it) }, a["DEFAULT"] == "YES")
                    )
                }
                line.startsWith("#EXT-X-SESSION-KEY") && isDrm(attrs(line.substringAfter(':'))) ->
                    throw Net.DownloadException("this video is DRM-protected and can't be saved")
            }
            i++
        }
        return Master(variants, renditions)
    }

    /** The best-quality stream, and its separate sound track if it has one. */
    internal fun choose(m: Master): Pair<String, String?> {
        val best = m.variants.maxByOrNull { it.bandwidth } ?: throw Net.DownloadException("the playlist lists no streams")
        val audio = best.audioGroup?.let { g ->
            val group = m.renditions.filter { it.type == "AUDIO" && it.groupId == g && it.uri != null }
            (group.firstOrNull { it.isDefault } ?: group.firstOrNull())?.uri
        }
        return best.uri to audio
    }

    private fun isDrm(a: Map<String, String>): Boolean {
        val method = a["METHOD"] ?: "NONE"
        val format = a["KEYFORMAT"] ?: "identity"
        return method.startsWith("SAMPLE-AES") || (method != "NONE" && format != "identity")
    }

    private fun parseRange(v: String, lastEnd: Long): LongRange {
        val len = v.substringBefore('@').trim().toLong()
        val off = if (v.contains('@')) v.substringAfter('@').trim().toLong() else lastEnd
        return off until off + len
    }

    internal fun parseMedia(text: String, url: String): Playlist {
        if (!text.trimStart().startsWith("#EXTM3U")) throw Net.DownloadException("bad stream playlist")
        val out = ArrayList<Segment>()
        var seq = 0L
        var key: Key? = null
        var mapUri: String? = null
        var mapRange: LongRange? = null
        var pendingRange: String? = null
        var ended = false
        val lastEnd = HashMap<String, Long>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    if (isDrm(a)) throw Net.DownloadException("this video is DRM-protected and can't be saved")
                    key = when (a["METHOD"]) {
                        null, "NONE" -> null
                        "AES-128" -> Key("AES-128", a["URI"]?.let { resolve(url, it) }, a["IV"]?.let { hex(it) })
                        else -> throw Net.DownloadException("unsupported stream encryption")
                    }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line.substringAfter(':'))
                    mapUri = a["URI"]?.let { resolve(url, it) }
                    mapRange = a["BYTERANGE"]?.let { parseRange(it, 0) }
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = line.substringAfter(':')
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#") -> {}
                else -> {
                    val u = resolve(url, line)
                    val range = pendingRange?.let { r -> parseRange(r, lastEnd[u] ?: 0L).also { lastEnd[u] = it.last + 1 } }
                    pendingRange = null
                    out.add(Segment(u, range, key, seq, mapUri, mapRange))
                    seq++
                }
            }
        }
        if (out.isEmpty()) throw Net.DownloadException("the stream has no video segments")
        return Playlist(out, ended)
    }

    /** HLS rule: with no IV given, the IV is the segment's sequence number as a 16-byte big-endian value. */
    internal fun ivFor(sequence: Long): ByteArray = ByteArray(16).also { b ->
        var s = sequence
        for (i in 15 downTo 8) {
            b[i] = (s and 0xff).toByte()
            s = s shr 8
        }
    }

    private fun hex(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Writes every segment of [playlist] to [out] in order, fetching a few
     * at a time. [onSegment] is called after each one.
     */
    fun download(playlist: Playlist, job: Downloads.Job, out: OutputStream, onSegment: () -> Unit) {
        if (!playlist.ended) throw Net.DownloadException("live streams can't be saved, only finished videos")
        if (playlist.segments.size > 50_000) throw Net.DownloadException("stream is too long")
        val keys = HashMap<String, ByteArray>()
        val pool: ExecutorService = Executors.newFixedThreadPool(4)
        try {
            var writtenMap: String? = null
            val window = ArrayDeque<Future<ByteArray>>()
            var next = 0
            var total = 0L
            fun submit(seg: Segment) {
                window.addLast(pool.submit(Callable { fetchSegment(seg, job, keys) }))
            }
            val segs = playlist.segments
            while (next < segs.size && window.size < 6) submit(segs[next++])
            var index = 0
            while (window.isNotEmpty()) {
                if (job.cancelled) throw InterruptedException()
                val seg = segs[index]
                if (seg.mapUri != null) {
                    val mapId = seg.mapUri + (seg.mapRange?.toString() ?: "")
                    if (writtenMap == null) {
                        val range = seg.mapRange?.let { "bytes=${it.first}-${it.last}" }
                        out.write(Net.readBytes(seg.mapUri, job, range, 8 * 1024 * 1024))
                        writtenMap = mapId
                    } else if (writtenMap != mapId) {
                        throw Net.DownloadException("this stream changes format part-way through and can't be saved")
                    }
                }
                val data = try {
                    window.removeFirst().get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
                if (index == 0 && seg.mapUri == null) {
                    Net.rejectReason(data, data.size)?.let { throw Net.DownloadException(it) }
                }
                out.write(data)
                total += data.size
                if (total > 8L * 1024 * 1024 * 1024) throw Net.DownloadException("video is larger than 8 GB")
                job.bytes += data.size
                index++
                onSegment()
                if (next < segs.size) submit(segs[next++])
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchSegment(seg: Segment, job: Downloads.Job, keys: HashMap<String, ByteArray>): ByteArray {
        if (job.cancelled) throw InterruptedException()
        val range = seg.range?.let { "bytes=${it.first}-${it.last}" }
        var attempt = 0
        var bytes: ByteArray
        while (true) {
            try {
                bytes = Net.readBytes(seg.uri, job, range)
                break
            } catch (e: Exception) {
                if (++attempt >= 3 || job.cancelled) throw e
                Thread.sleep(800L * attempt)
            }
        }
        val key = seg.key ?: return bytes
        val keyUri = key.uri ?: throw Net.DownloadException("stream key missing")
        val iv = key.iv ?: ivFor(seg.sequence)
        val keyBytes = synchronized(keys) { keys[keyUri] } ?: Net.readBytes(keyUri, job, null, 1024).also {
            if (it.size != 16) throw Net.DownloadException("bad stream key")
            synchronized(keys) { keys[keyUri] = it }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(bytes)
    }
}
