package io.github.pahpuhbinny.usbcapture.uvc;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/**
 * An opened UVC device: claims its video interfaces, negotiates video
 * modes with the probe/commit protocol, picks the USB bandwidth setting
 * and starts/stops the native streaming engine.
 */
public final class UvcCamera {
    private static final String TAG = "UvcCamera";

    public static final int REQ_SET_CUR = 0x01;
    public static final int REQ_GET_CUR = 0x81;
    public static final int REQ_GET_MIN = 0x82;
    public static final int REQ_GET_MAX = 0x83;
    public static final int REQ_GET_RES = 0x84;
    public static final int REQ_GET_LEN = 0x85;
    public static final int REQ_GET_INFO = 0x86;
    public static final int REQ_GET_DEF = 0x87;

    private static final int VS_PROBE = 0x01;
    private static final int VS_COMMIT = 0x02;

    public static final int SPEED_LOW = 1, SPEED_FULL = 2, SPEED_HIGH = 3, SPEED_SUPER = 5, SPEED_SUPER_PLUS = 6;

    /** Knobs for the USB transfer queue (0 = automatic). */
    public static final class Tuning {
        public int numUrbs;
        public int packetsPerUrb;
        public boolean maxBandwidth;
        public boolean strictMjpeg = true;
        public boolean tolerateErrors;
        public int consumers = 1;
    }

    /** What the device agreed to during negotiation. */
    public static final class Negotiated {
        public int formatIndex, frameIndex, interval;
        public long maxFrameSize, maxPayload;
        public int probeLength;
        public int altSetting;
        public int packetSize;
        public int packetsPerUrb;
        public int numUrbs;
        public boolean bulk;
        public int endpoint;

        public String describe() {
            return String.format(Locale.US,
                    "format %d frame %d interval %d (%.2f fps), max frame %d B, max payload %d B, %s, alt %d, %s",
                    formatIndex, frameIndex, interval, interval > 0 ? 1e7 / interval : 0, maxFrameSize, maxPayload,
                    bulk ? "bulk" : "isochronous", altSetting,
                    bulk ? numUrbs + " x " + packetSize + " B transfers"
                            : numUrbs + " transfers x " + packetsPerUrb + " packets x " + packetSize + " B");
        }
    }

    public static final class UvcException extends Exception {
        public final boolean bandwidth;

        public UvcException(String msg) {
            this(msg, false);
        }

        public UvcException(String msg, boolean bandwidth) {
            super(msg);
            this.bandwidth = bandwidth;
        }
    }

    public final UsbDevice device;
    public final UsbDeviceConnection conn;
    public final int fd;
    public final UvcDeviceInfo info;
    public final int usbSpeed;
    private final List<UsbInterface> claimed = new ArrayList<>();
    private UvcDeviceInfo.StreamInterface activeStream;
    private NativeStream activeNative;
    private Negotiated negotiated;
    private UvcControls controls;
    private boolean closed;

    private UvcCamera(UsbDevice device, UsbDeviceConnection conn, UvcDeviceInfo info) {
        this.device = device;
        this.conn = conn;
        this.fd = conn.getFileDescriptor();
        this.info = info;
        int sp = UvcNative.AVAILABLE ? UvcNative.nativeGetSpeed(fd) : -1;
        if (sp <= 0) {
            sp = info.superSpeed ? SPEED_SUPER : (info.bcdUSB >= 0x0200 ? SPEED_HIGH : SPEED_FULL);
            AppLog.d(TAG, "USB speed query unavailable; inferred " + speedName(sp));
        }
        this.usbSpeed = sp;
    }

