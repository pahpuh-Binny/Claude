package io.github.pahpuhbinny.multiview.vault

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.min

/** Lets the video player read straight from an encrypted vault file (uri: mvvault://<id>). */
@OptIn(UnstableApi::class)
class VaultDataSource(private val context: Context) : BaseDataSource(false) {

    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: throw IOException("bad vault uri")
        val ch = try {
            Vault.openChannel(context, id)
        } catch (e: Exception) {
            throw IOException("Can't open this video", e)
        }
        channel = ch
        val size = ch.size()
        if (dataSpec.position > size) throw IOException("position past end")
        ch.position(dataSpec.position)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - dataSpec.position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: throw IOException("not open")
        val want = min(length.toLong(), remaining).toInt()
        val bb = ByteBuffer.wrap(buffer, offset, want)
        var n = 0
        while (n == 0) {
            n = try {
                ch.read(bb)
            } catch (e: Exception) {
                throw IOException("Decryption failed: the file may be damaged", e)
            }
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            channel?.close()
        } finally {
            channel = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(context.applicationContext)
    }
}
