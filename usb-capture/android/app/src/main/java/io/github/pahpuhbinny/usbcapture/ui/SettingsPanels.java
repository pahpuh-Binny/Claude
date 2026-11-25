package io.github.pahpuhbinny.usbcapture.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioDeviceInfo;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.pahpuhbinny.usbcapture.BuildConfig;
import io.github.pahpuhbinny.usbcapture.CaptureController;
import io.github.pahpuhbinny.usbcapture.Prefs;
import io.github.pahpuhbinny.usbcapture.audio.AudioEngine;
import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.render.DisplayParams;
import io.github.pahpuhbinny.usbcapture.uvc.ModeSelector;
import io.github.pahpuhbinny.usbcapture.uvc.PixelFormat;
import io.github.pahpuhbinny.usbcapture.uvc.UvcControls;
import io.github.pahpuhbinny.usbcapture.uvc.VideoMode;

/** Content of the Play-mode quick settings sheet and the Expert-mode panel tabs. */
public final class SettingsPanels {
    public interface Host {
        Context context();

        CaptureController controller();

        Prefs prefs();

        CaptureController.StreamInfo info();

        UvcControls controls();

        String audioStatus();

        void rebuildPanel();

        void applyUiPrefs();

        void setExpert(boolean on);

        void openLogs();

        void showHelp();

        void requestAudioPermission();

        void toast(String s);
    }

    public static final String[] TABS = {"Video", "Image", "Display", "Assist", "Audio", "Record", "Advanced", "App", "Info"};

    private final Host h;

    public SettingsPanels(Host h) {
        this.h = h;
    }

    // ==================================================================

    public void buildQuick(PanelBuilder b) {
        video(b, false);
        Prefs p = h.prefs();
        CaptureController c = h.controller();

        b.header("Picture");
        DisplayParams dp = c.displayParams();
        b.chips("Fit to screen", new String[]{"Fit", "Fill (crop)", "Stretch", "Pixel-perfect"},
                scaleIndex(dp.scaleMode), i -> {
                    DisplayParams q = c.displayParams();
                    q.scaleMode = scaleValue(i);
                    q.zoom = 1f;
                    q.panX = q.panY = 0f;
                    c.setDisplayParams(q);
                    h.applyUiPrefs();
                });
        b.chips("Shape", new String[]{"Auto", "16:9", "4:3"}, aspectIndex(dp.aspect, new float[]{0f, 16f / 9, 4f / 3}), i -> {
            DisplayParams q = c.displayParams();
            q.aspect = new float[]{0f, 16f / 9, 4f / 3}[i];
            c.setDisplayParams(q);
            h.applyUiPrefs();
        });

        b.header("Sound");
        AudioEngine.Config ac = p.audio();
        b.toggle("Play the capture card's sound", null, ac.enabled && ac.monitor, on -> {
            p.put("audio_on", on || p.audio().micMix);
            p.put("audio_monitor", on);
            if (on && !h.controller().hasAudioPermission()) h.requestAudioPermission();
            c.updateAudio();
        });
        b.slider("Volume", 0f, 200f, 5f, ac.volume * 100f, v -> Math.round(v) + "%", v -> {
            p.put("audio_volume", v / 100f);
            c.updateAudio();
        });
        b.slider("Sound delay (lip sync)", 0f, 500f, 10f, ac.delayMs, v -> Math.round(v) + " ms", v -> {
            p.put("audio_delay", Math.round(v));
            c.updateAudio();
        });
        b.note("If the sound comes before the picture, add a little delay.");

        b.header("Screen");
        b.chips("Rotation", new String[]{"Auto-rotate", "Landscape", "Portrait"}, p.orientation(), i -> {
            p.put("orientation", i);
            h.applyUiPrefs();
        });
        b.toggle("Maximum screen brightness", null, p.maxBrightness(), on -> {
            p.put("max_brightness", on);
            h.applyUiPrefs();
        });
        b.spacer(8);
        b.buttonRow(new String[]{"Expert mode", "Logs", "Help"},
                new Runnable[]{() -> h.setExpert(true), h::openLogs, h::showHelp});
        b.spacer(16);
    }

