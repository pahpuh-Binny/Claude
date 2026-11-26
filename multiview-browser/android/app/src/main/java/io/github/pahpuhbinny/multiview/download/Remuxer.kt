package io.github.pahpuhbinny.multiview.download

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Builds a brand-new video file from a downloaded one. Only the encoded
 * picture and sound frames are copied into a fresh MP4 (or WebM) container;
 * everything else in the original file (extra data, metadata, attachments,
 * anything appended or hidden) is left behind. If the download isn't a
 * real video, the media parser rejects it and nothing is saved.
 */
object Remuxer {

    class Result(val mime: String, val durationUs: Long, val hasVideo: Boolean, val hasAudio: Boolean)

    private val mp4Video = buildSet {
        addAll(listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_MPEG4, MediaFormat.MIMETYPE_VIDEO_H263))
        if (Build.VERSION.SDK_INT >= 34) add(MediaFormat.MIMETYPE_VIDEO_AV1)
    }
    private val mp4Audio = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB)
    private val webmVideo = setOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9)
    private val webmAudio = setOf(MediaFormat.MIMETYPE_AUDIO_VORBIS, MediaFormat.MIMETYPE_AUDIO_OPUS)

    private class Pick(val extractor: MediaExtractor, val track: Int, val format: MediaFormat, val mime: String)

    fun remux(sources: List<MediaDataSource>, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean): Result {
        val extractors = sources.map { src ->
            MediaExtractor().apply {
                try {
                    setDataSource(src)
                } catch (e: Exception) {
                    throw Net.DownloadException("the download isn't a video format Android can read")
                }
            }
        }
        try {
            var video: Pick? = null
            var audio: Pick? = null
            for (ex in extractors) {
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (video == null && mime.startsWith("video/")) video = Pick(ex, i, f, mime)
                    if (audio == null && mime.startsWith("audio/")) audio = Pick(ex, i, f, mime)
                }
            }
            if (video == null && audio == null) throw Net.DownloadException("no video or sound found in the download")

            // Choose a container both tracks fit in; drop a track that fits nowhere.
            val v = video
            var a = audio
            val outFormat: Int
            val outMime: String
            when {
                v != null && v.mime in mp4Video -> {
                    outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4; outMime = "video/mp4"
                    if (a != null && a.mime !in mp4Audio) a = null
                }
                v != null && v.mime in webmVideo -> {
                    outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM; outMime = "video/webm"
                    if (a != null && a.mime !in webmAudio) a = null
                }
                v != null -> throw Net.DownloadException("this video uses a format (${v.mime}) that can't be saved")
                a != null && a.mime in mp4Audio -> { outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4; outMime = "audio/mp4" }
                a != null && a.mime in webmAudio -> { outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM; outMime = "audio/webm" }
                else -> throw Net.DownloadException("this sound format can't be saved")
            }
            val picks = listOfNotNull(v, a)
            val muxer = MediaMuxer(out.path, outFormat)
            var started = false
            try {
                val muxTrack = HashMap<Pick, Int>()
                for (p in picks) {
                    p.extractor.selectTrack(p.track)
                    muxTrack[p] = muxer.addTrack(p.format)
                }
                v?.format?.let { f ->
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION))
                }
                muxer.start()
                started = true

                val durationUs = picks.maxOf { p -> if (p.format.containsKey(MediaFormat.KEY_DURATION)) p.format.getLong(MediaFormat.KEY_DURATION) else 0L }
                val active = picks.map { it.extractor }.distinct().toMutableList()
                val base = active.minOf { ex -> ex.sampleTime.takeIf { it >= 0 } ?: Long.MAX_VALUE }.let { if (it == Long.MAX_VALUE) 0L else it }
                var maxInput = picks.maxOf { p ->
                    if (p.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) p.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                }.coerceIn(2 * 1024 * 1024, 64 * 1024 * 1024)
                var buffer = ByteBuffer.allocateDirect(maxInput)
                val info = MediaCodec.BufferInfo()
                val lastPts = HashMap<Int, Long>()
                val offset = HashMap<Int, Long>()
                val written = HashMap<Int, Int>()
                var samples = 0L

                while (active.isNotEmpty()) {
                    if (cancelled()) throw InterruptedException()
                    // Take the earliest sample across all inputs, to keep sound and picture interleaved.
                    val ex = active.minByOrNull { it.sampleTime.let { t -> if (t < 0) Long.MAX_VALUE else t } }!!
                    val trackIdx = ex.sampleTrackIndex
                    if (trackIdx < 0) {
                        active.remove(ex)
                        continue
                    }
                    val pick = picks.first { it.extractor === ex && it.track == trackIdx }
                    val mt = muxTrack[pick]!!
                    if (Build.VERSION.SDK_INT >= 28) {
                        val need = ex.sampleSize
                        if (need > buffer.capacity()) {
                            if (need > 256L * 1024 * 1024) throw Net.DownloadException("damaged video (frame too large)")
                            maxInput = need.toInt()
                            buffer = ByteBuffer.allocateDirect(maxInput)
                        }
                    }
                    buffer.clear()
                    val size = try {
                        ex.readSampleData(buffer, 0)
                    } catch (e: IllegalArgumentException) {
                        maxInput *= 2
                        if (maxInput > 256 * 1024 * 1024) throw Net.DownloadException("damaged video (frame too large)")
                        buffer = ByteBuffer.allocateDirect(maxInput)
                        continue
                    }
                    if (size < 0) {
                        active.remove(ex)
                        continue
                    }
                    val flags = ex.sampleFlags
                    if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                        throw Net.DownloadException("this video is DRM-protected and can't be saved")
                    }
                    var pts = ex.sampleTime - base + (offset[mt] ?: 0L)
                    val last = lastPts[mt]
                    if (last != null && pts + 1_000_000 < last) {
                        // Timestamps jumped backwards (stream splice): continue from where we were.
                        val add = last - pts + 33_000
                        offset[mt] = (offset[mt] ?: 0L) + add
                        pts += add
                    }
                    if (pts < 0) pts = 0
                    lastPts[mt] = maxOf(last ?: 0L, pts)
                    var outFlags = 0
                    if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) outFlags = outFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    info.set(0, size, pts, outFlags)
                    buffer.position(0)
                    buffer.limit(size)
                    muxer.writeSampleData(mt, buffer, info)
                    written[mt] = (written[mt] ?: 0) + 1
                    if (++samples % 200 == 0L && durationUs > 0) progress((pts.toFloat() / durationUs).coerceIn(0f, 1f))
                    ex.advance()
                }
                if (written.isEmpty()) throw Net.DownloadException("the download contained no frames")
                muxer.stop()
                started = false
                return Result(outMime, durationUs, v != null && (written[muxTrack[v]] ?: 0) > 0, a != null && (written[muxTrack[a]] ?: 0) > 0)
            } finally {
                try {
                    if (started) muxer.stop()
                } catch (e: Exception) {
                }
                muxer.release()
            }
        } finally {
            extractors.forEach { it.release() }
        }
    }
}
