package io.github.pahpuhbinny.usbcapture.uvc;

import android.os.SystemClock;

import java.nio.ByteBuffer;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/**
 * Java owner of one native streaming session.
 *
 * Frames handed out by {@link #acquire} point straight into native
 * memory, so this class makes sure that memory is never freed while a
 * frame is still out: {@link #destroy} waits for every frame to be
 * released and, if something misbehaves, leaks the memory instead of
 * risking a crash.
 */
public final class NativeStream {
    private static final String TAG = "NativeStream";

    public static final class FrameRef {
        public int index;
        public ByteBuffer data;
        public int size;
        public long seq;
        public long timestampNs;
        public long pts;
        public int flags;
    }

    private final Object lock = new Object();
    private long handle;
    private boolean closing;
    private int activeCalls;
    private int lockedFrames;
    private final long[] info = new long[6];
    private final long[] stats = new long[UvcNative.ST_COUNT];

    private NativeStream(long handle) {
        this.handle = handle;
    }

    public static NativeStream create(int fd, long[] config) {
        long h = UvcNative.nativeCreate(fd, config);
        return h == 0 ? null : new NativeStream(h);
    }

    public int start() {
        synchronized (lock) {
            if (handle == 0) return -22;
            return UvcNative.nativeStart(handle);
        }
    }

    private long enter() {
        synchronized (lock) {
            if (handle == 0 || closing) return 0;
            activeCalls++;
            return handle;
        }
    }

    private void exit() {
        synchronized (lock) {
            activeCalls--;
            lock.notifyAll();
        }
    }

    /**
     * Waits for the newest frame. Returns null on timeout; sets
     * {@code stopped[0]} if the stream has ended.
     */
    public FrameRef acquire(int timeoutMs, boolean[] stopped) {
        long h = enter();
        if (h == 0) {
            if (stopped != null) stopped[0] = true;
            return null;
        }
        try {
            long[] inf = new long[6];
            int idx = UvcNative.nativeAcquire(h, timeoutMs, inf);
            if (idx == -2 && stopped != null) stopped[0] = true;
            if (idx < 0) return null;
            ByteBuffer view = UvcNative.nativeView(h, idx);
            if (view == null) {
                UvcNative.nativeRelease(h, idx);
                return null;
            }
            synchronized (lock) {
                lockedFrames++;
            }
            FrameRef f = new FrameRef();
            f.index = idx;
            f.data = view;
            f.size = (int) inf[0];
            f.seq = inf[1];
            f.timestampNs = inf[2];
            f.pts = inf[3];
            f.flags = (int) inf[4];
            return f;
        } finally {
            exit();
        }
    }

    public void release(FrameRef f) {
        if (f == null || f.index < 0) return;
        synchronized (lock) {
            if (handle != 0) UvcNative.nativeRelease(handle, f.index);
            f.index = -1;
            f.data = null;
            lockedFrames--;
            lock.notifyAll();
        }
    }

    public long[] stats() {
        synchronized (lock) {
            if (handle != 0) UvcNative.nativeGetStats(handle, stats);
            return stats.clone();
        }
    }

    public String[] drainLog() {
        synchronized (lock) {
            if (handle == 0) return null;
            return UvcNative.nativeDrainLog(handle);
        }
    }

    public void setTolerateErrors(boolean on) {
        synchronized (lock) {
            if (handle != 0) UvcNative.nativeSetTolerateErrors(handle, on);
        }
    }

    /** Stops the USB transfers. Frames already out stay valid until released. */
    public void stop() {
        long h;
        synchronized (lock) {
            h = handle;
        }
        if (h != 0) UvcNative.nativeStop(h);
    }

    public void destroy() {
        long h;
        synchronized (lock) {
            if (handle == 0) return;
            closing = true;
            h = handle;
        }
        UvcNative.nativeStop(h); // wakes anyone blocked in acquire
        String[] tail = null;
        synchronized (lock) {
            long deadline = SystemClock.uptimeMillis() + 2000;
            while ((activeCalls > 0 || lockedFrames > 0) && SystemClock.uptimeMillis() < deadline) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (activeCalls > 0 || lockedFrames > 0) {
                AppLog.w(TAG, "Stream closed while " + lockedFrames + " frame(s) were still in use; leaking its memory to stay safe");
                handle = 0;
                return;
            }
            tail = UvcNative.nativeDrainLog(h);
            handle = 0;
        }
        if (tail != null) for (String s : tail) AppLog.i("Native", s);
        int r = UvcNative.nativeDestroy(h);
        if (r != 0) AppLog.w(TAG, "Native stream kept its buffers alive (code " + r + ")");
    }

    public boolean isOpen() {
        synchronized (lock) {
            return handle != 0 && !closing;
        }
    }
}
