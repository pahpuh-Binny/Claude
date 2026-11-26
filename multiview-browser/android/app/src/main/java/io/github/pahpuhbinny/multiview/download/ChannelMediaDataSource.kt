package io.github.pahpuhbinny.multiview.download

import android.media.MediaDataSource
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.min

/** Feeds Android's media parser from a decrypting channel, so the download is never on disk in the clear. */
class ChannelMediaDataSource(private val channel: SeekableByteChannel) : MediaDataSource() {
    private val total = prime(channel).size()

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        if (size <= 0) return 0
        channel.position(position)
        val bb = ByteBuffer.wrap(buffer, offset, min(size.toLong(), total - position).toInt())
        var read = 0
        while (bb.hasRemaining()) {
            val n = channel.read(bb)
            if (n < 0) break
            read += n
        }
        return if (read == 0) -1 else read
    }

    override fun getSize(): Long = total

    @Synchronized
    override fun close() = channel.close()

    companion object {
        /**
         * Tink's decrypting channel only knows the file's size once it has
         * read the header (that's how it picks the key), so read one byte first.
         */
        fun prime(ch: SeekableByteChannel): SeekableByteChannel {
            ch.position(0)
            ch.read(ByteBuffer.allocate(1))
            ch.position(0)
            return ch
        }
    }
}
