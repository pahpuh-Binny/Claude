package io.github.pahpuhbinny.usbcapture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import io.github.pahpuhbinny.usbcapture.render.DisplayParams;
import io.github.pahpuhbinny.usbcapture.render.Geometry;
import io.github.pahpuhbinny.usbcapture.uvc.ModeSelector;
import io.github.pahpuhbinny.usbcapture.uvc.PixelFormat;
import io.github.pahpuhbinny.usbcapture.uvc.UvcDeviceInfo;
import io.github.pahpuhbinny.usbcapture.uvc.VideoMode;

/**
 * Parses descriptor sets laid out exactly as real devices send them
 * (field by field per the USB 2.0 / UVC 1.1 specifications).
 */
public class DescriptorTest {

    /** Little helper to write USB descriptors byte by byte. */
    static final class D {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        D desc(int... b) {
            out.write(b.length + 1);
            for (int v : b) out.write(v & 0xff);
            return this;
        }

        static int[] cat(int[]... parts) {
            int n = 0;
            for (int[] p : parts) n += p.length;
            int[] r = new int[n];
            int o = 0;
            for (int[] p : parts) {
                System.arraycopy(p, 0, r, o, p.length);
                o += p.length;
            }
            return r;
        }

        static int[] u16(int v) {
            return new int[]{v & 0xff, (v >> 8) & 0xff};
        }