    public void buildTab(PanelBuilder b, String tab) {
        switch (tab) {
            case "Video": video(b, true); break;
            case "Image": image(b); break;
            case "Display": display(b); break;
            case "Assist": assist(b); break;
            case "Audio": audio(b); break;
            case "Record": record(b); break;
            case "Advanced": advanced(b); break;
            case "App": app(b); break;
            default: info(b);
        }
        b.spacer(24);
    }

    // ==================================================================

    private void video(PanelBuilder b, boolean expert) {
        Prefs p = h.prefs();
        CaptureController c = h.controller();
        CaptureController.StreamInfo i = h.info();
        b.header("Video");
        int sel = p.customMode() ? -1 : p.preset();
        b.chips("Quality", new String[]{"Best quality", "Smoothest", "Lowest latency"}, sel, idx -> {
            c.selectPreset(idx);
            h.toast(new String[]{"Highest resolution, then highest frame rate",
                    "Highest frame rate first", "Uncompressed when possible, for the least delay"}[idx]);
        });
        if (i == null || i.mode == null) {
            b.note(i != null && i.testPattern ? "Showing the test pattern."
                    : "Connect a device to choose its resolution and frame rate.");
            return;
        }
        VideoMode cur = i.mode;
        List<int[]> res = ModeSelector.resolutions(i.modes);
        String[] rl = new String[res.size()];
        int rsel = -1;
        for (int k = 0; k < res.size(); k++) {
            int[] r = res.get(k);
            rl[k] = r[0] + "×" + r[1] + resolutionName(r[0], r[1]);
            if (r[0] == cur.width && r[1] == cur.height) rsel = k;
        }
        b.chips("Resolution", rl, rsel, idx -> {
            int[] r = res.get(idx);
            VideoMode m = bestAt(i.modes, r[0], r[1], cur);
            if (m != null) c.selectMode(m);
        });

        List<VideoMode> rates = new ArrayList<>();
        for (VideoMode m : ModeSelector.atResolution(i.modes, cur.width, cur.height, cur.pixelFormat)) {
            boolean dup = false;
            for (VideoMode r : rates) if (r.fpsLabel().equals(m.fpsLabel())) dup = true;
            if (!dup) rates.add(m);
        }
        String[] fl = new String[rates.size()];
        boolean[] fen = new boolean[rates.size()];
        int fsel = -1;
        for (int k = 0; k < rates.size(); k++) {
            VideoMode m = rates.get(k);
            fl[k] = m.fpsLabel() + " fps" + (m.exceedsBandwidth ? " ⚠" : "");
            fen[k] = true;
            if (m.sameAs(cur)) fsel = k;
        }
        b.chips("Frame rate", fl, fsel, fen, idx -> c.selectMode(rates.get(idx)));

        List<Integer> fmts = new ArrayList<>();
        for (VideoMode m : ModeSelector.atResolution(i.modes, cur.width, cur.height, 0)) {
            if (!fmts.contains(m.pixelFormat)) fmts.add(m.pixelFormat);
        }
        if (expert || fmts.size() > 1) {
            String[] names = new String[fmts.size()];
            int fms = -1;
            for (int k = 0; k < fmts.size(); k++) {
                names[k] = expert ? PixelFormat.name(fmts.get(k)) : PixelFormat.friendly(fmts.get(k));
                if (fmts.get(k) == cur.pixelFormat) fms = k;
            }
            b.chips("Format", names, fms, idx -> {
                List<VideoMode> ms = ModeSelector.atResolution(i.modes, cur.width, cur.height, fmts.get(idx));
                VideoMode pick = null;
                for (VideoMode m : ms) if (Math.abs(m.fps() - cur.fps()) < 0.5) pick = m;
                if (pick == null && !ms.isEmpty()) pick = ms.get(0);
                if (pick != null) c.selectMode(pick);
            });
            if (!expert) {
                b.note("Compressed (MJPEG) allows higher resolutions over USB 2; uncompressed has slightly less delay and no compression artefacts.");
            }
        }
        if (cur.exceedsBandwidth) {
            b.note("⚠ This mode needs more USB bandwidth than the connection reports. If the picture stutters, choose a lower frame rate or MJPEG.");
        }
        b.note("Connected over " + i.usbSpeed + ".");
        if (i.hint != null && !i.hint.isEmpty()) b.note(i.hint);
        if (expert) {
            b.buttonRow(new String[]{"All modes…", "Restart video"}, new Runnable[]{this::allModes, c::restartStream});
            if (!i.transfer.isEmpty()) b.mono(i.transfer);
        }
    }

