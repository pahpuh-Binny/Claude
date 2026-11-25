package io.github.pahpuhbinny.usbcapture.video;

import android.os.SystemClock;

import io.github.pahpuhbinny.usbcapture.uvc.PixelFormat;

/**
 * A 1080p60 test signal drawn on the GPU (colour bars, a grey ramp and a
 * moving marker), so every display, recording and overlay feature can be
 * tried without a capture card.
 */
public final class TestPatternSource implements FrameSource {
    private final int width;
    private final int height;
    private final double fps;
    private final long periodNs;
    private long next;
    private long seq;
    private volatile boolean stopped;

    public TestPatternSource(int width, int height, double fps) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.periodNs = (long) (1e9 / fps);
        this.next = System.nanoTime();
    }

    @Override
    public Frame acquire(int timeoutMs) {
        if (stopped) return null;
        long now = System.nanoTime();
        long wait = next - now;
        if (wait > timeoutMs * 1_000_000L) {
            SystemClock.sleep(timeoutMs);
            return null;
        }
        if (wait > 0) {
            SystemClock.sleep(wait / 1_000_000L);
        }
        now = System.nanoTime();
        next = Math.max(next + periodNs, now - periodNs);
        Frame f = new Frame();
        f.pixelFormat = PixelFormat.PATTERN;
        f.width = width;
        f.height = height;
        f.seq = seq++;
        f.timestampNs = now;
        return f;
    }

    @Override
    public void release(Frame f) {
    }

    @Override
    public void stop() {
        stopped = true;
    }

    @Override
    public boolean ended() {
        return false;
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
        return "Test pattern";
    }
}