        static int[] u32(long v) {
            return new int[]{(int) (v & 0xff), (int) ((v >> 8) & 0xff), (int) ((v >> 16) & 0xff), (int) ((v >> 24) & 0xff)};
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static final int[] YUY2_GUID = {'Y', 'U', 'Y', '2', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71};
    static final int[] NV12_GUID = {'N', 'V', '1', '2', 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71};

    static int[] mjpegFrame(int idx, int w, int h, int... intervals) {
        int[] head = D.cat(new int[]{0x24, 0x07, idx, 0x00}, D.u16(w), D.u16(h),
                D.u32((long) w * h * 16 * 30), D.u32((long) w * h * 16 * 60), D.u32((long) w * h * 2),
                D.u32(intervals[0]), new int[]{intervals.length});
        int[] r = head;
        for (int iv : intervals) r = D.cat(r, D.u32(iv));
        return r;
    }

    static int[] uncFrame(int idx, int w, int h, int... intervals) {
        int[] r = mjpegFrame(idx, w, h, intervals);
        r[1] = 0x05;
        return r;
    }

    /** Typical MacroSilicon MS2109 USB 2.0 HDMI capture card. */
    static byte[] ms2109() {
        D d = new D();
        // Device: bcdUSB 2.00, class EF/02/01, VID 534d PID 2109
        d.desc(0x01, 0x00, 0x02, 0xEF, 0x02, 0x01, 0x40, 0x4d, 0x53, 0x09, 0x21, 0x00, 0x21, 1, 2, 3, 1);
        // Configuration (total length not checked by the parser)
        d.desc(0x02, 0x00, 0x00, 4, 1, 0, 0x80, 250);
        // IAD video
        d.desc(0x0B, 0, 2, 0x0E, 0x03, 0x00, 2);
        // VC interface 0
        d.desc(0x04, 0, 0, 1, 0x0E, 0x01, 0x00, 2);
        d.desc(D.cat(new int[]{0x24, 0x01}, D.u16(0x0100), D.u16(0x4d), D.u32(6_000_000), new int[]{1, 1}));
        // Camera terminal id 1, bControlSize 3, no controls
        d.desc(D.cat(new int[]{0x24, 0x02, 1}, D.u16(0x0201), new int[]{0, 0}, D.u16(0), D.u16(0), D.u16(0),
                new int[]{3, 0x00, 0x00, 0x00}));
        // Processing unit id 2 <- 1, controls: brightness, contrast, hue, saturation
        d.desc(D.cat(new int[]{0x24, 0x05, 2, 1}, D.u16(0), new int[]{2, 0x0F, 0x00, 0}));
        // Output terminal id 3
        d.desc(D.cat(new int[]{0x24, 0x03, 3}, D.u16(0x0101), new int[]{0, 2, 0}));
        // Interrupt endpoint
        d.desc(0x05, 0x83, 0x03, 0x10, 0x00, 6);
        d.desc(0x25, 0x03, 0x10, 0x00);
        // VS interface 1 alt 0
        d.desc(0x04, 1, 0, 0, 0x0E, 0x02, 0x00, 0);
        d.desc(D.cat(new int[]{0x24, 0x01, 2}, D.u16(0x01ff), new int[]{0x81, 0, 3, 0, 0, 0, 1, 0, 0}));
        // MJPEG format 1 with 3 frames
        d.desc(0x24, 0x06, 1, 3, 0x01, 1, 0, 0, 0, 0);
        d.desc(mjpegFrame(1, 1920, 1080, 333333, 400000, 500000));
        d.desc(mjpegFrame(2, 1280, 720, 166666, 333333));
        d.desc(mjpegFrame(3, 640, 480, 166666, 333333));
        d.desc(0x24, 0x0D, 1, 1, 4);
        // YUY2 format 2 with 2 frames
        d.desc(D.cat(new int[]{0x24, 0x04, 2, 2}, YUY2_GUID, new int[]{16, 1, 0, 0, 0, 0}));
        d.desc(uncFrame(1, 1920, 1080, 2000000));
        d.desc(uncFrame(2, 1280, 720, 1000000));
        d.desc(0x24, 0x0D, 1, 1, 4);
        // Alt settings with isochronous endpoint 0x81
        int[][] sizes = {{1, 0x0080}, {2, 0x0200}, {3, 0x0400}, {4, 0x0B00}, {5, 0x0C00}, {6, 0x1380}, {7, 0x1400}};
        for (int[] s : sizes) {
            d.desc(0x04, 1, s[0], 1, 0x0E, 0x02, 0x00, 0);
            d.desc(0x05, 0x81, 0x05, s[1] & 0xff, s[1] >> 8, 1);
        }
        // Audio (IAD + control + streaming)
        d.desc(0x0B, 2, 2, 0x01, 0x02, 0x00, 0);
        d.desc(0x04, 2, 0, 0, 0x01, 0x01, 0x00, 0);
        d.desc(0x04, 3, 0, 0, 0x01, 0x02, 0x00, 0);
        d.desc(0x04, 3, 1, 1, 0x01, 0x02, 0x00, 0);
        d.desc(0x05, 0x82, 0x05, 0xC4, 0x00, 4);
        return d.bytes();
    }

    /** A USB 3 capture card that streams over a bulk endpoint (like many 4K cards). */
    static byte[] usb3Bulk() {
        D d = new D();
        d.desc(0x01, 0x20, 0x03, 0xEF, 0x02, 0x01, 0x09, 0xd9, 0x0f, 0x66, 0x00, 0x00, 0x01, 1, 2, 0, 1);
        d.desc(0x02, 0x00, 0x00, 2, 1, 0, 0x80, 112);
        d.desc(0x04, 0, 0, 0, 0x0E, 0x01, 0x00, 0);
        d.desc(D.cat(new int[]{0x24, 0x01}, D.u16(0x0110), D.u16(0x30), D.u32(48_000_000), new int[]{1, 1}));
        d.desc(D.cat(new int[]{0x24, 0x02, 1}, D.u16(0x0201), new int[]{0, 0}, D.u16(0), D.u16(0), D.u16(0),
                new int[]{3, 0x0A, 0x02, 0x00})); // AE mode, exposure, zoom
        d.desc(0x04, 1, 0, 1, 0x0E, 0x02, 0x00, 0);
        d.desc(D.cat(new int[]{0x24, 0x01, 1}, D.u16(0x0100), new int[]{0x81, 0, 3, 0, 0, 0, 1, 0}));
        d.desc(D.cat(new int[]{0x24, 0x04, 1, 2}, NV12_GUID, new int[]{12, 1, 0, 0, 0, 0}));
        d.desc(uncFrame(1, 3840, 2160, 333333));
        int[] cont = D.cat(new int[]{0x24, 0x05, 2, 0}, D.u16(1920), D.u16(1080), D.u32(1), D.u32(2), D.u32(3110400),
                D.u32(166666), new int[]{0}, D.u32(166666), D.u32(333333), D.u32(166667));
        d.desc(cont);
        d.desc(0x05, 0x81, 0x02, 0x00, 0x04, 0);
        d.desc(0x30, 15, 0, 0, 0);
        return d.bytes();
    }

    @Test
    public void parsesMs2109() {
        UvcDeviceInfo info = UvcDeviceInfo.parse(ms2109());
        assertEquals(0x534d, info.vendorId);
        assertEquals(0x2109, info.productId);
        assertEquals(0x0100, info.bcdUVC);
        assertEquals(0, info.vcInterface);
        assertFalse(info.superSpeed);
        assertTrue(info.warnings.toString(), info.warnings.isEmpty());

        UvcDeviceInfo.Unit pu = info.unit(UvcDeviceInfo.UNIT_PROCESSING);
        assertNotNull(pu);
        assertEquals(2, pu.id);
        assertEquals(0x0F, pu.controls);
        UvcDeviceInfo.Unit ct = info.unit(UvcDeviceInfo.UNIT_CAMERA);
        assertNotNull(ct);
        assertEquals(1, ct.id);

        assertEquals(1, info.streams.size());
        UvcDeviceInfo.StreamInterface s = info.streams.get(0);
        assertEquals(1, s.ifnum);
        assertEquals(0x81, s.endpointAddress);
        assertFalse(s.isBulk());
        assertEquals(8, s.alts.size()); // alt 0 + 7 bandwidth settings
        assertEquals(3072, s.maxIsoBytes()); // 0x1400 = 3 x 1024
        assertEquals(2, s.formats.size());

        UvcDeviceInfo.Format mj = s.formats.get(0);
        assertEquals(PixelFormat.MJPEG, mj.pixelFormat);
        assertEquals(3, mj.frames.size());
        assertEquals(1920, mj.frames.get(0).width);
        assertEquals(1080, mj.frames.get(0).height);
        assertEquals(3, mj.frames.get(0).intervals.length);
        assertEquals(333333, mj.frames.get(0).intervals[0]);
        assertEquals(1920L * 1080 * 2, mj.frames.get(0).maxFrameSize);
        assertEquals(4, mj.matrix);

        UvcDeviceInfo.Format yuy = s.formats.get(1);
        assertEquals(PixelFormat.YUY2, yuy.pixelFormat);
        assertEquals("YUY2", yuy.fourcc);
        assertEquals(16, yuy.bitsPerPixel);
        assertEquals(2, yuy.frames.size());

        assertEquals(1, info.audioInterfaces.size());
        assertEquals(3, (int) info.audioInterfaces.get(0));
    }

    @Test
    public void parsesUsb3BulkDevice() {
        UvcDeviceInfo info = UvcDeviceInfo.parse(usb3Bulk());
        assertTrue(info.superSpeed);
        assertEquals(0x0110, info.bcdUVC);
        UvcDeviceInfo.StreamInterface s = info.streams.get(0);
        assertTrue(s.isBulk());
        assertNotNull(s.bulkAlt());
        UvcDeviceInfo.Format f = s.formats.get(0);
        assertEquals(PixelFormat.NV12, f.pixelFormat);
        assertEquals(2, f.frames.size());
        UvcDeviceInfo.Frame cont = f.frames.get(1);
        assertTrue(cont.continuous);
        assertEquals(166666, cont.minInterval);
        int[] offered = cont.offeredIntervals();
        assertTrue(offered.length >= 2);
        assertEquals(166666, offered[0]);
        UvcDeviceInfo.Unit ct = info.unit(UvcDeviceInfo.UNIT_CAMERA);
        assertEquals(0x020A, ct.controls);
    }

    @Test
    public void survivesGarbage() {
        byte[] junk = {5, 2, 1, 0, 0, 0, 0, 0, 0, 1, 1};
        UvcDeviceInfo info = UvcDeviceInfo.parse(junk);
        assertFalse(info.warnings.isEmpty());
        UvcDeviceInfo.parse(new byte[0]);
        UvcDeviceInfo.parse(null);
        byte[] ms = ms2109();
        for (int cut = 1; cut < ms.length; cut += 7) {
            byte[] t = new byte[cut];
            System.arraycopy(ms, 0, t, 0, cut);
            UvcDeviceInfo.parse(t); // must never throw
        }
    }

    private static List<VideoMode> modesOf(UvcDeviceInfo info, long linkBytesPerSec) {
        List<VideoMode> out = new ArrayList<>();
        UvcDeviceInfo.StreamInterface s = info.streams.get(0);
        for (UvcDeviceInfo.Format f : s.formats) {
            for (UvcDeviceInfo.Frame fr : f.frames) {
                for (int iv : fr.offeredIntervals()) {
                    VideoMode m = new VideoMode(0, s.ifnum, f, fr, iv);
                    m.exceedsBandwidth = m.bandwidth > linkBytesPerSec;
                    out.add(m);
                }
            }
        }
        return out;
    }

    @Test
    public void ranksPresets() {
        UvcDeviceInfo info = UvcDeviceInfo.parse(ms2109());
        List<VideoMode> modes = modesOf(info, 3072L * 8000);
        VideoMode best = ModeSelector.rank(modes, ModeSelector.PRESET_BEST_QUALITY).get(0);
        assertEquals(1920, best.width);
        assertEquals("30", best.fpsLabel());
        assertEquals(PixelFormat.MJPEG, best.pixelFormat); // YUY2 1080p5 is slower, MJPEG 1080p30 wins

        VideoMode smooth = ModeSelector.rank(modes, ModeSelector.PRESET_SMOOTHEST).get(0);
        assertEquals("60", smooth.fpsLabel());
        assertEquals(1280, smooth.width);

        VideoMode low = ModeSelector.rank(modes, ModeSelector.PRESET_LOW_LATENCY).get(0);
        assertEquals(PixelFormat.YUY2, low.pixelFormat);

        assertEquals(3, ModeSelector.resolutions(modes).size());
        assertEquals(1920, ModeSelector.resolutions(modes).get(0)[0]);
    }

    @Test
    public void bandwidthLimitedModesGoLast() {
        UvcDeviceInfo info = UvcDeviceInfo.parse(usb3Bulk());
        List<VideoMode> modes = modesOf(info, 120_000_000L); // only 1080p30 NV12 fits
        List<VideoMode> ranked = ModeSelector.rank(modes, ModeSelector.PRESET_BEST_QUALITY);
        assertFalse(ranked.get(0).exceedsBandwidth);
        assertEquals(1920, ranked.get(0).width);
        assertEquals("30", ranked.get(0).fpsLabel());
        assertTrue(ranked.get(ranked.size() - 1).exceedsBandwidth);
    }

    @Test
    public void frameSizes() {
        assertEquals(1920L * 1080 * 2, PixelFormat.frameBytes(PixelFormat.YUY2, 1920, 1080));
        assertEquals(1920L * 1080 * 3 / 2, PixelFormat.frameBytes(PixelFormat.NV12, 1920, 1080));
        assertEquals(5L * 3 + 2L * 3 * 2, PixelFormat.frameBytes(PixelFormat.I420, 5, 3));
        assertEquals(0, PixelFormat.frameBytes(PixelFormat.MJPEG, 1920, 1080));
    }

    @Test
    public void geometryFitFillRotate() {
        DisplayParams p = new DisplayParams();
        Geometry g = Geometry.compute(p, 1920, 1080, 640, 480); // 4:3 into 16:9
        assertEquals(1440f, g.width(), 0.5f);
        assertEquals(1080f, g.height(), 0.5f);
        assertEquals(240f, g.rect.left, 0.5f);

        p.scaleMode = DisplayParams.SCALE_FILL;
        g = Geometry.compute(p, 1920, 1080, 640, 480);
        assertEquals(1920f, g.width(), 0.5f);
        assertEquals(1440f, g.height(), 0.5f);

        p.scaleMode = DisplayParams.SCALE_INTEGER;
        g = Geometry.compute(p, 1920, 1080, 320, 240);
        assertEquals(1280f, g.width(), 0.5f); // 4x
        assertEquals(960f, g.height(), 0.5f);

        p.scaleMode = DisplayParams.SCALE_FIT;
        p.rotation = 90;
        g = Geometry.compute(p, 1080, 1920, 1920, 1080); // rotated 16:9 fills a portrait screen
        assertEquals(1080f, g.width(), 0.5f);
        assertEquals(1920f, g.height(), 0.5f);
        // screen top-left shows the image's bottom-left after a 90° clockwise turn
        assertEquals(0f, g.uv[0], 1e-6f);
        assertEquals(1f, g.uv[1], 1e-6f);

        p.rotation = 0;
        p.mirror = true;
        g = Geometry.compute(p, 1920, 1080, 1920, 1080);
        assertEquals(1f, g.uv[0], 1e-6f);

        int[] cap = Geometry.captureSize(new DisplayParams(), 1919, 1081);
        assertEquals(0, cap[0] % 2);
        assertEquals(0, cap[1] % 2);
    }
}
