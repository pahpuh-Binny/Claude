package io.github.pahpuhbinny.usbcapture.uvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the app learns from a device's raw USB descriptors: the
 * video control interface and its units (for picture controls), every
 * video streaming interface with its formats, frame sizes and frame
 * rates, and the alternate settings that define the available USB
 * bandwidth.
 */
public final class UvcDeviceInfo {
    public int vendorId;
    public int productId;
    public int bcdUSB;
    public int bcdUVC = 0x0100;
    public int vcInterface = -1;
    public boolean superSpeed;           // the configuration carries SuperSpeed endpoint companions
    public final List<Unit> units = new ArrayList<>();
    public final List<StreamInterface> streams = new ArrayList<>();
    public final List<Integer> audioInterfaces = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public static final int UNIT_CAMERA = 1;
    public static final int UNIT_PROCESSING = 2;
    public static final int UNIT_EXTENSION = 3;
    public static final int UNIT_INPUT = 4;
    public static final int UNIT_OUTPUT = 5;
    public static final int UNIT_SELECTOR = 6;

    public static final class Unit {
        public int kind;
        public int id;
        public int terminalType;
        public long controls;      // bmControls bitmap (up to 64 bits)
        public int controlSize;
        public String guid;

        public String describe() {
            String k;
            switch (kind) {
                case UNIT_CAMERA: k = "Camera terminal"; break;
                case UNIT_PROCESSING: k = "Processing unit"; break;
                case UNIT_EXTENSION: k = "Extension unit " + guid; break;
                case UNIT_INPUT: k = String.format(Locale.US, "Input terminal type 0x%04x", terminalType); break;
                case UNIT_OUTPUT: k = String.format(Locale.US, "Output terminal type 0x%04x", terminalType); break;
                case UNIT_SELECTOR: k = "Selector unit"; break;
                default: k = "Unit";
            }
            return k + " #" + id + (controlSize > 0 ? String.format(Locale.US, " controls=0x%x", controls) : "");
        }
    }

    public static final class AltSetting {
        public int alt;
        public int endpoint = -1;       // bEndpointAddress
        public int attributes;          // bmAttributes
        public int wMaxPacketSize;
        public int interval;
        public int maxBurst;            // SuperSpeed companion
        public int ssMult;              // SuperSpeed companion (iso)
        public int bytesPerInterval;    // SuperSpeed companion
        public boolean hasCompanion;

        public boolean isIso() {
            return (attributes & 0x03) == 1;
        }

        public boolean isBulk() {
            return (attributes & 0x03) == 2;
        }

