package io.github.pahpuhbinny.usbcapture.uvc;

import java.util.ArrayList;
import java.util.List;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/**
 * The picture controls a device exposes through its Camera Terminal and
 * Processing Unit (brightness, contrast, white balance, exposure, focus,
 * zoom, power-line frequency, ...). Only controls the device both
 * declares and answers are listed.
 */
public final class UvcControls {
    private static final String TAG = "UvcControls";

    public static final int KIND_SLIDER = 0;
    public static final int KIND_TOGGLE = 1;
    public static final int KIND_MENU = 2;
    public static final int KIND_AE_MODE = 3;
    public static final int KIND_PAN = 4;
    public static final int KIND_TILT = 5;

    public static final class Control {
        public final String key;
        public final String name;
        public final int unitId;
        public final int selector;
        public final int length;
        public final boolean signed;
        public final int kind;
        public final String[] menu;
        public final int[] menuValues;
        public int min, max, res = 1, def, cur;
        public int info;
        /** For auto/manual pairs: key of the auto control that overrides this one. */
        public String autoKey;

        Control(String key, String name, int unitId, int selector, int length, boolean signed, int kind,
                String[] menu, int[] menuValues) {
            this.key = key;
            this.name = name;
            this.unitId = unitId;
            this.selector = selector;
            this.length = length;
            this.signed = signed;
            this.kind = kind;
            this.menu = menu;
            this.menuValues = menuValues;
        }

        public boolean writable() {
            return (info & 0x02) != 0 || info == 0;
        }
    }

    private final UvcCamera cam;
    public final List<Control> list = new ArrayList<>();

    private UvcControls(UvcCamera cam) {
        this.cam = cam;
    }

    private static final String[] POWER_LINE = {"Off", "50 Hz", "60 Hz", "Auto"};
    private static final int[] POWER_LINE_V = {0, 1, 2, 3};
    private static final String[] AE_MODES = {"Manual", "Auto", "Shutter priority", "Aperture priority"};
    private static final int[] AE_MODES_V = {1, 2, 4, 8};

