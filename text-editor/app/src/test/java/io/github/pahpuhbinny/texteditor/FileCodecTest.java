package io.github.pahpuhbinny.texteditor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Random;
import org.junit.Test;

public class FileCodecTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    private static byte[] reencode(FileCodec.Decoded d) throws ParseException {
        return FileCodec.encodeText(d.text, d.charset, d.bom, d.crlf);
    }

    @Test
    public void utf8TextRoundTrips() throws Exception {
        byte[] data = "{\"name\": \"café ☃\"}\n".getBytes(StandardCharsets.UTF_8);
        FileCodec.Decoded d = FileCodec.decode(data);
        assertTrue(d.isText);
        assertFalse(d.crlf);
        assertArrayEquals(data, reencode(d));
    }

    @Test
    public void crlfIsShownAsLfAndRestored() throws Exception {
        byte[] data = "<html>\r\n<body>\r\n</html>\r\n".getBytes(StandardCharsets.UTF_8);
        FileCodec.Decoded d = FileCodec.decode(data);
        assertTrue(d.crlf);
        assertEquals("<html>\n<body>\n</html>\n", d.text);
        assertArrayEquals(data, reencode(d));
    }

    @Test
    public void mixedLineEndingsAreKeptAsWritten() throws Exception {
        byte[] data = "a\r\nb\nc\rd".getBytes(StandardCharsets.UTF_8);
        FileCodec.Decoded d = FileCodec.decode(data);
        assertFalse(d.crlf);
        assertArrayEquals(data, reencode(d));
    }

    @Test
    public void bomsArePreserved() throws Exception {
        byte[] utf8 = bytes(0xEF, 0xBB, 0xBF, 'h', 'i');
        FileCodec.Decoded d = FileCodec.decode(utf8);
        assertTrue(d.isText);
        assertEquals("hi", d.text);
        assertArrayEquals(utf8, reencode(d));

        byte[] utf16 = bytes(0xFF, 0xFE, 'h', 0, 'i', 0);
        d = FileCodec.decode(utf16);
        assertTrue(d.isText);
        assertEquals("hi", d.text);
        assertEquals(StandardCharsets.UTF_16LE, d.charset);
        assertArrayEquals(utf16, reencode(d));
    }

    @Test
    public void binaryIsDetected() {
        assertFalse(FileCodec.decode(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A)).isText);
        assertFalse(FileCodec.decode(bytes('a', 0, 'b')).isText);
        assertTrue(FileCodec.decode(new byte[0]).isText);
    }

    @Test
    public void latin1ViewOfBinaryRoundTrips() throws Exception {
        byte[] data = new byte[256 * 3];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        new Random(1).nextBytes(data);
        FileCodec.Decoded d = FileCodec.decodeLatin1(data);
        assertArrayEquals(data, reencode(d));
    }

    @Test
    public void hexRoundTripsRandomData() throws Exception {
        Random random = new Random(42);
        for (int len = 0; len < 40; len++) {
            byte[] data = new byte[len];
            random.nextBytes(data);
            assertArrayEquals(data, FileCodec.fromHex(FileCodec.toHex(data)));
        }
    }

    @Test
    public void hexLineFormat() {
        assertEquals("00000000: 48 69 7C 00 FF          | Hi|..\n",
                FileCodec.toHex(bytes('H', 'i', '|', 0, 0xFF)));
    }

    @Test
    public void hexAcceptsLooseInput() throws Exception {
        assertArrayEquals(bytes(0xDE, 0xAD, 0xBE, 0xEF, 0x01),
                FileCodec.fromHex("dead beef\n\n00000004: 01 | ignored 99\n"));
    }

    @Test
    public void hexReportsTheBadLine() {
        try {
            FileCodec.fromHex("00\n0G\n");
            fail();
        } catch (ParseException e) {
            assertEquals(2, e.getErrorOffset());
        }
        try {
            FileCodec.fromHex("00 1\n");
            fail();
        } catch (ParseException e) {
            assertEquals(1, e.getErrorOffset());
        }
    }

    @Test
    public void truncateDoesNotSplitACharacter() {
        byte[] data = "ab☃cd".getBytes(StandardCharsets.UTF_8); // snowman is 3 bytes
        assertEquals("ab", new String(FileCodec.truncate(data, 3), StandardCharsets.UTF_8));
        assertEquals("ab", new String(FileCodec.truncate(data, 4), StandardCharsets.UTF_8));
        assertEquals("ab☃", new String(FileCodec.truncate(data, 5), StandardCharsets.UTF_8));
        assertTrue(FileCodec.decode(FileCodec.truncate(data, 4)).isText);
        assertEquals(4, FileCodec.truncate(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80), 4).length);
    }

    @Test
    public void jsonIsReindentedWithoutChangingValues() throws Exception {
        String in = "{\"b\":1.0,\"a\":[1,2,{\"x\":\"a,b:{}\\\"\"}],\"e\":{},\"big\":12345678901234567890 , \"n\":null}";
        String expected = "{\n"
                + "  \"b\": 1.0,\n"
                + "  \"a\": [\n"
                + "    1,\n"
                + "    2,\n"
                + "    {\n"
                + "      \"x\": \"a,b:{}\\\"\"\n"
                + "    }\n"
                + "  ],\n"
                + "  \"e\": {},\n"
                + "  \"big\": 12345678901234567890,\n"
                + "  \"n\": null\n"
                + "}\n";
        assertEquals(expected, FileCodec.formatJson(in));
        assertEquals(expected, FileCodec.formatJson(expected));
    }

    @Test
    public void jsonErrorsAreReported() {
        for (String bad : new String[] {"{\"a\":[1,2}", "{\"a\":1", "]", "{\"a"}) {
            try {
                FileCodec.formatJson(bad);
                fail(bad);
            } catch (ParseException expected) {
                // expected
            }
        }
    }
}