    private static String resolutionName(int w, int h) {
        if (w == 3840 && h == 2160) return " (4K)";
        if (w == 2560 && h == 1440) return " (1440p)";
        if (w == 1920 && h == 1080) return " (1080p)";
        if (w == 1280 && h == 720) return " (720p)";
        if (w == 720 && (h == 480 || h == 576)) return " (SD)";
        return "";
    }

    private static VideoMode bestAt(List<VideoMode> modes, int w, int h, VideoMode cur) {
        List<VideoMode> c = ModeSelector.atResolution(modes, w, h, cur.pixelFormat);
        if (c.isEmpty()) c = ModeSelector.atResolution(modes, w, h, 0);
        if (c.isEmpty()) return null;
        for (VideoMode m : c) if (Math.abs(m.fps() - cur.fps()) < 0.5 && !m.exceedsBandwidth) return m;
        return c.get(0);
    }

    private void allModes() {
        CaptureController.StreamInfo i = h.info();
        if (i == null || i.modes.isEmpty()) return;
        List<VideoMode> list = new ArrayList<>(i.modes);
        String[] labels = new String[list.size()];
        for (int k = 0; k < list.size(); k++) {
            VideoMode m = list.get(k);
            labels[k] = (m.sameAs(i.mode) ? "● " : "") + m.label() + "  [" + m.fourcc + "]"
                    + (m.isSupported() ? "" : "  (unsupported)") + (m.exceedsBandwidth ? "  ⚠ bandwidth" : "");
        }
        new MaterialAlertDialogBuilder(h.context())
                .setTitle("All video modes (" + list.size() + ")")
                .setItems(labels, (d, which) -> {
                    VideoMode m = list.get(which);
                    if (m.isSupported()) h.controller().selectMode(m);
                    else h.toast(PixelFormat.name(m.pixelFormat) + " can't be shown by this app yet");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------

    private void image(PanelBuilder b) {
        CaptureController c = h.controller();
        UvcControls cs = h.controls();
        b.header("Device picture controls");
        if (cs == null) {
            b.note("Available while a device is connected.");
            return;
        }
        if (cs.list.isEmpty()) {
            b.note("This device doesn't offer adjustable picture controls. Use the Display tab to adjust the picture in the app instead.");
            return;
        }
        b.note("These settings change the picture inside the capture card or camera itself.");
        for (UvcControls.Control ctl : cs.list) {
            boolean overridden = cs.isOverriddenByAuto(ctl);
            switch (ctl.kind) {
                case UvcControls.KIND_TOGGLE:
                    b.toggle(ctl.name, null, ctl.cur != 0, on -> c.setControl(ctl, on ? 1 : 0));
                    break;
                case UvcControls.KIND_MENU: {
                    List<String> names = new ArrayList<>();
                    List<Integer> vals = new ArrayList<>();
                    int s = -1;
                    for (int k = 0; k < ctl.menu.length; k++) {
                        if (ctl.menuValues[k] > Math.max(ctl.max, 2)) continue;
                        if (ctl.menuValues[k] == ctl.cur) s = names.size();
                        names.add(ctl.menu[k]);
                        vals.add(ctl.menuValues[k]);
                    }
                    b.chips(ctl.name, names.toArray(new String[0]), s, idx -> c.setControl(ctl, vals.get(idx)));
                    break;
                }
                case UvcControls.KIND_AE_MODE: {
                    List<String> names = new ArrayList<>();
                    List<Integer> vals = new ArrayList<>();
                    int s = -1;
                    for (int k = 0; k < ctl.menu.length; k++) {
                        if ((ctl.res & ctl.menuValues[k]) == 0) continue;
                        if (ctl.menuValues[k] == ctl.cur) s = names.size();
                        names.add(ctl.menu[k]);
                        vals.add(ctl.menuValues[k]);
                    }
                    if (!names.isEmpty()) {
                        b.chips(ctl.name, names.toArray(new String[0]), s, idx -> c.setControl(ctl, vals.get(idx)));
                    }
                    break;
                }
                default: {
                    float step = Math.max(1, ctl.res);
                    com.google.android.material.slider.Slider sl = b.slider(ctl.name, ctl.min, ctl.max, step, ctl.cur,
                            v -> String.valueOf(Math.round(v)) + (Math.round(v) == ctl.def ? " (default)" : ""),
                            v -> c.setControl(ctl, Math.round(v)));
                    if (overridden) {
                        sl.setEnabled(false);
                        b.note("Automatic mode is on; turn it off to adjust " + ctl.name.toLowerCase(Locale.US) + ".");
                    }
                }
            }
        }
        b.button("Reset to device defaults", c::resetControls);
    }

    // ------------------------------------------------------------------

    private interface ParamEdit {
        void edit(DisplayParams p);
    }

    private void editParams(ParamEdit e) {
        CaptureController c = h.controller();
        DisplayParams p = c.displayParams();
        e.edit(p);
        c.setDisplayParams(p);
        h.applyUiPrefs();
    }

    private static int scaleIndex(int mode) {
        switch (mode) {
            case DisplayParams.SCALE_FILL: return 1;
            case DisplayParams.SCALE_STRETCH: return 2;
            case DisplayParams.SCALE_INTEGER: return 3;
            case DisplayParams.SCALE_ORIGINAL: return 4;
            default: return 0;
        }
    }

    private static int scaleValue(int idx) {
        return new int[]{DisplayParams.SCALE_FIT, DisplayParams.SCALE_FILL, DisplayParams.SCALE_STRETCH,
                DisplayParams.SCALE_INTEGER, DisplayParams.SCALE_ORIGINAL}[idx];
    }

    private static int aspectIndex(float a, float[] values) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - a) < 0.001f) return i;
        return -1;
    }

