package io.github.pahpuhbinny.usbcapture.uvc;

/** Pixel layouts the app understands, and how to recognise them in UVC descriptors. */
public final class PixelFormat {
    public static final int UNKNOWN = 0;
    public static final int MJPEG = 1;
    public static final int YUY2 = 2;   // Y0 U Y1 V
    public static final int UYVY = 3;   // U Y0 V Y1
    public static final int YVYU = 4;   // Y0 V Y1 U
    public static final int NV12 = 5;   // Y plane + interleaved UV
    public static final int NV21 = 6;   // Y plane + interleaved VU
    public static final int I420 = 7;   // Y + U + V planes
    public static final int YV12 = 8;   // Y + V + U planes
    public static final int GREY = 9;   // 8-bit luma only
    public static final int RGB565 = 10;
    public static final int BGR24 = 11;
    public static final int RGB24 = 12;
    public static final int BGRA32 = 13;
    public static final int H264 = 14;
    public static final int H265 = 15;
    public static final int P010 = 16;
    /** Decoded frame in an Android Bitmap (from MJPEG). */
    public static final int BITMAP = 30;
    /** Built-in test pattern drawn on the GPU. */
    public static final int PATTERN = 31;

    private PixelFormat() {
    }

    /** Maps a UVC format GUID (16 bytes) to a pixel format. */
    public static int fromGuid(byte[] g) {
        if (g == null || g.length < 16) return UNKNOWN;
        // DirectShow RGB subtypes (not FourCC based)
        if ((g[1] & 0xff) == 0xeb && (g[2] & 0xff) == 0x36 && (g[3] & 0xff) == 0xe4) {
            if ((g[0] & 0xff) == 0x7d) return BGR24;
            if ((g[0] & 0xff) == 0x7e) return BGRA32;
        }
        return fromFourcc(fourcc(g));
    }

    public static String fourcc(byte[] g) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            int c = g[i] & 0xff;
            sb.append(c >= 0x20 && c < 0x7f ? (char) c : '?');
        }
        return sb.toString();
    }

    public static int fromFourcc(String f) {
        switch (f) {
            case "YUY2":
            case "YUYV":
                return YUY2;
            case "UYVY":
            case "HDYC":
                return UYVY;
            case "YVYU":
                return YVYU;
            case "NV12":
                return NV12;
            case "NV21":
                return NV21;
            case "I420":
            case "IYUV":
                return I420;
            case "YV12":
                return YV12;
            case "Y800":
            case "Y8  ":
            case "GREY":
            case "GRAY":
                return GREY;
            case "RGBP":
                return RGB565;
            case "RGB3":
                return RGB24;
            case "BGR3":
                return BGR24;
            case "MJPG":
                return MJPEG;
            case "H264":
            case "AVC1":
                return H264;
            case "H265":
            case "HEVC":
                return H265;
            case "P010":
                return P010;
            default:
                return UNKNOWN;
        }
    }

    /** Can the renderer show this format? */
    public static boolean isSupported(int fmt) {
        switch (fmt) {
            case MJPEG:
            case YUY2:
            case UYVY:
            case YVYU:
            case NV12:
            case NV21:
            case I420:
            case YV12:
            case GREY:
            case RGB565:
            case BGR24:
            case RGB24:
            case BGRA32:
                return true;
            default:
                return false;
        }
    }

    public static boolean isCompressed(int fmt) {
        return fmt == MJPEG || fmt == H264 || fmt == H265;
    }

    /** Exact size of one uncompressed frame, or 0 if variable. */
    public static long frameBytes(int fmt, int w, int h) {
        long px = (long) w * h;
        switch (fmt) {
            case YUY2:
            case UYVY:
            case YVYU:
            case RGB565:
                return px * 2;
            case NV12:
            case NV21:
            case I420:
            case YV12:
                return px + 2L * ((w + 1) / 2) * ((h + 1) / 2);
            case GREY:
                return px;
            case BGR24:
            case RGB24:
                return px * 3;
            case BGRA32:
                return px * 4;
            case P010:
                return px * 3;
            default:
                return 0;
        }
    }

    public static String name(int fmt) {
        switch (fmt) {
            case MJPEG: return "MJPEG";
            case YUY2: return "YUY2";
            case UYVY: return "UYVY";
            case YVYU: return "YVYU";
            case NV12: return "NV12";
            case NV21: return "NV21";
            case I420: return "I420";
            case YV12: return "YV12";
            case GREY: return "Grey";
            case RGB565: return "RGB565";
            case BGR24: return "BGR24";
            case RGB24: return "RGB24";
            case BGRA32: return "BGRA";
            case H264: return "H.264";
            case H265: return "H.265";
            case P010: return "P010";
            case BITMAP: return "RGBA";
            case PATTERN: return "Pattern";
            default: return "Unknown";
        }
    }

    /** Short, friendly description for Play mode. */
    public static String friendly(int fmt) {
        if (fmt == MJPEG) return "Compressed (MJPEG)";
        if (isSupported(fmt)) return "Uncompressed (" + name(fmt) + ")";
        return name(fmt) + " (not supported)";
    }
}
