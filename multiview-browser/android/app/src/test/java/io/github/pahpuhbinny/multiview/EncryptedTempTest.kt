package io.github.pahpuhbinny.multiview

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import io.github.pahpuhbinny.multiview.download.ChannelMediaDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * The download pipeline writes the site's bytes only through a throw-away
 * key, then lets Android's media parser read them back at random offsets.
 */
class EncryptedTempTest {

    @Test
    fun randomAccessReadsMatchTheOriginal() {
        StreamingAeadConfig.register()
        val aead = KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM_HKDF_1MB"))
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val plain = Random(7).nextBytes(3 * 1024 * 1024 + 12345)
        val aad = "job-1".toByteArray()
        val f = File.createTempFile("mvtest", ".enc")
        try {
            aead.newEncryptingStream(FileOutputStream(f), aad).use { out ->
                var i = 0
                while (i < plain.size) {
                    val n = minOf(70_001, plain.size - i)
                    out.write(plain, i, n)
                    i += n
                }
            }
            // Nothing readable on disk.
            val onDisk = f.readBytes()
            assertFalse(onDisk.toList().windowed(32, 997).take(50).any { w -> plain.toList().windowed(32, 32).take(200).contains(w) })

            val src = ChannelMediaDataSource(aead.newSeekableDecryptingChannel(FileInputStream(f).channel, aad))
            assertEquals(plain.size.toLong(), src.size)
            val rnd = Random(3)
            repeat(300) {
                val pos = rnd.nextLong(plain.size.toLong())
                val len = rnd.nextInt(1, 200_000)
                val buf = ByteArray(len + 10)
                val n = src.readAt(pos, buf, 10, len)
                val expected = minOf(len.toLong(), plain.size - pos).toInt()
                assertEquals(expected, n)
                assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOfRange(10, 10 + n))
            }
            assertEquals(-1, src.readAt(plain.size.toLong(), ByteArray(4), 0, 4))
            src.close()
        } finally {
            f.delete()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun wrongKeyCannotRead() {
        StreamingAeadConfig.register()
        val t = KeyTemplates.get("AES128_GCM_HKDF_1MB")
        val a = KeysetHandle.generateNew(t).getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val b = KeysetHandle.generateNew(t).getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val f = File.createTempFile("mvtest", ".enc")
        try {
            a.newEncryptingStream(FileOutputStream(f), ByteArray(0)).use { it.write(ByteArray(5000) { 1 }) }
            val ch = b.newSeekableDecryptingChannel(FileInputStream(f).channel, ByteArray(0))
            ch.read(java.nio.ByteBuffer.allocate(100))
        } finally {
            f.delete()
        }
    }
}