    private void display(PanelBuilder b) {
        DisplayParams dp = h.controller().displayParams();
        b.header("Scaling");
        b.chips("Fit", new String[]{"Fit", "Fill (crop)", "Stretch", "Pixel-perfect (integer)", "1:1 pixels"},
                scaleIndex(dp.scaleMode), i -> editParams(p -> {
                    p.scaleMode = scaleValue(i);
                    p.zoom = 1f;
                    p.panX = p.panY = 0f;
                }));
        float[] aspects = {0f, 16f / 9, 4f / 3, 21f / 9, 16f / 10, 3f / 2, 1f, 8f / 7};
        b.chips("Aspect ratio", new String[]{"Source", "16:9", "4:3", "21:9", "16:10", "3:2", "1:1", "8:7"},
                aspectIndex(dp.aspect, aspects), i -> editParams(p -> p.aspect = aspects[i]));
        b.chips("Scaling filter", new String[]{"Smooth", "Sharp pixels (nearest)", "Sharp bilinear"},
                dp.filter, i -> editParams(p -> p.filter = i));
        b.note("Pixel-perfect + Sharp pixels is ideal for retro consoles.");
        b.slider("Crop edges (overscan)", 0f, 10f, 0.5f, dp.crop * 100f, v -> String.format(Locale.US, "%.1f%%", v),
                v -> editParams(p -> p.crop = v / 100f));
        b.slider("Zoom", 1f, 8f, 0.1f, dp.zoom, v -> String.format(Locale.US, "%.1fx", v),
                v -> editParams(p -> p.zoom = v));
        b.note("Tip: pinch the picture to zoom, drag to move, double-tap to switch Fit/Fill.");

        b.header("Orientation");
        b.chips("Rotate", new String[]{"0°", "90°", "180°", "270°"}, dp.rotation / 90,
                i -> editParams(p -> p.rotation = i * 90));
        b.toggle("Mirror (left ↔ right)", null, dp.mirror, on -> editParams(p -> p.mirror = on));
        b.toggle("Flip (upside down)", null, dp.flip, on -> editParams(p -> p.flip = on));

        b.header("Picture (in the app)");
        b.slider("Brightness", -50f, 50f, 1f, dp.brightness * 100f, v -> String.valueOf(Math.round(v)),
                v -> editParams(p -> p.brightness = v / 100f));
        b.slider("Contrast", 0f, 200f, 1f, dp.contrast * 100f, v -> Math.round(v) + "%",
                v -> editParams(p -> p.contrast = v / 100f));
        b.slider("Saturation", 0f, 200f, 1f, dp.saturation * 100f, v -> Math.round(v) + "%",
                v -> editParams(p -> p.saturation = v / 100f));
        b.slider("Gamma", 30f, 300f, 1f, dp.gamma * 100f, v -> String.format(Locale.US, "%.2f", v / 100f),
                v -> editParams(p -> p.gamma = v / 100f));
        b.slider("Hue", -180f, 180f, 1f, dp.hue, v -> Math.round(v) + "°", v -> editParams(p -> p.hue = v));
        b.slider("Sharpen", 0f, 200f, 5f, dp.sharpen * 100f, v -> Math.round(v) + "%",
                v -> editParams(p -> p.sharpen = v / 100f));
        b.slider("Scanlines (CRT look)", 0f, 100f, 5f, dp.scanlines * 100f, v -> Math.round(v) + "%",
                v -> editParams(p -> p.scanlines = v / 100f));
        b.toggle("Black & white", null, dp.mono, on -> editParams(p -> p.mono = on));
        b.toggle("Apply picture settings to screenshots and recordings", null, dp.adjustCaptures,
                on -> editParams(p -> p.adjustCaptures = on));
        b.button("Reset picture settings", () -> editParams(p -> {
            p.brightness = 0f;
            p.contrast = 1f;
            p.saturation = 1f;
            p.gamma = 1f;
            p.hue = 0f;
            p.sharpen = 0f;
            p.scanlines = 0f;
            p.mono = false;
            h.rebuildPanel();
        }));

        b.header("Colour conversion");
        b.chips("YUV matrix", new String[]{"Auto", "BT.601 (SD)", "BT.709 (HD)"}, dp.yuvMatrix,
                i -> editParams(p -> p.yuvMatrix = i));
        b.chips("Range", new String[]{"Auto (limited)", "Limited 16-235", "Full 0-255"}, dp.yuvRange,
                i -> editParams(p -> p.yuvRange = i));
        b.note("If blacks look grey, choose Full. If shadows and highlights are clipped, choose Limited.");
    }