    static UvcControls probe(UvcCamera cam) {
        UvcControls c = new UvcControls(cam);
        UvcDeviceInfo info = cam.info;
        UvcDeviceInfo.Unit ct = info.unit(UvcDeviceInfo.UNIT_CAMERA);
        UvcDeviceInfo.Unit pu = info.unit(UvcDeviceInfo.UNIT_PROCESSING);
        List<Control> defs = new ArrayList<>();
        if (pu != null) {
            long b = pu.controls;
            int id = pu.id;
            if (bit(b, 12)) defs.add(new Control("wb_auto", "Auto white balance", id, 0x0B, 1, false, KIND_TOGGLE, null, null));
            if (bit(b, 6)) defs.add(withAuto(new Control("wb_temp", "White balance (K)", id, 0x0A, 2, false, KIND_SLIDER, null, null), "wb_auto"));
            if (bit(b, 0)) defs.add(new Control("brightness", "Brightness", id, 0x02, 2, true, KIND_SLIDER, null, null));
            if (bit(b, 18)) defs.add(new Control("contrast_auto", "Auto contrast", id, 0x13, 1, false, KIND_TOGGLE, null, null));
            if (bit(b, 1)) defs.add(withAuto(new Control("contrast", "Contrast", id, 0x03, 2, false, KIND_SLIDER, null, null), "contrast_auto"));
            if (bit(b, 11)) defs.add(new Control("hue_auto", "Auto hue", id, 0x10, 1, false, KIND_TOGGLE, null, null));
            if (bit(b, 2)) defs.add(withAuto(new Control("hue", "Hue", id, 0x06, 2, true, KIND_SLIDER, null, null), "hue_auto"));
            if (bit(b, 3)) defs.add(new Control("saturation", "Saturation", id, 0x07, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 4)) defs.add(new Control("sharpness", "Sharpness", id, 0x08, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 5)) defs.add(new Control("gamma", "Gamma", id, 0x09, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 9)) defs.add(new Control("gain", "Gain", id, 0x04, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 8)) defs.add(new Control("backlight", "Backlight compensation", id, 0x01, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 10)) defs.add(new Control("power_line", "Anti-flicker (power line)", id, 0x05, 1, false, KIND_MENU, POWER_LINE, POWER_LINE_V));
            if (bit(b, 14)) defs.add(new Control("digital_zoom", "Digital multiplier", id, 0x0E, 2, false, KIND_SLIDER, null, null));
        }
        if (ct != null) {
            long b = ct.controls;
            int id = ct.id;
            if (bit(b, 1)) defs.add(new Control("ae_mode", "Exposure mode", id, 0x02, 1, false, KIND_AE_MODE, AE_MODES, AE_MODES_V));
            if (bit(b, 2)) defs.add(new Control("ae_priority", "Allow lower frame rate in low light", id, 0x03, 1, false, KIND_TOGGLE, null, null));
            if (bit(b, 3)) defs.add(withAuto(new Control("exposure", "Exposure time (x100 µs)", id, 0x04, 4, false, KIND_SLIDER, null, null), "ae_mode"));
            if (bit(b, 17)) defs.add(new Control("focus_auto", "Autofocus", id, 0x08, 1, false, KIND_TOGGLE, null, null));
            if (bit(b, 5)) defs.add(withAuto(new Control("focus", "Focus", id, 0x06, 2, false, KIND_SLIDER, null, null), "focus_auto"));
            if (bit(b, 7)) defs.add(new Control("iris", "Iris", id, 0x09, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 9)) defs.add(new Control("zoom", "Zoom", id, 0x0B, 2, false, KIND_SLIDER, null, null));
            if (bit(b, 11)) {
                defs.add(new Control("pan", "Pan", id, 0x0D, 8, true, KIND_PAN, null, null));
                defs.add(new Control("tilt", "Tilt", id, 0x0D, 8, true, KIND_TILT, null, null));
            }
            if (bit(b, 13)) defs.add(new Control("roll", "Roll", id, 0x0F, 2, true, KIND_SLIDER, null, null));
            if (bit(b, 18)) defs.add(new Control("privacy", "Privacy shutter", id, 0x11, 1, false, KIND_TOGGLE, null, null));
        }
        for (Control ctl : defs) {
            try {
                if (c.query(ctl)) c.list.add(ctl);
            } catch (Throwable t) {
                AppLog.w(TAG, "Control " + ctl.name + " failed", t);
            }
        }
        StringBuilder sb = new StringBuilder("Picture controls: ");
        for (Control ctl : c.list) {
            sb.append(ctl.key).append('[').append(ctl.min).append("..").append(ctl.max).append(" def ")
                    .append(ctl.def).append(" cur ").append(ctl.cur).append("] ");
        }
        if (c.list.isEmpty()) sb.append("none");
        AppLog.i(TAG, sb.toString());
        return c;
    }

    private static Control withAuto(Control c, String autoKey) {
        c.autoKey = autoKey;
        return c;
    }

    private static boolean bit(long bits, int n) {
        return ((bits >> n) & 1) != 0;
    }

    private int index(Control c) {
        return (c.unitId << 8) | cam.info.vcInterface;
    }

    private Integer read(Control c, int req) {
        byte[] buf = new byte[c.length];
        int r = cam.get(req, c.selector, index(c), buf, c.length);
        if (r < c.length) return null;
        return decode(c, buf);
    }

    private int decode(Control c, byte[] buf) {
        if (c.kind == KIND_PAN || c.kind == KIND_TILT) {
            int off = c.kind == KIND_PAN ? 0 : 4;
            return (buf[off] & 0xff) | ((buf[off + 1] & 0xff) << 8) | ((buf[off + 2] & 0xff) << 16) | ((buf[off + 3] & 0xff) << 24);
        }
        long v = 0;
        for (int i = 0; i < c.length; i++) v |= ((long) (buf[i] & 0xff)) << (8 * i);
        if (c.signed && c.length < 4) {
            int shift = 64 - 8 * c.length;
            v = (v << shift) >> shift;
        } else if (c.length == 4) {
            v = (int) v;
            if (!c.signed && v < 0) v = Integer.MAX_VALUE;
        }
        return (int) v;
    }

