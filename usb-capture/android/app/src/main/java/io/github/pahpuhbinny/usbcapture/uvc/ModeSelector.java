package io.github.pahpuhbinny.usbcapture.uvc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lists a device's video modes and ranks them for the quality presets. */
public final class ModeSelector {
    public static final int PRESET_BEST_QUALITY = 0;
    public static final int PRESET_SMOOTHEST = 1;
    public static final int PRESET_LOW_LATENCY = 2;

    private ModeSelector() {
    }

    public static List<VideoMode> allModes(UvcCamera cam) {
        List<VideoMode> out = new ArrayList<>();
        UvcDeviceInfo info = cam.info;
        for (int si = 0; si < info.streams.size(); si++) {
            UvcDeviceInfo.StreamInterface s = info.streams.get(si);
            long link = cam.linkBandwidth(s);
            for (UvcDeviceInfo.Format f : s.formats) {
                for (UvcDeviceInfo.Frame fr : f.frames) {
                    for (int iv : fr.offeredIntervals()) {
                        if (iv <= 0) continue;
                        VideoMode m = new VideoMode(si, s.ifnum, f, fr, iv);
                        m.exceedsBandwidth = m.bandwidth > 0 && link > 0 && m.bandwidth > link * 0.97;
                        out.add(m);
                    }
                }
            }
        }
        return out;
    }

    /** Best-first list of modes for a preset, for automatic selection with fallback. */
    public static List<VideoMode> rank(List<VideoMode> modes, int preset) {
        List<VideoMode> ok = new ArrayList<>();
        for (VideoMode m : modes) if (m.isSupported()) ok.add(m);
        Comparator<VideoMode> cmp;
        switch (preset) {
            case PRESET_SMOOTHEST:
                cmp = Comparator.<VideoMode>comparingDouble(m -> -Math.min(roundFps(m), 240))
                        .thenComparingLong(m -> -m.pixels())
                        .thenComparingInt(m -> m.pixelFormat == PixelFormat.MJPEG ? 1 : 0);
                break;
            case PRESET_LOW_LATENCY:
                cmp = Comparator.<VideoMode>comparingInt(m -> m.pixelFormat == PixelFormat.MJPEG ? 1 : 0)
                        .thenComparingDouble(m -> -Math.min(roundFps(m), 240))
                        .thenComparingLong(m -> -Math.min(m.pixels(), 1920L * 1080));
                break;
            default:
                cmp = Comparator.<VideoMode>comparingLong(m -> -m.pixels())
                        .thenComparingDouble(m -> -roundFps(m))
                        .thenComparingInt(m -> m.pixelFormat == PixelFormat.MJPEG ? 1 : 0);
        }
        // Modes that cannot fit the USB link go last, whatever the preset.
        ok.sort(Comparator.<VideoMode>comparingInt(m -> m.exceedsBandwidth ? 1 : 0).thenComparing(cmp));
        return ok;
    }

    private static double roundFps(VideoMode m) {
        return Math.round(m.fps() * 10) / 10.0;
    }

    /** Distinct resolutions, largest first. */
    public static List<int[]> resolutions(List<VideoMode> modes) {
        Map<String, int[]> map = new LinkedHashMap<>();
        for (VideoMode m : modes) {
            if (!m.isSupported()) continue;
            map.put(m.width + "x" + m.height, new int[]{m.width, m.height});
        }
        List<int[]> out = new ArrayList<>(map.values());
        out.sort((a, b) -> Long.compare((long) b[0] * b[1], (long) a[0] * a[1]));
        return out;
    }

    /** Modes at one resolution (optionally one pixel format), highest frame rate first. */
    public static List<VideoMode> atResolution(List<VideoMode> modes, int w, int h, int pixelFormat) {
        List<VideoMode> out = new ArrayList<>();
        for (VideoMode m : modes) {
            if (m.width == w && m.height == h && m.isSupported() && (pixelFormat == 0 || m.pixelFormat == pixelFormat)) {
                out.add(m);
            }
        }
        out.sort(Comparator.<VideoMode>comparingInt(m -> m.exceedsBandwidth ? 1 : 0)
                .thenComparingDouble(m -> -m.fps())
                .thenComparingInt(m -> m.pixelFormat == PixelFormat.MJPEG ? 1 : 0));
        return out;
    }

    public static VideoMode findByKey(List<VideoMode> modes, String key) {
        if (key == null) return null;
        for (VideoMode m : modes) if (m.key().equals(key)) return m;
        return null;
    }

    /** Distinct pixel formats offered (supported ones first). */
    public static List<Integer> formats(List<VideoMode> modes) {
        List<Integer> out = new ArrayList<>();
        for (VideoMode m : modes) if (!out.contains(m.pixelFormat)) out.add(m.pixelFormat);
        out.sort(Comparator.comparingInt(f -> PixelFormat.isSupported(f) ? 0 : 1));
        return out;
    }
}
