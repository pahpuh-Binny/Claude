package io.github.pahpuhbinny.usbcapture.uvc;

import java.util.Locale;

/** One concrete stream setting: format + frame size + frame interval on a streaming interface. */
public final class VideoMode {
    public final int streamIndex;
    public final int ifnum;
    public final int formatIndex;
    public final int frameIndex;
    public final int interval;          // 100 ns units
    public final int pixelFormat;
    public final String fourcc;
    public final int width;
    public final int height;
    public final long maxFrameSize;
    /** Rough USB bandwidth needed in bytes/second (0 for compressed formats). */
    public final long bandwidth;
    public boolean exceedsBandwidth;

    public VideoMode(int streamIndex, int ifnum, UvcDeviceInfo.Format f, UvcDeviceInfo.Frame fr, int interval) {
        this.streamIndex = streamIndex;
        this.ifnum = ifnum;
        this.formatIndex = f.index;
        this.frameIndex = fr.index;
        this.interval = interval;
        this.pixelFormat = f.pixelFormat;
        this.fourcc = f.fourcc;
        this.width = fr.width;
        this.height = fr.height;
        this.maxFrameSize = fr.maxFrameSize;
        long fb = PixelFormat.frameBytes(f.pixelFormat, fr.width, fr.height);
        this.bandwidth = fb > 0 ? (long) (fb * fps()) : 0;
    }

    public double fps() {
        return interval > 0 ? 1e7 / interval : 0;
    }

    /** Frame rate rounded for display, e.g. 59.94 shows as "59.94", 60.0 as "60". */
    public String fpsLabel() {
        double f = fps();
        double r = Math.round(f);
        if (Math.abs(f - r) < 0.02) return String.valueOf((long) r);
        return String.format(Locale.US, "%.2f", f);
    }

    public long pixels() {
        return (long) width * height;
    }

    public boolean isSupported() {
        return PixelFormat.isSupported(pixelFormat);
    }

    public String resolutionLabel() {
        return width + "×" + height;
    }

    public String label() {
        return width + "×" + height + " · " + fpsLabel() + " fps · " + PixelFormat.name(pixelFormat);
    }

    public String key() {
        return ifnum + ":" + formatIndex + ":" + frameIndex + ":" + interval;
    }

    public boolean sameAs(VideoMode o) {
        return o != null && key().equals(o.key());
    }

    @Override
    public String toString() {
        return label() + " [if " + ifnum + " fmt " + formatIndex + " frame " + frameIndex + " interval " + interval
                + (exceedsBandwidth ? ", over USB bandwidth" : "") + "]";
    }
}
