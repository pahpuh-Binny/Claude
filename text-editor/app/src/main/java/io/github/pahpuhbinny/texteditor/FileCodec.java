package io.github.pahpuhbinny.texteditor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Turns file bytes into editable text and back, with no Android dependencies.
 *
 * Every conversion round-trips exactly: decoding a file and encoding the
 * unedited result gives back the same bytes.
 */
final class FileCodec {
    /** Bytes shown on each line of the hex view. */
    static final int BYTES_PER_LINE = 8;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private FileCodec() {}

    /** A file's content as text, plus what is needed to write it back unchanged. */
    static final class Decoded {
        /** False when the bytes are not valid text and should be shown as hex. */
        boolean isText;
        String text;
        Charset charset = StandardCharsets.UTF_8;
        /** The file starts with a byte order mark. */
        boolean bom;
        /** Line breaks were CRLF and are shown as LF. */
        boolean crlf;
    }

    /** Decodes bytes as UTF-8 or UTF-16 text, or reports them as binary. */
    static Decoded decode(byte[] data) {
        Decoded d = new Decoded();
        int skip = 0;
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            d.bom = true;
            skip = 3;
        } else if (startsWith(data, 0xFF, 0xFE)) {
            d.charset = StandardCharsets.UTF_16LE;
            d.bom = true;
            skip = 2;
        } else if (startsWith(data, 0xFE, 0xFF)) {
            d.charset = StandardCharsets.UTF_16BE;
            d.bom = true;
            skip = 2;
        }
        String text = strictDecode(data, skip, d.charset);
        // A NUL character means binary data, even where the bytes decode.
        if (text == null || text.indexOf('\0') >= 0) {
            d.isText = false;
            return d;
        }
        d.isText = true;
        setText(d, text);
        return d;
    }

    /**
     * Decodes any bytes as text, one character per byte (ISO-8859-1), so that
     * binary data can be viewed as text and still be saved unchanged.
     */
    static Decoded decodeLatin1(byte[] data) {
        Decoded d = new Decoded();
        d.isText = true;
        d.charset = StandardCharsets.ISO_8859_1;
        setText(d, new String(data, StandardCharsets.ISO_8859_1));
        return d;
    }

    private static void setText(Decoded d, String text) {
        // Show CRLF files with plain LF, but only when every line break is
        // CRLF, so that saving can restore them exactly.
        String withoutCrlf = text.replace("\r\n", "\n");
        boolean hasCrlf = withoutCrlf.length() != text.length();
        boolean uniform = hasCrlf && withoutCrlf.indexOf('\r') < 0
                && countOf(withoutCrlf, '\n') == text.length() - withoutCrlf.length();
        d.crlf = uniform;
        d.text = uniform ? withoutCrlf : text;
    }

    private static int countOf(String s, char c) {
        int n = 0;
        for (int i = s.indexOf(c); i >= 0; i = s.indexOf(c, i + 1)) n++;
        return n;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static String strictDecode(byte[] data, int offset, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Encodes edited text back to bytes.
     *
     * @throws ParseException if the text has a character the charset cannot store
     */
    static byte[] encodeText(String text, Charset charset, boolean bom, boolean crlf)
            throws ParseException {
        if (crlf) text = text.replace("\r\n", "\n").replace("\n", "\r\n");
        ByteBuffer body;
        try {
            body = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException e) {
            throw new ParseException("The text has characters that " + charset.name()
                    + " cannot store.", 0);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.remaining() + 3);
        if (bom) {
            if (charset.equals(StandardCharsets.UTF_8)) {
                out.write(0xEF); out.write(0xBB); out.write(0xBF);
            } else if (charset.equals(StandardCharsets.UTF_16LE)) {
                out.write(0xFF); out.write(0xFE);
            } else if (charset.equals(StandardCharsets.UTF_16BE)) {
                out.write(0xFE); out.write(0xFF);
            }
        }
        out.write(body.array(), body.arrayOffset() + body.position(), body.remaining());
        return out.toByteArray();
    }

    /**
     * Cuts data to at most {@code limit} bytes without splitting a UTF-8
     * character, so that a cut-off text file still decodes as text.
     */
    static byte[] truncate(byte[] data, int limit) {
        if (data.length <= limit) return data;
        int end = limit;
        // Step back over the bytes of a character that the cut would split.
        int back = 0;
        while (end > 0 && back < 3 && (data[end] & 0xC0) == 0x80) {
            end--;
            back++;
        }
        if ((data[end] & 0xC0) == 0x80) end = limit; // not UTF-8; cut anywhere
        return Arrays.copyOf(data, end);
    }

    /**
     * Formats bytes as editable hex lines:
     * {@code 00000000: 48 65 6C 6C 6F 20 77 6F | Hello wo}
     */
    static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length / BYTES_PER_LINE * 48 + 48);
        for (int off = 0; off < data.length; off += BYTES_PER_LINE) {
            for (int shift = 28; shift >= 0; shift -= 4) sb.append(HEX[(off >>> shift) & 0xF]);
            sb.append(':');
            int end = Math.min(off + BYTES_PER_LINE, data.length);
            for (int i = off; i < off + BYTES_PER_LINE; i++) {
                if (i < end) {
                    sb.append(' ').append(HEX[(data[i] >> 4) & 0xF]).append(HEX[data[i] & 0xF]);
                } else {
                    sb.append("   ");
                }
            }
            sb.append(" | ");
            for (int i = off; i < end; i++) {
                int b = data[i] & 0xFF;
                sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Reads hex lines back into bytes. On each line the offset before ":" and
     * the preview after "|" are ignored; only the hex digits between count.
     *
     * @throws ParseException with the 1-based line number as its offset
     */
    static byte[] fromHex(String text) throws ParseException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() / 5);
        int line = 1;
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int eol = text.indexOf('\n', pos);
            if (eol < 0) eol = n;
            int start = pos;
            int end = eol;
            int bar = text.indexOf('|', start);
            if (bar >= 0 && bar < end) end = bar;
            int colon = text.indexOf(':', start);
            if (colon >= 0 && colon < end) start = colon + 1;
            int high = -1;
            for (int i = start; i < end; i++) {
                char c = text.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r') continue;
                int v = Character.digit(c, 16);
                if (v < 0 || c > 'f') {
                    throw new ParseException("Line " + line + ": \"" + c
                            + "\" is not a hex digit.", line);
                }
                if (high < 0) {
                    high = v;
                } else {
                    out.write((high << 4) | v);
                    high = -1;
                }
            }
            if (high >= 0) {
                throw new ParseException("Line " + line
                        + ": a byte needs two hex digits.", line);
            }
            pos = eol + 1;
            line++;
        }
        return out.toByteArray();
    }

    /**
     * Re-indents JSON with two spaces. Strings, numbers and key order are
     * copied exactly as written; only the whitespace between them changes.
     *
     * @throws ParseException if brackets do not match or a string is not closed
     */
    static String formatJson(String json) throws ParseException {
        StringBuilder sb = new StringBuilder(json.length() + json.length() / 4);
        ArrayDeque<Character> open = new ArrayDeque<>();
        int n = json.length();
        int i = 0;
        while (i < n) {
            char c = json.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < n && json.charAt(j) != '"') {
                    j += json.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= n) throw new ParseException("A string is missing its closing quote.", i);
                sb.append(json, i, j + 1);
                i = j + 1;
            } else if (c == '{' || c == '[') {
                char close = c == '{' ? '}' : ']';
                int j = skipSpace(json, i + 1);
                if (j < n && json.charAt(j) == close) {
                    sb.append(c).append(close);
                    i = j + 1;
                } else {
                    open.push(close);
                    sb.append(c);
                    newLine(sb, open.size());
                    i = j;
                }
            } else if (c == '}' || c == ']') {
                if (open.isEmpty() || open.pop() != c) {
                    throw new ParseException("Unexpected \"" + c + "\".", i);
                }
                newLine(sb, open.size());
                sb.append(c);
                i++;
            } else if (c == ',') {
                sb.append(c);
                newLine(sb, open.size());
                i = skipSpace(json, i + 1);
            } else if (c == ':') {
                sb.append(": ");
                i = skipSpace(json, i + 1);
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        if (!open.isEmpty()) {
            throw new ParseException("Missing \"" + open.peek() + "\" at the end.", n);
        }
        if (sb.length() > 0) sb.append('\n');
        return sb.toString();
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static void newLine(StringBuilder sb, int depth) {
        sb.append('\n');
        for (int k = 0; k < depth; k++) sb.append("  ");
    }
}
