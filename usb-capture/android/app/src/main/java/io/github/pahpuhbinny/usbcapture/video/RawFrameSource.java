package io.github.pahpuhbinny.usbcapture.video;

import io.github.pahpuhbinny.usbcapture.uvc.NativeStream;

/** Uncompressed frames (YUY2, NV12, ...) handed straight from native memory to the GPU. */
public final class RawFrameSource implements FrameSource {
    private final NativeStream stream;
    private final int pixelFormat;
    private final int width;
    private final int height;
    private final double fps;
    private volatile boolean stopped;
    private volatile boolean ended;
    private final boolean[] endedFlag = new boolean[1];

    public RawFrameSource(NativeStream stream, int pixelFormat, int width, int height, double fps) {
        this.stream = stream;
        this.pixelFormat = pixelFormat;
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    @Override
    public Frame acquire(int timeoutMs) {
        if (stopped) return null;
        endedFlag[0] = false;
        NativeStream.FrameRef ref = stream.acquire(timeoutMs, endedFlag);
        if (endedFlag[0]) ended = true;
        if (ref == null) return null;
        Frame f = new Frame();
        f.pixelFormat = pixelFormat;
        f.width = width;
        f.height = height;
        f.data = ref.data;
        f.size = ref.size;
        f.seq = ref.seq;
        f.timestampNs = ref.timestampNs;
        f.flags = ref.flags;
        f.token = ref;
        return f;
    }

    @Override
    public void release(Frame f) {
        if (f != null && f.token instanceof NativeStream.FrameRef) {
            stream.release((NativeStream.FrameRef) f.token);
            f.token = null;
            f.data = null;
        }
    }

    @Override
    public void stop() {
        stopped = true;
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
        return "";
    }
}
