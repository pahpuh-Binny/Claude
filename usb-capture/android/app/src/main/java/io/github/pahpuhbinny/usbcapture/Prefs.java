package io.github.pahpuhbinny.usbcapture;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.pahpuhbinny.usbcapture.audio.AudioEngine;
import io.github.pahpuhbinny.usbcapture.render.DisplayParams;
import io.github.pahpuhbinny.usbcapture.uvc.ModeSelector;
import io.github.pahpuhbinny.usbcapture.uvc.UvcCamera;

/** All saved settings, with their defaults. */
public final class Prefs {
    public static final int ORIENT_AUTO = 0, ORIENT_LANDSCAPE = 1, ORIENT_PORTRAIT = 2;
    public static final int GRID_NONE = 0, GRID_THIRDS = 1, GRID_QUARTERS = 2, GRID_GOLDEN = 3;
    public static final int HIST_OFF = 0, HIST_LUMA = 1, HIST_RGB = 2;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return sp;
    }

    private int i(String k, int d) {
        return sp.getInt(k, d);
    }

    private float f(String k, float d) {
        return sp.getFloat(k, d);
    }

    private boolean b(String k, boolean d) {
        return sp.getBoolean(k, d);
    }

    public void put(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public void put(String k, float v) {
        sp.edit().putFloat(k, v).apply();
    }

    public void put(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    public void put(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    // ---- general
    public boolean expertMode() { return b("expert", false); }
    public int preset() { return i("preset", ModeSelector.PRESET_BEST_QUALITY); }
    public boolean customMode() { return b("custom_mode", false); }
    public int orientation() { return i("orientation", ORIENT_LANDSCAPE); }
    public boolean maxBrightness() { return b("max_brightness", false); }
    public int autoHideSeconds() { return i("autohide", 4); }
    public boolean showStats() { return b("show_stats", false); }
    public boolean autoPip() { return b("auto_pip", false); }
    public boolean idlePattern() { return b("idle_pattern", false); }
    public boolean lowLatency() { return b("low_latency", true); }
    public boolean startFullscreen() { return b("fullscreen", true); }
    public boolean hapticFeedback() { return b("haptics", true); }

    // ---- overlays
    public int grid() { return i("grid", GRID_NONE); }
    public boolean crosshair() { return b("crosshair", false); }
    public boolean safeAreas() { return b("safe_areas", false); }
    public int histogram() { return i("histogram", HIST_OFF); }
    public boolean meters() { return b("meters", false); }

    // ---- recording
    public boolean hevc() { return b("hevc", false); }
    public int bitrateMbps() { return i("bitrate", 0); }
    public int recordMaxHeight() { return i("rec_max_height", 0); }
    public boolean recordAudio() { return b("rec_audio", true); }
    public boolean replay() { return b("replay", false); }
    public int replaySeconds() { return i("replay_seconds", 30); }
    public boolean screenshotPng() { return b("shot_png", true); }

    // ---- advanced streaming
    public int decoderThreads() { return i("decoder_threads", 0); }
    public boolean rgb565() { return b("rgb565", false); }
    public boolean strictMjpeg() { return b("strict_mjpeg", true); }
    public boolean tolerateErrors() { return b("tolerate_errors", false); }
    public int urbCount() { return i("urbs", 0); }
    public int packetsPerUrb() { return i("packets", 0); }
    public boolean maxBandwidth() { return b("max_bw", false); }
    public boolean autoRecover() { return b("auto_recover", true); }

    public UvcCamera.Tuning tuning() {
        UvcCamera.Tuning t = new UvcCamera.Tuning();
        t.numUrbs = urbCount();
        t.packetsPerUrb = packetsPerUrb();
        t.maxBandwidth = maxBandwidth();
        t.strictMjpeg = strictMjpeg();
        t.tolerateErrors = tolerateErrors();
        return t;
    }

    // ---- per-device memory
    public String savedModeKey(int vid, int pid) {
        return sp.getString(String.format("mode_%04x_%04x", vid, pid), null);
    }

    public void saveModeKey(int vid, int pid, String key) {
        put(String.format("mode_%04x_%04x", vid, pid), key);
    }

    public void clearModeKey(int vid, int pid) {
        sp.edit().remove(String.format("mode_%04x_%04x", vid, pid)).apply();
    }

    public String lastDevice() {
        return sp.getString("last_device", null);
    }

    // ---- audio
    public AudioEngine.Config audio() {
        AudioEngine.Config c = new AudioEngine.Config();
        c.enabled = b("audio_on", true);
        c.monitor = b("audio_monitor", true);
        c.volume = f("audio_volume", 1f);
        c.delayMs = i("audio_delay", 0);
        c.inputDeviceId = i("audio_input", 0);
        c.ms2109Mode = i("ms2109", 0);
        c.micMix = b("mic_mix", false);
        c.micDeviceId = i("mic_input", 0);
        c.micGain = f("mic_gain", 1f);
        c.micMute = b("mic_mute", false);
        return c;
    }

    // ---- display
    public DisplayParams display() {
        DisplayParams p = new DisplayParams();
        p.scaleMode = i("scale", DisplayParams.SCALE_FIT);
        p.aspect = f("aspect", 0f);
        p.rotation = i("rotation", 0);
        p.mirror = b("mirror", false);
        p.flip = b("flip", false);
        p.filter = i("filter", DisplayParams.FILTER_SMOOTH);
        p.crop = f("crop", 0f);
        p.brightness = f("brightness", 0f);
        p.contrast = f("contrast", 1f);
        p.saturation = f("saturation", 1f);
        p.gamma = f("gamma", 1f);
        p.hue = f("hue", 0f);
        p.sharpen = f("sharpen", 0f);
        p.scanlines = f("scanlines", 0f);
        p.mono = b("mono", false);
        p.yuvMatrix = i("yuv_matrix", DisplayParams.MATRIX_AUTO);
        p.yuvRange = i("yuv_range", DisplayParams.RANGE_AUTO);
        p.zebra = b("zebra", false);
        p.zebraLevel = f("zebra_level", 0.95f);
        p.peaking = b("peaking", false);
        p.peakThreshold = f("peak_threshold", 0.18f);
        p.peakColor = i("peak_color", 0xFFFF3B30);
        p.falseColor = b("false_color", false);
        p.adjustCaptures = b("adjust_captures", true);
        return p;
    }

    public void saveDisplay(DisplayParams p) {
        sp.edit()
                .putInt("scale", p.scaleMode)
                .putFloat("aspect", p.aspect)
                .putInt("rotation", p.rotation)
                .putBoolean("mirror", p.mirror)
                .putBoolean("flip", p.flip)
                .putInt("filter", p.filter)
                .putFloat("crop", p.crop)
                .putFloat("brightness", p.brightness)
                .putFloat("contrast", p.contrast)
                .putFloat("saturation", p.saturation)
                .putFloat("gamma", p.gamma)
                .putFloat("hue", p.hue)
                .putFloat("sharpen", p.sharpen)
                .putFloat("scanlines", p.scanlines)
                .putBoolean("mono", p.mono)
                .putInt("yuv_matrix", p.yuvMatrix)
                .putInt("yuv_range", p.yuvRange)
                .putBoolean("zebra", p.zebra)
                .putFloat("zebra_level", p.zebraLevel)
                .putBoolean("peaking", p.peaking)
                .putFloat("peak_threshold", p.peakThreshold)
                .putInt("peak_color", p.peakColor)
                .putBoolean("false_color", p.falseColor)
                .putBoolean("adjust_captures", p.adjustCaptures)
                .apply();
    }

    public void resetPicture() {
        sp.edit().remove("brightness").remove("contrast").remove("saturation").remove("gamma").remove("hue")
                .remove("sharpen").remove("scanlines").remove("mono").apply();
    }
}