    // ------------------------------------------------------------------

    private void assist(PanelBuilder b) {
        Prefs p = h.prefs();
        DisplayParams dp = h.controller().displayParams();
        b.header("Framing guides");
        b.chips("Grid", new String[]{"Off", "Thirds", "Quarters", "Golden ratio"}, p.grid(), i -> {
            p.put("grid", i);
            h.applyUiPrefs();
        });
        b.toggle("Centre mark", null, p.crosshair(), on -> {
            p.put("crosshair", on);
            h.applyUiPrefs();
        });
        b.toggle("Safe areas (93% / 90% / 80%)", null, p.safeAreas(), on -> {
            p.put("safe_areas", on);
            h.applyUiPrefs();
        });

        b.header("Exposure");
        b.toggle("Zebra stripes on bright areas", null, dp.zebra, on -> editParams(q -> q.zebra = on));
        b.slider("Zebra level", 70f, 100f, 1f, dp.zebraLevel * 100f, v -> Math.round(v) + "%",
                v -> editParams(q -> q.zebraLevel = v / 100f));
        b.toggle("False colour", "Purple = crushed blacks, green = mid grey, pink = skin, yellow/red = near clipping",
                dp.falseColor, on -> editParams(q -> q.falseColor = on));
        b.chips("Histogram", new String[]{"Off", "Brightness", "RGB"}, p.histogram(), i -> {
            p.put("histogram", i);
            h.applyUiPrefs();
        });

        b.header("Focus");
        b.toggle("Focus peaking", "Highlights sharp edges", dp.peaking, on -> editParams(q -> q.peaking = on));
        b.slider("Peaking sensitivity", 5f, 50f, 1f, 55f - dp.peakThreshold * 100f, v -> String.valueOf(Math.round(v)),
                v -> editParams(q -> q.peakThreshold = (55f - v) / 100f));
        int[] colors = {0xFFFF3B30, 0xFF34C759, 0xFF0A84FF, 0xFFFFCC00, 0xFFFFFFFF};
        int ci = 0;
        for (int k = 0; k < colors.length; k++) if (colors[k] == dp.peakColor) ci = k;
        b.chips("Peaking colour", new String[]{"Red", "Green", "Blue", "Yellow", "White"}, ci,
                i -> editParams(q -> q.peakColor = colors[i]));

        b.header("On-screen info");
        b.toggle("Audio level meters", null, p.meters(), on -> {
            p.put("meters", on);
            h.applyUiPrefs();
        });
        b.toggle("Live statistics (fps, drops, latency)", null, p.showStats(), on -> {
            p.put("show_stats", on);
            h.applyUiPrefs();
        });
    }

