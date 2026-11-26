package io.github.pahpuhbinny.multiview.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import io.github.pahpuhbinny.multiview.vault.Vault
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Saves one video into the vault:
 *  1. download it into a temporary file encrypted with a throw-away key
 *     (the site's bytes are never stored as-is, not even briefly);
 *  2. rebuild a new, clean video file from only its picture and sound frames;
 *  3. check the new file is a real, playable video and grab a thumbnail;
 *  4. encrypt it into the vault and delete every temporary file.
 */
object Pipeline {

    private const val MAX_FILE = 8L * 1024 * 1024 * 1024

    fun run(context: Context, job: Downloads.Job) {
        val work = File(context.cacheDir, "dl-${job.id}").apply { mkdirs() }
        try {
            Vault.init(context)
            val aad = job.id.toByteArray()
            // Throw-away key that only exists in memory for this download.
            val temp = KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_1MB"))
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)

            job.state = Downloads.State.DOWNLOADING
            job.progress = -1f
            Downloads.changed()
            val sources = ArrayList<File>()
            if (job.candidate.kind == MediaCandidate.Kind.HLS) {
                val plan = Hls.plan(job.candidate.url, job)
                val total = plan.video.segments.size + (plan.audio?.segments?.size ?: 0)
                var done = 0
                val tick = {
                    done++
                    job.progress = done.toFloat() / total
                    Downloads.changed()
                }
                val vf = File(work, "src0.enc")
                encryptedOut(temp, vf, aad).use { Hls.download(plan.video, job, it, tick) }
                sources.add(vf)
                plan.audio?.let { audio ->
                    val af = File(work, "src1.enc")
                    encryptedOut(temp, af, aad).use { Hls.download(audio, job, it, tick) }
                    sources.add(af)
                }
            } else {
                val f = File(work, "src0.enc")
                encryptedOut(temp, f, aad).use { downloadFile(job, it) }
                sources.add(f)
            }
            checkCancelled(job)

            job.state = Downloads.State.REBUILDING
            job.progress = 0f
            Downloads.changed()
            val clean = File(work, "clean.bin")
            val inputs = sources.map { ChannelMediaDataSource(temp.newSeekableDecryptingChannel(FileInputStream(it).channel, aad)) }
            val result = try {
                Remuxer.remux(inputs, clean, { p -> job.progress = p; Downloads.changed() }, { job.cancelled })
            } finally {
                inputs.forEach { runCatching { it.close() } }
                sources.forEach { it.delete() }
            }
            checkCancelled(job)

            // The rebuilt file must open as a real video before it goes into the vault.
            val mmr = MediaMetadataRetriever()
            var durationMs: Long
            var width = 0
            var height = 0
            var thumb: Bitmap? = null
            try {
                mmr.setDataSource(clean.path)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: (result.durationUs / 1000)
                if (result.hasVideo) {
                    width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val frame = mmr.getFrameAtTime(durationMs * 1000 / 10, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    thumb = frame?.let { scale(it, 480) }
                }
            } catch (e: Exception) {
                throw Net.DownloadException("the rebuilt video didn't pass the playback check")
            } finally {
                mmr.release()
            }
            if (durationMs <= 0 && result.durationUs <= 0) throw Net.DownloadException("the rebuilt video is empty")

            job.state = Downloads.State.ENCRYPTING
            job.progress = 0f
            Downloads.changed()
            val pageHost = Uri.parse(job.candidate.pageUrl).host ?: Uri.parse(job.candidate.url).host ?: ""
            Vault.add(
                context,
                Vault.Item(
                    id = job.id,
                    title = job.title.ifBlank { "Video from $pageHost" }.take(200),
                    sourceHost = pageHost.removePrefix("www."),
                    pageUrl = job.candidate.pageUrl,
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    sizeBytes = clean.length(),
                    mime = result.mime,
                    hasThumb = false,
                ),
                clean, thumb,
            ) { p -> job.progress = p; Downloads.changed() }
            job.state = Downloads.State.DONE
            job.progress = 1f
        } catch (e: InterruptedException) {
            job.state = Downloads.State.CANCELLED
        } catch (e: Throwable) {
            if (job.cancelled) {
                job.state = Downloads.State.CANCELLED
            } else {
                job.state = Downloads.State.FAILED
                job.message = when (e) {
                    is Net.DownloadException -> e.message ?: "download failed"
                    is java.net.UnknownHostException -> "no internet connection"
                    is java.net.SocketTimeoutException -> "the server stopped responding"
                    is java.io.IOException -> e.message ?: "network error"
                    is OutOfMemoryError -> "not enough memory"
                    else -> e.javaClass.simpleName
                }
            }
        } finally {
            work.deleteRecursively()
            Downloads.changed()
        }
    }

    private fun checkCancelled(job: Downloads.Job) {
        if (job.cancelled) throw InterruptedException()
    }

    private fun encryptedOut(aead: StreamingAead, f: File, aad: ByteArray): OutputStream =
        aead.newEncryptingStream(FileOutputStream(f), aad)

    private fun downloadFile(job: Downloads.Job, out: OutputStream) {
        val c = Net.open(job.candidate.url, job)
        try {
            val type = (c.contentType ?: "").lowercase()
            if (type.startsWith("text/") || type.contains("html") || type.contains("javascript") ||
                type.contains("android.package-archive") || type.contains("msdownload") || type.contains("x-executable") ||
                type.contains("zip") || type.contains("pdf")
            ) throw Net.DownloadException("the link isn't a video ($type)")
            val total = c.contentLengthLong
            if (total > MAX_FILE) throw Net.DownloadException("video is larger than 8 GB")
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var first = true
                while (true) {
                    if (job.cancelled) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (first) {
                        Net.rejectReason(buf, n)?.let { throw Net.DownloadException(it) }
                        first = false
                    }
                    out.write(buf, 0, n)
                    done += n
                    job.bytes = done
                    if (done > MAX_FILE) throw Net.DownloadException("video is larger than 8 GB")
                    job.progress = if (total > 0) done.toFloat() / total else -1f
                    Downloads.changed()
                }
                if (done == 0L) throw Net.DownloadException("the server sent an empty file")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun scale(b: Bitmap, maxW: Int): Bitmap {
        if (b.width <= maxW) return b
        val h = (b.height * (maxW.toFloat() / b.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, maxW, h, true)
    }
}