    public static UvcCamera open(UsbManager um, UsbDevice dev) throws UvcException {
        if (!UvcNative.AVAILABLE) {
            throw new UvcException("The app's USB video engine could not load: " + UvcNative.loadError());
        }
        UsbDeviceConnection c;
        try {
            c = um.openDevice(dev);
        } catch (SecurityException e) {
            throw new UvcException("Android did not give the app permission to use this device");
        }
        if (c == null) throw new UvcException("Android could not open the USB device (is another app using it?)");
        byte[] raw = c.getRawDescriptors();
        UvcDeviceInfo info = UvcDeviceInfo.parse(raw);
        UvcCamera cam = new UvcCamera(dev, c, info);
        AppLog.i(TAG, "Opened " + describeDevice(dev) + " on " + speedName(cam.usbSpeed) + "\n" + info.describe());
        if (info.vcInterface < 0 || info.streams.isEmpty()) {
            cam.close();
            throw new UvcException("This USB device does not look like a USB video (UVC) camera or capture card");
        }
        try {
            cam.claimInterfaces();
        } catch (UvcException e) {
            cam.close();
            throw e;
        }
        return cam;
    }

    public static String describeDevice(UsbDevice d) {
        String name = d.getProductName();
        String mfr = d.getManufacturerName();
        return String.format(Locale.US, "%s%s (%04x:%04x)", mfr != null ? mfr + " " : "",
                name != null ? name : "USB video device", d.getVendorId(), d.getProductId());
    }

    public static String speedName(int sp) {
        switch (sp) {
            case SPEED_LOW: return "USB 1.x Low-Speed";
            case SPEED_FULL: return "USB 1.1 Full-Speed (12 Mbps)";
            case SPEED_HIGH: return "USB 2.0 High-Speed (480 Mbps)";
            case SPEED_SUPER: return "USB 3 SuperSpeed (5 Gbps)";
            case SPEED_SUPER_PLUS: return "USB 3 SuperSpeed+ (10 Gbps+)";
            default: return "USB (unknown speed)";
        }
    }

    public String speedShort() {
        switch (usbSpeed) {
            case SPEED_LOW:
            case SPEED_FULL: return "USB 1.1";
            case SPEED_HIGH: return "USB 2.0";
            case SPEED_SUPER: return "USB 3";
            case SPEED_SUPER_PLUS: return "USB 3+";
            default: return "USB";
        }
    }

    /** Approximate usable payload bandwidth of the link in bytes/second. */
    public long linkBandwidth(UvcDeviceInfo.StreamInterface s) {
        if (s.isBulk()) {
            return usbSpeed >= SPEED_SUPER ? 380_000_000L : usbSpeed == SPEED_HIGH ? 40_000_000L : 1_000_000L;
        }
        long best = 0;
        for (UvcDeviceInfo.AltSetting a : s.alts) {
            if (!a.isIso()) continue;
            long perSec;
            if (usbSpeed >= SPEED_HIGH) {
                int iv = Math.max(1, Math.min(16, a.interval));
                perSec = (long) a.payloadBytes() * 8000L / (1L << (iv - 1));
            } else {
                perSec = (long) a.payloadBytes() * 1000L;
            }
            best = Math.max(best, perSec);
        }
        return best;
    }

