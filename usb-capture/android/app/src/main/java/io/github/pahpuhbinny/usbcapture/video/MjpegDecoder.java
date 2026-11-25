package io.github.pahpuhbinny.usbcapture.video;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Process;
import android.os.SystemClock;

import java.util.Locale;

import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.uvc.NativeStream;
import io.github.pahpuhbinny.usbcapture.uvc.PixelFormat;

/**
 * Decodes MJPEG frames on one or more worker threads into a small pool
 * of reusable Bitmaps. Like the native side it keeps only the newest
 * frame: when decoding or drawing falls behind, old frames are skipped
 * rather than queued, so latency stays flat and nothing piles up.
 */
public final class MjpegDecoder implements FrameSource {
    private static final String TAG = "Mjpeg";
    private static final int FREE = 0, DECODING = 1, READY = 2, RENDERING = 3;

    private static final class Slot {
        Bitmap bitmap;
        int state;
        long seq;
        long ts;
        int flags;
    }

    private final NativeStream stream;
    private final int width;
    private final int height;
    private final double fps;
    private final boolean rgb565;
    private final Slot[] slots;
    private final Thread[] workers;
    private final Object lock = new Object();
    private volatile boolean stopped;
    private volatile boolean ended;
    private long newestPublished = -1;

    // stats (guarded by lock)
    private long decoded;
    private long decodeErrors;
    private long skipped;
    private long decodeNs;
    private long decodeCount;
    private float lastAvgMs;

