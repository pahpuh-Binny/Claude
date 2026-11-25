package io.github.pahpuhbinny.usbcapture.video;

/** Something that produces frames for the renderer. */
public interface FrameSource {
    /** Waits up to timeoutMs for the newest frame; null if none arrived. */
    Frame acquire(int timeoutMs);

    /** Hands a frame back. Must be called once for every frame acquired. */
    void release(Frame f);

    /** Stops producing frames and frees worker threads; acquire returns null afterwards. */
    void stop();

    /** True once the underlying stream has ended on its own (e.g. unplugged). */
    boolean ended();

    int width();

    int height();

    /** Frame rate the source was configured for (0 if unknown). */
    double nominalFps();

    /** Extra stats line for the overlay (may be empty). */
    String statsLine();
}