    private void claimInterfaces() throws UvcException {
        List<Integer> wanted = new ArrayList<>();
        wanted.add(info.vcInterface);
        for (UvcDeviceInfo.StreamInterface s : info.streams) wanted.add(s.ifnum);
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getAlternateSetting() != 0) continue;
            if (!wanted.contains(intf.getId())) continue;
            boolean ok = conn.claimInterface(intf, true);
            if (!ok) {
                SystemClock.sleep(100);
                ok = conn.claimInterface(intf, true);
            }
            if (!ok) {
                throw new UvcException("Could not take control of USB interface " + intf.getId()
                        + ". Close other camera apps and reconnect the device.");
            }
            claimed.add(intf);
        }
        AppLog.d(TAG, "Claimed " + claimed.size() + " interfaces");
    }

    public synchronized UvcControls controls() {
        if (controls == null && !closed) controls = UvcControls.probe(this);
        return controls;
    }

    public synchronized Negotiated negotiated() {
        return negotiated;
    }

    // ------------------------------------------------------------------
    // Control transfers

    /** Class-specific GET request to an interface/entity. Returns bytes read or negative. */
    public int get(int req, int selector, int index, byte[] buf, int len) {
        if (closed) return -1;
        int r = -1;
        for (int attempt = 0; attempt < 2; attempt++) {
            r = conn.controlTransfer(0xA1, req, selector << 8, index, buf, len, 1000);
            if (r >= 0) return r;
            SystemClock.sleep(5);
        }
        return r;
    }

    public int set(int selector, int index, byte[] buf, int len) {
        if (closed) return -1;
        int r = -1;
        for (int attempt = 0; attempt < 2; attempt++) {
            r = conn.controlTransfer(0x21, REQ_SET_CUR, selector << 8, index, buf, len, 1000);
            if (r >= 0) return r;
            SystemClock.sleep(5);
        }
        return r;
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static long get32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private int[] probeLengths() {
        if (info.bcdUVC >= 0x0150) return new int[]{48, 34, 26};
        if (info.bcdUVC >= 0x0110) return new int[]{34, 26, 48};
        return new int[]{26, 34};
    }

    private Negotiated negotiate(UvcDeviceInfo.StreamInterface s, VideoMode m) throws UvcException {
        StringBuilder errors = new StringBuilder();
        for (int len : probeLengths()) {
            byte[] p = new byte[len];
            p[0] = 1; // bmHint: keep dwFrameInterval
            p[2] = (byte) m.formatIndex;
            p[3] = (byte) m.frameIndex;
            put32(p, 4, m.interval);
            int r = set(VS_PROBE, s.ifnum, p, len);
            if (r < 0) {
                errors.append(" SET_CUR(probe,").append(len).append(")=").append(r);
                continue;
            }
            byte[] q = new byte[len];
            r = get(REQ_GET_CUR, VS_PROBE, s.ifnum, q, len);
            if (r < 26) {
                errors.append(" GET_CUR(probe,").append(len).append(")=").append(r);
                continue;
            }
            Negotiated n = new Negotiated();
            n.formatIndex = q[2] & 0xff;
            n.frameIndex = q[3] & 0xff;
            n.interval = (int) get32(q, 4);
            n.maxFrameSize = get32(q, 18);
            n.maxPayload = get32(q, 22);
            n.probeLength = len;
            if (n.formatIndex != m.formatIndex || n.frameIndex != m.frameIndex) {
                AppLog.w(TAG, "Device changed the request: wanted format " + m.formatIndex + "/frame " + m.frameIndex
                        + ", got " + n.formatIndex + "/" + n.frameIndex);
                if (n.formatIndex == 0 || n.frameIndex == 0) {
                    // Some devices answer zeros; trust our own request.
                    n.formatIndex = m.formatIndex;
                    n.frameIndex = m.frameIndex;
                    q[2] = (byte) m.formatIndex;
                    q[3] = (byte) m.frameIndex;
                }
            }
            if (n.interval == 0) {
                n.interval = m.interval;
                put32(q, 4, m.interval);
            }
            r = set(VS_COMMIT, s.ifnum, q, len);
            if (r < 0) {
                errors.append(" SET_CUR(commit,").append(len).append(")=").append(r);
                continue;
            }
            return n;
        }
        throw new UvcException("The device refused this video mode (" + m.label() + "). Details:" + errors);
    }

    // ------------------------------------------------------------------
    // Streaming

    public synchronized NativeStream startStream(VideoMode m, Tuning t) throws UvcException {
        if (closed) throw new UvcException("Device is closed");
        if (activeNative != null) stopStream();
        if (m.streamIndex < 0 || m.streamIndex >= info.streams.size()) throw new UvcException("Bad stream index");
        UvcDeviceInfo.StreamInterface s = info.streams.get(m.streamIndex);
        UvcDeviceInfo.Format fmt = null;
        for (UvcDeviceInfo.Format f : s.formats) if (f.index == m.formatIndex) fmt = f;
        if (fmt == null) throw new UvcException("Unknown format");

        int r = UvcNative.nativeSetInterface(fd, s.ifnum, 0);
        if (r < 0) AppLog.w(TAG, "SET_INTERFACE(" + s.ifnum + ", 0) failed: " + UvcNative.strerror(r));

        Negotiated n = negotiate(s, m);

        long expected = 0;
        if (fmt.nativeFormatKind() == UvcNative.FMT_UNCOMPRESSED) {
            expected = PixelFormat.frameBytes(m.pixelFormat, m.width, m.height);
        }
        long rawEstimate = (long) m.width * m.height * 2;
        long initialCap;
        long maxCap;
        if (expected > 0) {
            initialCap = expected + 4096;
            maxCap = expected * 2 + 65536;
        } else {
            long hint = Math.max(n.maxFrameSize, m.maxFrameSize);
            if (hint <= 0 || hint > rawEstimate * 2) hint = rawEstimate / 2;
            initialCap = Math.max(256 * 1024, Math.min(hint + 65536, rawEstimate));
            maxCap = Math.max(rawEstimate * 2, 16L * 1024 * 1024);
        }
        int numBufs = Math.max(4, t.consumers + 3);
        if (initialCap > 12L * 1024 * 1024) numBufs = Math.min(numBufs, 5);

        long[] cfg = new long[13];
        cfg[6] = fmt.nativeFormatKind();
        cfg[7] = expected;
        cfg[8] = initialCap;
        cfg[9] = maxCap;
        cfg[10] = numBufs;
        cfg[11] = t.strictMjpeg ? 1 : 0;
        cfg[12] = t.tolerateErrors ? 1 : 0;

        if (s.isBulk()) {
            UvcDeviceInfo.AltSetting a = s.bulkAlt();
            if (a == null) throw new UvcException("Bulk streaming endpoint not found");
            long urb = n.maxPayload;
            if (urb <= 0 || urb > 32L * 1024 * 1024) {
                AppLog.w(TAG, "Device reported an unusable payload size (" + urb + "); using 512 KiB transfers");
                urb = 512 * 1024;
            }
            int count;
            if (t.numUrbs > 0) count = t.numUrbs;
            else if (urb <= 64 * 1024) count = 16;
            else if (urb <= 512 * 1024) count = 8;
            else count = (int) Math.max(2, Math.min(4, (12L * 1024 * 1024) / urb));
            n.bulk = true;
            n.altSetting = 0;
            n.packetSize = (int) urb;
            n.numUrbs = count;
            n.endpoint = a.endpoint;
            UvcNative.nativeClearHalt(fd, a.endpoint);
            cfg[0] = a.endpoint;
            cfg[1] = UvcNative.XFER_BULK;
            cfg[4] = urb;
            cfg[5] = count;
        } else {
            List<UvcDeviceInfo.AltSetting> isoAlts = new ArrayList<>();
            for (UvcDeviceInfo.AltSetting a : s.alts) if (a.alt > 0 && a.endpoint >= 0 && a.isIso()) isoAlts.add(a);
            if (isoAlts.isEmpty()) throw new UvcException("No isochronous streaming settings found");
            isoAlts.sort((x, y) -> Integer.compare(x.payloadBytes(), y.payloadBytes()));
            UvcDeviceInfo.AltSetting chosen = isoAlts.get(isoAlts.size() - 1);
            if (!t.maxBandwidth && n.maxPayload > 0) {
                for (UvcDeviceInfo.AltSetting a : isoAlts) {
                    if (a.payloadBytes() >= n.maxPayload) {
                        chosen = a;
                        break;
                    }
                }
            }
            if (n.maxPayload > chosen.payloadBytes()) {
                AppLog.w(TAG, "Device wants " + n.maxPayload + " bytes per packet but the largest setting allows "
                        + chosen.payloadBytes() + "; frames may be incomplete");
            }
            int pkt = chosen.payloadBytes();
            int ppu = t.packetsPerUrb > 0 ? t.packetsPerUrb
                    : usbSpeed >= SPEED_SUPER ? 16 : usbSpeed == SPEED_HIGH ? 32 : 8;
            int count = t.numUrbs > 0 ? t.numUrbs : usbSpeed >= SPEED_SUPER ? 12 : 16;
            long budget = 12L * 1024 * 1024;
            while (count > 4 && (long) count * ppu * pkt > budget) count--;
            while (ppu > 4 && (long) count * ppu * pkt > budget) ppu /= 2;
            n.bulk = false;
            n.altSetting = chosen.alt;
            n.packetSize = pkt;
            n.packetsPerUrb = ppu;
            n.numUrbs = count;
            n.endpoint = chosen.endpoint;
            r = UvcNative.nativeSetInterface(fd, s.ifnum, chosen.alt);
            if (r < 0) {
                throw new UvcException("The phone could not reserve USB bandwidth for this mode ("
                        + UvcNative.strerror(r) + "). Try a lower resolution or frame rate.", true);
            }
            cfg[0] = chosen.endpoint;
            cfg[1] = UvcNative.XFER_ISO;
            cfg[2] = pkt;
            cfg[3] = ppu;
            cfg[5] = count;
        }

        NativeStream ns = null;
        int attempt = 0;
        while (true) {
            ns = NativeStream.create(fd, cfg);
            if (ns == null) {
                idle(s);
                throw new UvcException("Not enough memory for video buffers at " + m.resolutionLabel());
            }
            r = ns.start();
            if (r == 0) break;
            drainNative(ns);
            ns.destroy();
            attempt++;
            boolean retryable = (r == -12 /*ENOMEM*/ || r == -22 /*EINVAL*/ || r == -28 /*ENOSPC*/ || r == -27 /*EFBIG*/);
            if (!retryable || attempt > 3) {
                idle(s);
                if (r == -19 /*ENODEV*/) throw new UvcException("The device was disconnected");
                throw new UvcException("Could not start streaming: " + UvcNative.strerror(r) + " (" + r + ")",
                        r == -28);
            }
            // Shrink the transfer queue and try again.
            if (cfg[1] == UvcNative.XFER_ISO) {
                cfg[3] = Math.max(2, cfg[3] / 2);
                cfg[5] = Math.max(2, cfg[5] - 4);
                n.packetsPerUrb = (int) cfg[3];
                n.numUrbs = (int) cfg[5];
            } else {
                cfg[5] = Math.max(2, cfg[5] / 2);
                n.numUrbs = (int) cfg[5];
            }
            AppLog.w(TAG, "Start failed with " + UvcNative.strerror(r) + "; retrying with " + n.numUrbs + " transfers");
        }
        drainNative(ns);
        activeStream = s;
        activeNative = ns;
        negotiated = n;
        AppLog.i(TAG, "Streaming " + m.label() + ": " + n.describe());
        return ns;
    }

    private void idle(UvcDeviceInfo.StreamInterface s) {
        if (s.isBulk()) {
            UvcDeviceInfo.AltSetting a = s.bulkAlt();
            if (a != null) UvcNative.nativeClearHalt(fd, a.endpoint);
        } else {
            UvcNative.nativeSetInterface(fd, s.ifnum, 0);
        }
    }

    public static void drainNative(NativeStream ns) {
        String[] lines = ns.drainLog();
        if (lines != null) for (String l : lines) AppLog.i("Native", l);
    }

    /** Stops USB transfers and frees the native stream. Consumers must have stopped first. */
    public synchronized void stopStream() {
        NativeStream ns = activeNative;
        activeNative = null;
        if (ns != null) {
            ns.stop();
            drainNative(ns);
        }
        if (activeStream != null && !closed) idle(activeStream);
        activeStream = null;
        if (ns != null) ns.destroy();
    }

    public synchronized void close() {
        if (closed) return;
        stopStream();
        closed = true;
        for (UsbInterface i : claimed) {
            try {
                conn.releaseInterface(i);
            } catch (Throwable ignored) {
            }
        }
        claimed.clear();
        try {
            conn.close();
        } catch (Throwable ignored) {
        }
        AppLog.i(TAG, "Closed " + describeDevice(device));
    }

    public boolean isClosed() {
        return closed;
    }
}