    public MjpegDecoder(NativeStream stream, int width, int height, double fps, int threads, boolean rgb565) {
        this.stream = stream;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.rgb565 = rgb565;
        int n = Math.max(1, Math.min(4, threads));
        slots = new Slot[n + 2];
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
        workers = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int id = i;
            workers[i] = new Thread(() -> workerLoop(id), "mjpeg-" + i);
            workers[i].start();
        }
        AppLog.i(TAG, "MJPEG decoding with " + n + " thread(s), " + (rgb565 ? "RGB565" : "ARGB8888") + " output");
    }

    /** Picks a sensible number of decoder threads for a mode. */
    public static int autoThreads(int w, int h, double fps) {
        double load = (double) w * h * fps; // pixels per second
        int cores = Runtime.getRuntime().availableProcessors();
        int t;
        if (load <= 1920.0 * 1080 * 31) t = 1;
        else if (load <= 1920.0 * 1080 * 61) t = 2;
        else if (load <= 3840.0 * 2160 * 31) t = 3;
        else t = 4;
        return Math.max(1, Math.min(t, Math.max(1, cores - 2)));
    }

    private void workerLoop(int id) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
        }
        byte[] jpeg = new byte[Math.max(256 * 1024, width * height / 4)];
        boolean[] endedFlag = new boolean[1];
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = true;
        opts.inScaled = false;
        opts.inSampleSize = 1;
        opts.inPreferredConfig = rgb565 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        long consecutiveErrors = 0;

        while (!stopped) {
            endedFlag[0] = false;
            NativeStream.FrameRef ref = stream.acquire(100, endedFlag);
            if (endedFlag[0]) {
                ended = true;
                SystemClock.sleep(20);
            }
            if (ref == null) continue;
            int n = ref.size;
            long seq = ref.seq, ts = ref.timestampNs;
            int flags = ref.flags;
            try {
                if (jpeg.length < n) jpeg = new byte[n + n / 4];
                ref.data.position(0);
                ref.data.get(jpeg, 0, n);
            } catch (Throwable t) {
                AppLog.w(TAG, "Copying a frame failed", t);
                n = 0;
            } finally {
                stream.release(ref);
            }
            if (n <= 0) continue;

            Slot slot = obtainSlot();
            if (slot == null) continue;

            long t0 = System.nanoTime();
            Bitmap out = null;
            try {
                opts.inBitmap = slot.bitmap;
                try {
                    out = BitmapFactory.decodeByteArray(jpeg, 0, n, opts);
                } catch (IllegalArgumentException reuseFailed) {
                    // Frame size changed: decode into a fresh bitmap.
                    opts.inBitmap = null;
                    out = BitmapFactory.decodeByteArray(jpeg, 0, n, opts);
                }
            } catch (OutOfMemoryError oom) {
                AppLog.e(TAG, "Out of memory decoding a " + width + "x" + height + " frame");
                SystemClock.sleep(50);
            } catch (Throwable t) {
                if (consecutiveErrors < 3) AppLog.w(TAG, "Decoder error", t);
            }
            long dt = System.nanoTime() - t0;

            synchronized (lock) {
                if (out == null) {
                    decodeErrors++;
                    consecutiveErrors++;
                    if (consecutiveErrors == 30) AppLog.w(TAG, "30 frames in a row could not be decoded (corrupt MJPEG?)");
                    slot.state = FREE;
                    continue;
                }
                consecutiveErrors = 0;
                if (out != slot.bitmap) {
                    if (slot.bitmap != null) slot.bitmap.recycle();
                    slot.bitmap = out;
                }
                decoded++;
                decodeNs += dt;
                decodeCount++;
                if (seq < newestPublished) {
                    slot.state = FREE; // another worker already showed a newer frame
                    skipped++;
                    continue;
                }
                for (Slot s : slots) {
                    if (s != slot && s.state == READY) {
                        s.state = FREE;
                        skipped++;
                    }
                }
                slot.state = READY;
                slot.seq = seq;
                slot.ts = ts;
                slot.flags = flags;
                newestPublished = seq;
                lock.notifyAll();
            }
        }
    }

    private Slot obtainSlot() {
        synchronized (lock) {
            for (Slot s : slots) {
                if (s.state == FREE) {
                    s.state = DECODING;
                    return s;
                }
            }
            Slot oldest = null;
            for (Slot s : slots) {
                if (s.state == READY && (oldest == null || s.seq < oldest.seq)) oldest = s;
            }
            if (oldest != null) {
                oldest.state = DECODING;
                skipped++;
                return oldest;
            }
            skipped++;
            return null;
        }
    }

    @Override
    public Frame acquire(int timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!stopped) {
                Slot best = null;
                for (Slot s : slots) {
                    if (s.state == READY && (best == null || s.seq > best.seq)) best = s;
                }
                if (best != null) {
                    best.state = RENDERING;
                    Frame f = new Frame();
                    f.pixelFormat = PixelFormat.BITMAP;
                    f.bitmap = best.bitmap;
                    f.width = best.bitmap.getWidth();
                    f.height = best.bitmap.getHeight();
                    f.seq = best.seq;
                    f.timestampNs = best.ts;
                    f.flags = best.flags;
                    f.token = best;
                    return f;
                }
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) return null;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    @Override
    public void release(Frame f) {
        if (f == null || !(f.token instanceof Slot)) return;
        synchronized (lock) {
            Slot s = (Slot) f.token;
            if (s.state == RENDERING) s.state = FREE;
            f.token = null;
            lock.notifyAll();
        }
    }

    @Override
    public void stop() {
        stopped = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        for (Thread t : workers) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) AppLog.w(TAG, "Decoder thread " + t.getName() + " did not stop in time");
        }
    }

    @Override
    public boolean ended() {
        return ended;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public double nominalFps() {
        return fps;
    }

    @Override
    public String statsLine() {
        synchronized (lock) {
            if (decodeCount > 0) {
                lastAvgMs = decodeNs / 1e6f / decodeCount;
                decodeNs = 0;
                decodeCount = 0;
            }
            return String.format(Locale.US, "JPEG decode %.1f ms x%d · errors %d · skipped %d",
                    lastAvgMs, workers.length, decodeErrors, skipped);
        }
    }

    public float averageDecodeMs() {
        synchronized (lock) {
            return lastAvgMs;
        }
    }

    public int threads() {
        return workers.length;
    }
}
