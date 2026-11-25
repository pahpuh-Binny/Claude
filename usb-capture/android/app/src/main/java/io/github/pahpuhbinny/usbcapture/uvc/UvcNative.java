package io.github.pahpuhbinny.usbcapture.uvc;

import java.nio.ByteBuffer;

/** JNI entry points into libuvcstream (src/main/cpp). */
public final class UvcNative {
    public static final boolean AVAILABLE;
    private static String loadError;

    // Indexes into the stats array (keep in sync with uvc_stream.h).
    public static final int ST_URBS_REAPED = 0;
    public static final int ST_BYTES = 1;
    public static final int ST_PAYLOADS = 2;
    public static final int ST_EMPTY_PAYLOADS = 3;
    public static final int ST_BAD_HEADERS = 4;
    public static final int ST_ISO_PACKET_ERRORS = 5;
    public static final int ST_URB_ERRORS = 6;
    public static final int ST_FRAMES_COMPLETE = 7;
    public static final int ST_FRAMES_PUBLISHED = 8;
    public static final int ST_DROP_ERROR = 9;
    public static final int ST_DROP_INCOMPLETE = 10;
    public static final int ST_DROP_OVERFLOW = 11;
    public static final int ST_DROP_INVALID = 12;
    public static final int ST_DROP_NO_BUFFER = 13;
    public static final int ST_SKIPPED_BY_CONSUMER = 14;
    public static final int ST_DELIVERED = 15;
    public static final int ST_RESUBMIT_FAILURES = 16;
    public static final int ST_STALLS_CLEARED = 17;
    public static final int ST_PAYLOAD_ERR_BIT = 18;
    public static final int ST_URBS_IN_FLIGHT = 19;
    public static final int ST_LAST_FRAME_SIZE = 20;
    public static final int ST_MAX_FRAME_SIZE_SEEN = 21;
    public static final int ST_STOP_REASON = 22;
    public static final int ST_LAST_ERRNO = 23;
    public static final int ST_COUNT = 24;

    public static final int STOP_NONE = 0;
    public static final int STOP_REQUESTED = 1;
    public static final int STOP_DEVICE_GONE = 2;
    public static final int STOP_FATAL = 3;

    public static final int XFER_ISO = 0;
    public static final int XFER_BULK = 1;
    public static final int FMT_MJPEG = 1;
    public static final int FMT_UNCOMPRESSED = 2;
    public static final int FMT_FRAME_BASED = 3;

    public static final int FRAME_FLAG_TRUNCATED = 1;
    public static final int FRAME_FLAG_ERROR_TOLERATED = 2;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("uvcstream");
            ok = true;
        } catch (Throwable t) {
            loadError = t.toString();
        }
        AVAILABLE = ok;
    }

    private UvcNative() {
    }

    public static String loadError() {
        return loadError;
    }

    public static String strerror(int err) {
        if (!AVAILABLE) return "error " + err;
        try {
            return nativeStrError(err);
        } catch (Throwable t) {
            return "error " + err;
        }
    }

    static native long nativeCreate(int fd, long[] config);

    static native int nativeStart(long handle);

    static native void nativeStop(long handle);

    static native int nativeDestroy(long handle);

    static native int nativeAcquire(long handle, int timeoutMs, long[] info);

    static native ByteBuffer nativeView(long handle, int index);

    static native void nativeRelease(long handle, int index);

    static native void nativeGetStats(long handle, long[] out);

    static native String[] nativeDrainLog(long handle);

    static native void nativeSetTolerateErrors(long handle, boolean on);

    static native int nativeSetInterface(int fd, int iface, int alt);

    static native int nativeClearHalt(int fd, int endpoint);

    static native int nativeGetSpeed(int fd);

    public static native void nativeInstallCrashHandler(String path);

    static native String nativeStrError(int err);
}
