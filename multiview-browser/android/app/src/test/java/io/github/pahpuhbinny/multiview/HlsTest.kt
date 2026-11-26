package io.github.pahpuhbinny.multiview

import io.github.pahpuhbinny.multiview.download.Hls
import io.github.pahpuhbinny.multiview.download.Net
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-lo",NAME="English",DEFAULT=NO,URI="audio/en-lo.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-hi",NAME="English",DEFAULT=YES,URI="audio/en-hi.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-hi",NAME="French",DEFAULT=NO,URI="audio/fr-hi.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud-lo"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud-hi"
        https://cdn.example.com/1080p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud-hi"
        720p/index.m3u8
    """.trimIndent()

    @Test
    fun picksHighestQualityAndItsDefaultSoundTrack() {
        val m = Hls.parseMaster(master, "https://site.example/video/master.m3u8")
        assertEquals(3, m.variants.size)
        val (video, audio) = Hls.choose(m)
        assertEquals("https://cdn.example.com/1080p/index.m3u8", video)
        assertEquals("https://site.example/video/audio/en-hi.m3u8", audio)
    }

    @Test
    fun parsesMediaPlaylistWithKeysRangesAndInitSection() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:40
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1000@720
            media.mp4
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:500
            media.mp4
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/k1",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:6.0,
            seg3.m4s
            #EXT-X-KEY:METHOD=AES-128,URI="k2"
            #EXTINF:4.5,
            seg4.m4s
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.5,
            seg5.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = Hls.parseMedia(text, "https://cdn.example/v/index.m3u8")
        assertTrue(p.ended)
        assertEquals(5, p.segments.size)
        val s = p.segments
        assertEquals("https://cdn.example/v/media.mp4", s[0].uri)
        assertEquals(720L..1719L, s[0].range)
        assertEquals("byte range without offset continues from the previous one", 1720L..2219L, s[1].range)
        assertEquals("https://cdn.example/v/init.mp4", s[0].mapUri)
        assertEquals(0L..719L, s[0].mapRange)
        assertEquals(40L, s[0].sequence)
        assertEquals(44L, s[4].sequence)
        assertNull(s[1].key)
        assertEquals("https://keys.example/k1", s[2].key!!.uri)
        assertArrayEquals(ByteArray(16) { it.toByte() }, s[2].key!!.iv)
        assertEquals("https://cdn.example/v/k2", s[3].key!!.uri)
        assertNull(s[3].key!!.iv)
        assertNull(s[4].key)
    }

    @Test
    fun ivDefaultsToSequenceNumber() {
        val iv = Hls.ivFor(0x0102L)
        assertEquals(16, iv.size)
        assertEquals(0x01.toByte(), iv[14])
        assertEquals(0x02.toByte(), iv[15])
        assertTrue(iv.take(14).all { it == 0.toByte() })
    }

    @Test
    fun liveStreamsAreMarkedNotEnded() {
        val p = Hls.parseMedia("#EXTM3U\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n", "https://x.example/live.m3u8")
        assertFalse(p.ended)
    }

    @Test
    fun drmStreamsAreRefused() {
        val drm = listOf(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n",
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:x\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:6,\na.ts\n",
        )
        for (t in drm) {
            try {
                Hls.parseMedia(t, "https://x.example/a.m3u8")
                throw AssertionError("DRM playlist was accepted")
            } catch (e: Net.DownloadException) {
                assertTrue(e.message!!.contains("DRM"))
            }
        }
    }

    @Test(expected = Net.DownloadException::class)
    fun notAPlaylist() {
        Hls.parseMedia("<html>nope</html>", "https://x.example/a.m3u8")
    }
}