    private boolean query(Control c) {
        byte[] ib = new byte[1];
        if (cam.get(UvcCamera.REQ_GET_INFO, c.selector, index(c), ib, 1) == 1) c.info = ib[0] & 0xff;
        Integer cur = read(c, UvcCamera.REQ_GET_CUR);
        if (cur == null) return false;
        c.cur = cur;
        if (c.kind == KIND_TOGGLE) {
            c.min = 0;
            c.max = 1;
            Integer d = read(c, UvcCamera.REQ_GET_DEF);
            c.def = d != null ? d : cur;
            return true;
        }
        if (c.kind == KIND_MENU || c.kind == KIND_AE_MODE) {
            Integer d = read(c, UvcCamera.REQ_GET_DEF);
            c.def = d != null ? d : cur;
            if (c.kind == KIND_AE_MODE) {
                Integer res = read(c, UvcCamera.REQ_GET_RES);
                c.res = res != null && res != 0 ? res : 0x0f; // bitmap of supported modes
            } else {
                Integer mx = read(c, UvcCamera.REQ_GET_MAX);
                c.max = mx != null ? mx : 3;
            }
            return true;
        }
        Integer mn = read(c, UvcCamera.REQ_GET_MIN);
        Integer mx = read(c, UvcCamera.REQ_GET_MAX);
        Integer rs = read(c, UvcCamera.REQ_GET_RES);
        Integer df = read(c, UvcCamera.REQ_GET_DEF);
        if (mn == null || mx == null) return false;
        c.min = mn;
        c.max = mx;
        if (c.max < c.min) {
            int t = c.max;
            c.max = c.min;
            c.min = t;
        }
        c.res = rs != null && rs > 0 ? rs : 1;
        c.def = df != null ? df : cur;
        return c.max > c.min;
    }

    public boolean refresh(Control c) {
        Integer cur = read(c, UvcCamera.REQ_GET_CUR);
        if (cur == null) return false;
        c.cur = cur;
        return true;
    }

    public boolean set(Control c, int value) {
        byte[] buf = new byte[c.length];
        if (c.kind == KIND_PAN || c.kind == KIND_TILT) {
            if (cam.get(UvcCamera.REQ_GET_CUR, c.selector, index(c), buf, 8) < 8) java.util.Arrays.fill(buf, (byte) 0);
            int off = c.kind == KIND_PAN ? 0 : 4;
            for (int i = 0; i < 4; i++) buf[off + i] = (byte) (value >> (8 * i));
        } else {
            for (int i = 0; i < c.length; i++) buf[i] = (byte) (value >> (8 * i));
        }
        int r = cam.set(c.selector, index(c), buf, c.length);
        if (r < 0) {
            AppLog.w(TAG, "Setting " + c.name + " to " + value + " failed");
            return false;
        }
        c.cur = value;
        return true;
    }

    public Control find(String key) {
        for (Control c : list) if (c.key.equals(key)) return c;
        return null;
    }

    /** True while an automatic mode overrides the manual control. */
    public boolean isOverriddenByAuto(Control c) {
        if (c.autoKey == null) return false;
        Control a = find(c.autoKey);
        if (a == null) return false;
        if (a.kind == KIND_AE_MODE) return a.cur == 2 || a.cur == 8; // auto / aperture priority set exposure
        return a.cur != 0;
    }

    public void resetAll() {
        // Auto controls first so manual values are accepted afterwards.
        for (Control c : list) if (c.kind != KIND_SLIDER && c.kind != KIND_PAN && c.kind != KIND_TILT) set(c, c.def);
        for (Control c : list) if (c.kind == KIND_SLIDER || c.kind == KIND_PAN || c.kind == KIND_TILT) {
            if (!isOverriddenByAuto(c)) set(c, c.def);
        }
        AppLog.i(TAG, "Picture controls reset to defaults");
    }
}