    // ------------------------------------------------------------------

    private void audio(PanelBuilder b) {
        Prefs p = h.prefs();
        CaptureController c = h.controller();
        AudioEngine.Config ac = p.audio();
        b.header("Capture card audio");
        if (!c.hasAudioPermission()) {
            b.note("Sound needs the Microphone permission: Android delivers the capture card's audio as a USB microphone.");
            b.button("Allow microphone", h::requestAudioPermission);
        }
        b.note(h.audioStatus());
        b.toggle("Capture audio", null, ac.enabled, on -> {
            p.put("audio_on", on);
            c.updateAudio();
        });
        b.toggle("Play through this phone (monitor)", null, ac.monitor, on -> {
            p.put("audio_monitor", on);
            c.updateAudio();
        });
        b.slider("Volume", 0f, 200f, 5f, ac.volume * 100f, v -> Math.round(v) + "%", v -> {
            p.put("audio_volume", v / 100f);
            c.updateAudio();
        });
        b.slider("A/V sync delay", 0f, 1000f, 10f, ac.delayMs, v -> Math.round(v) + " ms", v -> {
            p.put("audio_delay", Math.round(v));
            c.updateAudio();
        });
        List<AudioDeviceInfo> inputs = c.audio.inputs();
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        names.add("Auto (USB)");
        ids.add(0);
        int sel = 0;
        for (AudioDeviceInfo d : inputs) {
            names.add(AudioEngine.describe(d));
            ids.add(d.getId());
            if (d.getId() == ac.inputDeviceId) sel = ids.size() - 1;
        }
        b.chips("Input", names.toArray(new String[0]), sel, i -> {
            p.put("audio_input", ids.get(i));
            c.updateAudio();
        });
        b.chips("MS2109 audio fix", new String[]{"Auto", "On", "Off"}, ac.ms2109Mode, i -> {
            p.put("ms2109", i);
            c.updateAudio();
        });
        b.note("Cheap MS2109 capture cards report 96 kHz mono but send 48 kHz stereo, which sounds sped-up or crackly. The fix reads it correctly.");

        b.header("Commentary microphone");
        b.toggle("Mix a microphone into recordings", "Uses the phone's mic (or a headset/USB mic). Not played back, to avoid echo. Some phones can't record two inputs at once.",
                ac.micMix, on -> {
                    p.put("mic_mix", on);
                    if (on && !c.hasAudioPermission()) h.requestAudioPermission();
                    c.updateAudio();
                    h.applyUiPrefs();
                });
        List<String> mnames = new ArrayList<>();
        List<Integer> mids = new ArrayList<>();
        mnames.add("Phone microphone");
        mids.add(0);
        int msel = 0;
        for (AudioDeviceInfo d : inputs) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) continue;
            mnames.add(AudioEngine.describe(d));
            mids.add(d.getId());
            if (d.getId() == ac.micDeviceId) msel = mids.size() - 1;
        }
        b.chips("Microphone", mnames.toArray(new String[0]), msel, i -> {
            p.put("mic_input", mids.get(i));
            c.updateAudio();
        });
        b.slider("Mic level", 0f, 400f, 10f, ac.micGain * 100f, v -> Math.round(v) + "%", v -> {
            p.put("mic_gain", v / 100f);
            c.updateAudio();
        });
        b.toggle("Mute microphone", null, ac.micMute, on -> {
            p.put("mic_mute", on);
            c.updateAudio();
        });
    }

    // ------------------------------------------------------------------

    private void record(PanelBuilder b) {
        Prefs p = h.prefs();
        CaptureController c = h.controller();
        b.header("Recording");
        b.chips("Codec", new String[]{"H.264 (most compatible)", "HEVC / H.265 (smaller files)"}, p.hevc() ? 1 : 0, i -> {
            p.put("hevc", i == 1);
            c.refreshRecorderConfig();
        });
        int[] heights = {0, 2160, 1440, 1080, 720};
        int hs = 0;
        for (int k = 0; k < heights.length; k++) if (heights[k] == p.recordMaxHeight()) hs = k;
        b.chips("Resolution", new String[]{"Same as source", "Up to 4K", "Up to 1440p", "Up to 1080p", "Up to 720p"}, hs, i -> {
            p.put("rec_max_height", heights[i]);
            c.refreshRecorderConfig();
        });
        int[] rates = {0, 8, 16, 24, 40, 60, 80};
        int rs = 0;
        for (int k = 0; k < rates.length; k++) if (rates[k] == p.bitrateMbps()) rs = k;
        b.chips("Quality (bitrate)", new String[]{"Auto", "8 Mb/s", "16 Mb/s", "24 Mb/s", "40 Mb/s", "60 Mb/s", "80 Mb/s"}, rs, i -> {
            p.put("bitrate", rates[i]);
            c.refreshRecorderConfig();
        });
        b.toggle("Record audio", null, p.recordAudio(), on -> {
            p.put("rec_audio", on);
            c.refreshRecorderConfig();
        });
        b.note("Recordings are saved to Movies/USB Capture. Long recordings are split into parts below 4 GB with no frames lost between parts.");

        b.header("Instant replay");
        b.toggle("Keep the last moments in memory", "Press the replay button to save them, like a console's capture button.", p.replay(), on -> {
            c.setReplay(on);
            h.applyUiPrefs();
        });
        int[] secs = {15, 30, 60, 120};
        int ss = 1;
        for (int k = 0; k < secs.length; k++) if (secs[k] == p.replaySeconds()) ss = k;
        b.chips("Replay length", new String[]{"15 s", "30 s", "1 min", "2 min"}, ss, i -> {
            p.put("replay_seconds", secs[i]);
            if (p.replay()) c.setReplay(true);
        });

        b.header("Screenshots");
        b.chips("Format", new String[]{"PNG (lossless)", "JPEG (smaller)"}, p.screenshotPng() ? 0 : 1,
                i -> p.put("shot_png", i == 0));
        b.note("Screenshots are saved at the full source resolution to Pictures/USB Capture.");
    }

    // ------------------------------------------------------------------

    private void advanced(PanelBuilder b) {
        Prefs p = h.prefs();
        CaptureController c = h.controller();
        b.header("Decoding");
        int[] th = {0, 1, 2, 3, 4};
        b.chips("MJPEG decoder threads", new String[]{"Auto", "1", "2", "3", "4"}, p.decoderThreads(), i -> {
            p.put("decoder_threads", th[i]);
            c.restartStream();
        });
        b.toggle("Fast decoding (16-bit colour)", "Halves memory bandwidth for MJPEG on slower phones; slight banding.", p.rgb565(), on -> {
            p.put("rgb565", on);
            c.restartStream();
        });
        b.toggle("Drop incomplete MJPEG frames", "Prevents half-grey glitch frames.", p.strictMjpeg(), on -> {
            p.put("strict_mjpeg", on);
            c.restartStream();
        });
        b.toggle("Show frames with USB errors", "Use with poor cables or hubs: shows damaged frames instead of skipping them.", p.tolerateErrors(), on -> {
            p.put("tolerate_errors", on);
            c.restartStream();
        });
        b.toggle("Automatic recovery", "Restarts the stream if video stops for 8 seconds.", p.autoRecover(),
                on -> p.put("auto_recover", on));
        b.toggle("Lowest display latency", "Draws each frame immediately instead of waiting for the screen's refresh.", p.lowLatency(), on -> {
            p.put("low_latency", on);
            c.renderer.setLowLatency(on);
        });

        b.header("USB transfers");
        int[] urbs = {0, 4, 8, 16, 24, 32};
        int us = 0;
        for (int k = 0; k < urbs.length; k++) if (urbs[k] == p.urbCount()) us = k;
        b.chips("Transfers in flight", new String[]{"Auto", "4", "8", "16", "24", "32"}, us, i -> {
            p.put("urbs", urbs[i]);
            c.restartStream();
        });
        int[] pk = {0, 8, 16, 32, 64};
        int ps = 0;
        for (int k = 0; k < pk.length; k++) if (pk[k] == p.packetsPerUrb()) ps = k;
        b.chips("Packets per transfer (isochronous)", new String[]{"Auto", "8", "16", "32", "64"}, ps, i -> {
            p.put("packets", pk[i]);
            c.restartStream();
        });
        b.toggle("Reserve maximum USB bandwidth", "Helps devices that under-report how much bandwidth they need.", p.maxBandwidth(), on -> {
            p.put("max_bw", on);
            c.restartStream();
        });
        b.buttonRow(new String[]{"Restart video", "Forget saved mode"}, new Runnable[]{c::restartStream, () -> {
            CaptureController.StreamInfo i = h.info();
            if (i != null) p.clearModeKey(i.vendorId, i.productId);
            p.put("custom_mode", false);
            c.selectPreset(p.preset());
        }});
    }

    // ------------------------------------------------------------------

    private void app(PanelBuilder b) {
        Prefs p = h.prefs();
        CaptureController c = h.controller();
        b.header("Mode");
        b.chips(null, new String[]{"Play mode (simple)", "Expert mode"}, p.expertMode() ? 1 : 0, i -> h.setExpert(i == 1));
        b.header("Screen");
        b.chips("Rotation", new String[]{"Auto-rotate", "Landscape", "Portrait"}, p.orientation(), i -> {
            p.put("orientation", i);
            h.applyUiPrefs();
        });
        b.toggle("Start in full screen", null, p.startFullscreen(), on -> p.put("fullscreen", on));
        int[] hide = {2, 4, 8, 0};
        int hsel = 1;
        for (int k = 0; k < hide.length; k++) if (hide[k] == p.autoHideSeconds()) hsel = k;
        b.chips("Hide controls after", new String[]{"2 s", "4 s", "8 s", "Never"}, hsel, i -> {
            p.put("autohide", hide[i]);
            h.applyUiPrefs();
        });
        b.toggle("Maximum screen brightness", null, p.maxBrightness(), on -> {
            p.put("max_brightness", on);
            h.applyUiPrefs();
        });
        b.toggle("Picture-in-picture when leaving the app", null, p.autoPip(), on -> {
            p.put("auto_pip", on);
            h.applyUiPrefs();
        });
        b.toggle("Vibrate on button presses", null, p.hapticFeedback(), on -> p.put("haptics", on));
        b.header("Test signal");
        b.toggle("Show a test pattern when nothing is connected", null, p.idlePattern(), on -> {
            p.put("idle_pattern", on);
            c.setTestPattern(on);
        });
        b.header("Help & logs");
        b.buttonRow(new String[]{"Logs & crash reports", "Help"}, new Runnable[]{h::openLogs, h::showHelp});
        b.note("USB Capture " + BuildConfig.VERSION_NAME + ". Every crash, freeze and USB problem is written to the log automatically.");
    }

    private void info(PanelBuilder b) {
        CaptureController.StreamInfo i = h.info();
        b.header("Device");
        if (i == null) {
            b.note("No device connected.");
            return;
        }
        b.keyValue("Name", i.deviceName);
        if (!i.testPattern) {
            b.keyValue("USB ID", String.format(Locale.US, "%04x:%04x", i.vendorId, i.productId));
            b.keyValue("Connection", i.usbSpeed);
        }
        if (i.mode != null) {
            b.keyValue("Mode", i.mode.label());
            b.keyValue("Modes offered", String.valueOf(i.modes.size()));
        }
        if (!i.transfer.isEmpty()) b.mono(i.transfer);
        if (!i.deviceReport.isEmpty()) {
            b.header("Descriptors");
            b.mono(i.deviceReport);
            b.buttonRow(new String[]{"Copy report", "Write to log"}, new Runnable[]{() -> {
                ClipboardManager cm = (ClipboardManager) h.context().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("USB Capture device report", i.deviceReport));
                h.toast("Device report copied");
            }, () -> {
                AppLog.i("DeviceReport", i.deviceReport);
                h.toast("Device report written to the log");
            }});
        }
    }
}
