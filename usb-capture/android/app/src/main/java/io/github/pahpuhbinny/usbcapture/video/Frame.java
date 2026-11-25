package io.github.pahpuhbinny.usbcapture.video;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/** One video frame on its way to the renderer. */
public final class Frame {
    public int pixelFormat;
    public int width;
    public int height;
    /** Raw pixels for uncompressed formats (points into native memory). */
    public ByteBuffer data;
    public int size;
    /** Decoded pixels for MJPEG. */
    public Bitmap bitmap;
    public long seq;
    /** Capture time on the System.nanoTime() clock. */
    public long timestampNs;
    public int flags;
    /** Owner-private handle used to release the frame. */
    Object token;
}