        /** Bytes the endpoint can move per service interval. */
        public int payloadBytes() {
            if (endpoint < 0) return 0;
            if (hasCompanion) {
                int base = (wMaxPacketSize & 0x7ff) * (maxBurst + 1) * (isIso() ? (ssMult + 1) : 1);
                if (bytesPerInterval > 0 && isIso()) return Math.min(base, Math.max(bytesPerInterval, 1));
                return base;
            }
            int size = wMaxPacketSize & 0x7ff;
            int mult = ((wMaxPacketSize >> 11) & 0x3) + 1;
            return size * mult;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "alt %d: ep 0x%02x %s %d bytes/interval (wMaxPacketSize 0x%04x%s)",
                    alt, endpoint, isIso() ? "iso" : isBulk() ? "bulk" : "other", payloadBytes(), wMaxPacketSize,
                    hasCompanion ? String.format(Locale.US, ", burst %d mult %d bpi %d", maxBurst, ssMult, bytesPerInterval) : "");
        }
    }

    public static final class Frame {
        public int index;
        public int width;
        public int height;
        public long maxFrameSize;
        public int defaultInterval;           // 100 ns units
        public int[] intervals = new int[0];  // discrete intervals
        public boolean continuous;
        public int minInterval;
        public int maxInterval;
        public int stepInterval;
        public int bytesPerLine;

        /** Discrete list of frame intervals to offer (continuous ranges are sampled). */
        public int[] offeredIntervals() {
            if (!continuous) return intervals;
            List<Integer> out = new ArrayList<>();
            int[] common = {166666, 166833, 200000, 333333, 333667, 400000, 500000, 666666, 1000000, 2000000,
                    83333, 69444, 41666, 40000, 50000};
            for (int c : common) {
                if (c >= minInterval && c <= maxInterval) out.add(c);
            }
            if (!out.contains(minInterval)) out.add(minInterval);
            if (!out.contains(maxInterval)) out.add(maxInterval);
            int[] r = new int[out.size()];
            for (int i = 0; i < r.length; i++) r[i] = out.get(i);
            java.util.Arrays.sort(r);
            return r;
        }
    }

    public static final class Format {
        public int index;
        public int subtype;          // VS_FORMAT_* descriptor subtype
        public int pixelFormat;
        public String fourcc = "";
        public int bitsPerPixel;
        public int defaultFrame;
        public final List<Frame> frames = new ArrayList<>();
        public int colorPrimaries;
        public int transfer;
        public int matrix;           // 1 = BT.709, 4/5 = BT.601, 0 = unspecified

        public boolean isMjpeg() {
            return pixelFormat == PixelFormat.MJPEG;
        }

        public int nativeFormatKind() {
            if (pixelFormat == PixelFormat.MJPEG) return UvcNative.FMT_MJPEG;
            if (subtype == 0x10) return UvcNative.FMT_FRAME_BASED;
            return UvcNative.FMT_UNCOMPRESSED;
        }
    }

    public static final class StreamInterface {
        public int ifnum;
        public int endpointAddress;
        public int terminalLink;
        public final List<Format> formats = new ArrayList<>();
        public final List<AltSetting> alts = new ArrayList<>();
        int lastColorMatrix;

        public boolean isBulk() {
            for (AltSetting a : alts) {
                if (a.alt == 0 && a.endpoint >= 0 && a.isBulk()) return true;
            }
            return false;
        }

        public AltSetting bulkAlt() {
            for (AltSetting a : alts) {
                if (a.endpoint >= 0 && a.isBulk()) return a;
            }
            return null;
        }

        public int maxIsoBytes() {
            int m = 0;
            for (AltSetting a : alts) if (a.isIso()) m = Math.max(m, a.payloadBytes());
            return m;
        }
    }

    public Unit unit(int kind) {
        for (Unit u : units) if (u.kind == kind) return u;
        return null;
    }

    // ------------------------------------------------------------------
    // Parsing

    private static int u8(byte[] d, int o) {
        return o < d.length ? d[o] & 0xff : 0;
    }

    private static int u16(byte[] d, int o) {
        return u8(d, o) | (u8(d, o + 1) << 8);
    }

    private static long u32(byte[] d, int o) {
        return (u16(d, o) & 0xffffL) | ((long) u16(d, o + 2) << 16);
    }

    private static int i32(byte[] d, int o) {
        return (int) u32(d, o);
    }

    private static long bitmap(byte[] d, int o, int size, int limit) {
        long v = 0;
        for (int i = 0; i < size && i < 8 && o + i < limit; i++) v |= ((long) u8(d, o + i)) << (8 * i);
        return v;
    }

    private static String guidString(byte[] d, int o) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(String.format(Locale.US, "%02x", u8(d, o + i)));
        return sb.toString();
    }

    public static UvcDeviceInfo parse(byte[] raw) {
        UvcDeviceInfo info = new UvcDeviceInfo();
        if (raw == null) {
            info.warnings.add("No descriptors");
            return info;
        }
        int pos = 0;
        int configs = 0;
        int curIf = -1, curAlt = 0, curClass = -1, curSub = -1;
        StreamInterface curStream = null;
        AltSetting lastAlt = null;
        Format curFormat = null;

        while (pos + 2 <= raw.length) {
            int len = u8(raw, pos);
            int type = u8(raw, pos + 1);
            if (len < 2) {
                info.warnings.add("Malformed descriptor at offset " + pos);
                break;
            }
            int end = Math.min(raw.length, pos + len);
            if (pos + len > raw.length) info.warnings.add("Truncated descriptor at offset " + pos);

            switch (type) {
                case 0x01: // DEVICE
                    info.bcdUSB = u16(raw, pos + 2);
                    info.vendorId = u16(raw, pos + 8);
                    info.productId = u16(raw, pos + 10);
                    break;
                case 0x02: // CONFIGURATION
                    configs++;
                    break;
                case 0x04: // INTERFACE
                    if (configs > 1) break;
                    curIf = u8(raw, pos + 2);
                    curAlt = u8(raw, pos + 3);
                    curClass = u8(raw, pos + 5);
                    curSub = u8(raw, pos + 6);
                    lastAlt = null;
                    if (curClass == 0x0E && curSub == 0x01 && info.vcInterface < 0) {
                        info.vcInterface = curIf;
                    } else if (curClass == 0x0E && curSub == 0x02) {
                        curStream = findStream(info, curIf);
                        if (curStream == null) {
                            curStream = new StreamInterface();
                            curStream.ifnum = curIf;
                            info.streams.add(curStream);
                        }
                        AltSetting a = new AltSetting();
                        a.alt = curAlt;
                        curStream.alts.add(a);
                        lastAlt = a;
                    } else if (curClass == 0x01 && curSub == 0x02 && curAlt == 0) {
                        if (!info.audioInterfaces.contains(curIf)) info.audioInterfaces.add(curIf);
                    }
                    break;
                case 0x05: // ENDPOINT
                    if (configs > 1) break;
                    if (curClass == 0x0E && curSub == 0x02 && lastAlt != null && lastAlt.endpoint < 0) {
                        int addr = u8(raw, pos + 2);
                        if ((addr & 0x80) != 0) {
                            lastAlt.endpoint = addr;
                            lastAlt.attributes = u8(raw, pos + 3);
                            lastAlt.wMaxPacketSize = u16(raw, pos + 4);
                            lastAlt.interval = u8(raw, pos + 6);
                        }
                    }
                    break;
                case 0x30: // SUPERSPEED ENDPOINT COMPANION
                    info.superSpeed = true;
                    if (lastAlt != null && lastAlt.endpoint >= 0 && !lastAlt.hasCompanion) {
                        lastAlt.hasCompanion = true;
                        lastAlt.maxBurst = u8(raw, pos + 2);
                        lastAlt.ssMult = u8(raw, pos + 3) & 0x3;
                        lastAlt.bytesPerInterval = u16(raw, pos + 4);
                    }
                    break;
                case 0x24: // CS_INTERFACE
                    if (configs > 1) break;
                    if (curClass == 0x0E && curSub == 0x01) {
                        parseVc(info, raw, pos, end);
                    } else if (curClass == 0x0E && curSub == 0x02 && curStream != null && curAlt == 0) {
                        Format f = parseVs(info, curStream, curFormat, raw, pos, end);
                        if (f != null) curFormat = f;
                    }
                    break;
                default:
                    break;
            }
            pos += len;
        }
        if (info.vcInterface < 0) info.warnings.add("No video control interface found");
        if (info.streams.isEmpty()) info.warnings.add("No video streaming interface found");
        return info;
    }

    private static StreamInterface findStream(UvcDeviceInfo info, int ifnum) {
        for (StreamInterface s : info.streams) if (s.ifnum == ifnum) return s;
        return null;
    }

    private static void parseVc(UvcDeviceInfo info, byte[] d, int p, int end) {
        int sub = u8(d, p + 2);
        switch (sub) {
            case 0x01: // VC_HEADER
                info.bcdUVC = u16(d, p + 3);
                break;
            case 0x02: { // INPUT_TERMINAL
                Unit u = new Unit();
                u.id = u8(d, p + 3);
                u.terminalType = u16(d, p + 4);
                if (u.terminalType == 0x0201) {
                    u.kind = UNIT_CAMERA;
                    u.controlSize = u8(d, p + 14);
                    u.controls = bitmap(d, p + 15, u.controlSize, end);
                } else {
                    u.kind = UNIT_INPUT;
                }
                info.units.add(u);
                break;
            }
            case 0x03: { // OUTPUT_TERMINAL
                Unit u = new Unit();
                u.kind = UNIT_OUTPUT;
                u.id = u8(d, p + 3);
                u.terminalType = u16(d, p + 4);
                info.units.add(u);
                break;
            }
            case 0x04: { // SELECTOR_UNIT
                Unit u = new Unit();
                u.kind = UNIT_SELECTOR;
                u.id = u8(d, p + 3);
                info.units.add(u);
                break;
            }
            case 0x05: { // PROCESSING_UNIT
                Unit u = new Unit();
                u.kind = UNIT_PROCESSING;
                u.id = u8(d, p + 3);
                u.controlSize = u8(d, p + 7);
                u.controls = bitmap(d, p + 8, u.controlSize, end);
                info.units.add(u);
                break;
            }
            case 0x06: { // EXTENSION_UNIT
                Unit u = new Unit();
                u.kind = UNIT_EXTENSION;
                u.id = u8(d, p + 3);
                u.guid = guidString(d, p + 4);
                int nrInPins = u8(d, p + 21);
                int cs = p + 22 + nrInPins;
                u.controlSize = u8(d, cs);
                u.controls = bitmap(d, cs + 1, u.controlSize, end);
                info.units.add(u);
                break;
            }
            default:
                break;
        }
    }

    private static Format parseVs(UvcDeviceInfo info, StreamInterface s, Format cur, byte[] d, int p, int end) {
        int sub = u8(d, p + 2);
        switch (sub) {
            case 0x01: // VS_INPUT_HEADER
                s.endpointAddress = u8(d, p + 6);
                s.terminalLink = u8(d, p + 8);
                return null;
            case 0x04: // FORMAT_UNCOMPRESSED
            case 0x10: { // FORMAT_FRAME_BASED
                Format f = new Format();
                f.subtype = sub;
                f.index = u8(d, p + 3);
                byte[] guid = new byte[16];
                for (int i = 0; i < 16; i++) guid[i] = (byte) u8(d, p + 5 + i);
                f.fourcc = PixelFormat.fourcc(guid);
                f.pixelFormat = PixelFormat.fromGuid(guid);
                f.bitsPerPixel = u8(d, p + 21);
                f.defaultFrame = u8(d, p + 22);
                s.formats.add(f);
                return f;
            }
            case 0x06: { // FORMAT_MJPEG
                Format f = new Format();
                f.subtype = sub;
                f.index = u8(d, p + 3);
                f.fourcc = "MJPG";
                f.pixelFormat = PixelFormat.MJPEG;
                f.defaultFrame = u8(d, p + 6);
                s.formats.add(f);
                return f;
            }
            case 0x05: // FRAME_UNCOMPRESSED
            case 0x07: // FRAME_MJPEG
            case 0x11: { // FRAME_FRAME_BASED
                if (cur == null) {
                    info.warnings.add("Frame descriptor without a format");
                    return null;
                }
                Frame fr = new Frame();
                fr.index = u8(d, p + 3);
                fr.width = u16(d, p + 5);
                fr.height = u16(d, p + 7);
                int ivType;
                int ivPos;
                if (sub == 0x11) {
                    fr.defaultInterval = i32(d, p + 17);
                    ivType = u8(d, p + 21);
                    fr.bytesPerLine = i32(d, p + 22);
                    ivPos = p + 26;
                } else {
                    fr.maxFrameSize = u32(d, p + 17);
                    fr.defaultInterval = i32(d, p + 21);
                    ivType = u8(d, p + 25);
                    ivPos = p + 26;
                }
                if (ivType == 0) {
                    fr.continuous = true;
                    fr.minInterval = i32(d, ivPos);
                    fr.maxInterval = i32(d, ivPos + 4);
                    fr.stepInterval = i32(d, ivPos + 8);
                    if (fr.minInterval <= 0) fr.minInterval = fr.defaultInterval > 0 ? fr.defaultInterval : 333333;
                    if (fr.maxInterval < fr.minInterval) fr.maxInterval = fr.minInterval;
                } else {
                    List<Integer> ivs = new ArrayList<>();
                    for (int i = 0; i < ivType && ivPos + 4 * i + 4 <= end; i++) {
                        int iv = i32(d, ivPos + 4 * i);
                        if (iv > 0 && !ivs.contains(iv)) ivs.add(iv);
                    }
                    if (ivs.isEmpty() && fr.defaultInterval > 0) ivs.add(fr.defaultInterval);
                    fr.intervals = new int[ivs.size()];
                    for (int i = 0; i < ivs.size(); i++) fr.intervals[i] = ivs.get(i);
                    java.util.Arrays.sort(fr.intervals);
                }
                if (fr.width > 0 && fr.height > 0) cur.frames.add(fr);
                return null;
            }
            case 0x0D: // COLORFORMAT (applies to the preceding format)
                if (cur != null) {
                    cur.colorPrimaries = u8(d, p + 3);
                    cur.transfer = u8(d, p + 4);
                    cur.matrix = u8(d, p + 5);
                }
                return null;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "USB %04x:%04x, bcdUSB %x.%02x, UVC %x.%02x, %s descriptors\n",
                vendorId, productId, bcdUSB >> 8, bcdUSB & 0xff, bcdUVC >> 8, bcdUVC & 0xff,
                superSpeed ? "SuperSpeed" : "High/Full-speed"));
        sb.append("Video control interface: ").append(vcInterface).append('\n');
        for (Unit u : units) sb.append("  ").append(u.describe()).append('\n');
        for (StreamInterface s : streams) {
            sb.append("Streaming interface ").append(s.ifnum).append(s.isBulk() ? " (bulk)" : " (isochronous)")
                    .append(String.format(Locale.US, ", endpoint 0x%02x\n", s.endpointAddress));
            for (AltSetting a : s.alts) sb.append("  ").append(a).append('\n');
            for (Format f : s.formats) {
                sb.append(String.format(Locale.US, "  Format %d: %s [%s]%s%s\n", f.index, PixelFormat.name(f.pixelFormat),
                        f.fourcc, PixelFormat.isSupported(f.pixelFormat) ? "" : " (unsupported)",
                        f.matrix != 0 ? " matrix=" + f.matrix : ""));
                for (Frame fr : f.frames) {
                    sb.append(String.format(Locale.US, "    #%d %dx%d max %d bytes: ", fr.index, fr.width, fr.height, fr.maxFrameSize));
                    if (fr.continuous) {
                        sb.append(String.format(Locale.US, "%.2f-%.2f fps (continuous)",
                                1e7 / Math.max(1, fr.maxInterval), 1e7 / Math.max(1, fr.minInterval)));
                    } else {
                        for (int iv : fr.intervals) sb.append(String.format(Locale.US, "%.2f ", 1e7 / iv));
                        sb.append("fps");
                    }
                    sb.append('\n');
                }
            }
        }
        if (!audioInterfaces.isEmpty()) sb.append("Audio streaming interfaces: ").append(audioInterfaces).append('\n');
        for (String w : warnings) sb.append("Warning: ").append(w).append('\n');
        return sb.toString();
    }
}
