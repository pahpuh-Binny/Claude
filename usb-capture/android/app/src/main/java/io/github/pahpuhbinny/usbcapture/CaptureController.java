package io.github.pahpuhbinny.usbcapture;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.pahpuhbinny.usbcapture.audio.AudioEngine;
import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.record.MediaSaver;
import io.github.pahpuhbinny.usbcapture.record.Recorder;
import io.github.pahpuhbinny.usbcapture.render.DisplayParams;
import io.github.pahpuhbinny.usbcapture.render.Geometry;
import io.github.pahpuhbinny.usbcapture.render.GlRenderer;
import io.github.pahpuhbinny.usbcapture.uvc.ModeSelector;
import io.github.pahpuhbinny.usbcapture.uvc.NativeStream;
import io.github.pahpuhbinny.usbcapture.uvc.PixelFormat;
import io.github.pahpuhbinny.usbcapture.uvc.UvcCamera;
import io.github.pahpuhbinny.usbcapture.uvc.UvcControls;
import io.github.pahpuhbinny.usbcapture.uvc.UvcDeviceInfo;
import io.github.pahpuhbinny.usbcapture.uvc.UvcNative;
import io.github.pahpuhbinny.usbcapture.uvc.VideoMode;
import io.github.pahpuhbinny.usbcapture.video.FrameSource;
import io.github.pahpuhbinny.usbcapture.video.MjpegDecoder;
import io.github.pahpuhbinny.usbcapture.video.RawFrameSource;
import io.github.pahpuhbinny.usbcapture.video.TestPatternSource;

/**
 * Ties everything together: finds USB video devices, asks for permission,
 * opens them, starts the best video mode (falling back to lower ones if a
 * mode fails), watches the stream and recovers it if it stalls, and drives
 * audio and recording. All device work runs on one background thread.
 */
public final class CaptureController implements GlRenderer.Listener, Recorder.Listener, AudioEngine.StatusListener {
    private static final String TAG = "Capture";
    private static final String ACTION_USB_PERMISSION = BuildConfig.APPLICATION_ID + ".USB_PERMISSION";

    public enum State {
        NO_DEVICE, NEED_CAMERA_PERMISSION, NEED_USB_PERMISSION, CONNECTING, STREAMING, NO_SIGNAL, ERROR, TEST_PATTERN
    }

    public interface Ui {
        void onState(State state, String title, String detail);

        void onStreamInfo(StreamInfo info);

        void onStats(LiveStats stats);

        void onToast(String message);

        void onRecordingState(boolean recording);

        void onAudioStatus(String status, boolean ok);

        void onControlsReady(UvcControls controls);
    }

    public static final class StreamInfo {
        public String deviceName = "";
        public String usbSpeed = "";
        public int vendorId, productId;
        public VideoMode mode;
        public List<VideoMode> modes = new ArrayList<>();
        public boolean testPattern;
        public int width, height;
        public double fps;
        public String transfer = "";
        public String deviceReport = "";
        public String hint = "";

        public String summary() {
            if (testPattern) return "Test pattern · 1920×1080 · 60 fps";
            if (mode == null) return deviceName;
            return mode.label();
        }
    }

    public static final class LiveStats {
        public float captureFps, renderFps, latencyMs, decodeMs;
        public double mbps;
        public long dropped, skipped, hiccups, usbErrors;
        public String text = "";
    }

    private final Context app;
    private final Prefs prefs;
    private final UsbManager usb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread ctlThread;
    private final Handler ctl;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    public final GlRenderer renderer;
    public final AudioEngine audio;
    public final Recorder recorder;
    private volatile Ui ui;

    // ---- control-thread state
    private UvcCamera camera;
    private NativeStream stream;
    private FrameSource source;
    private VideoMode mode;
    private List<VideoMode> modes = new ArrayList<>();
    private boolean active;
    private boolean testPatternRequested;
    private volatile State state = State.NO_DEVICE;
    private String stateTitle = "", stateDetail = "";
    private final Set<String> permissionAsked = new HashSet<>();
    private volatile DisplayParams params;
    private UvcControls controls;
    private long streamStartMs, firstFrameMs;
    private StreamInfo info;

    // ---- watchdog/stats
    private long lastStatsMs, lastFrames, lastBytes, lastDropError, lastDropsTotal;
    private long noFrameMs;
    private int recoveries;
    private long nextRecoveryMs;
    private boolean toleranceAuto;
    private long lastSummaryMs;
    private long summaryDrops, summarySkipped;
    private int thermalStatus;

    public CaptureController(Context context) {
        app = context.getApplicationContext();
        prefs = new Prefs(app);
        usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        ctlThread = new HandlerThread("capture-control");
        ctlThread.start();
        ctl = new Handler(ctlThread.getLooper());
        params = prefs.display();
        renderer = new GlRenderer(this);
        renderer.setParams(params);
        renderer.setLowLatency(prefs.lowLatency());
        renderer.start();
        audio = new AudioEngine(app);
        audio.setStatusListener(this);
        recorder = new Recorder(app, renderer, this);
        audio.setSink(recorder);
        if (prefs.replay()) recorder.setReplay(true, prefs.replaySeconds()); // starts once video arrives

        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(app, usbReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) pm.addThermalStatusListener(io, this::onThermal);
        }
        if (!UvcNative.AVAILABLE) AppLog.e(TAG, "Native USB engine missing: " + UvcNative.loadError());
    }

    public Prefs prefs() {
        return prefs;
    }

    public void attach(Ui ui) {
        this.ui = ui;
        final State s = state;
        final String t = stateTitle, d = stateDetail;
        main.post(() -> {
            Ui u = this.ui;
            if (u != null) {
                u.onState(s, t, d);
                u.onStreamInfo(info);
            }
        });
    }

    public void detach() {
        ui = null;
    }

    // ==================================================================
    // Lifecycle (main thread)

    public void onStart() {
        ctl.post(() -> {
            active = true;
            refresh();
        });
    }

    public void onStop() {
        ctl.post(() -> {
            active = false;
            closeDevice("app moved to the background");
            stopTestPattern();
            setState(State.NO_DEVICE, "Paused", "");
        });
    }

    public void release() {
        try {
            app.unregisterReceiver(usbReceiver);
        } catch (Throwable ignored) {
        }
        ctl.post(() -> {
            active = false;
            closeDevice("app closed");
        });
        recorder.shutdown();
        audio.stop();
        ctl.post(() -> renderer.release());
        ctlThread.quitSafely();
        io.shutdown();
    }

    public void onDeviceIntent() {
        ctl.post(this::refresh);
    }

    public void onPermissionsChanged() {
        ctl.post(() -> {
            permissionAsked.clear();
            refresh();
            if (camera != null) startAudio();
        });
    }

    public void retry() {
        ctl.post(() -> {
            permissionAsked.clear();
            if (camera != null && stream == null) startAuto();
            else refresh();
        });
    }

    public State state() {
        return state;
    }

    // ==================================================================
    // USB

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice d = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice.class);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (d != null) AppLog.i(TAG, "USB attached: " + UvcCamera.describeDevice(d) + (isVideo(d) ? " (video)" : ""));
                ctl.postDelayed(CaptureController.this::refresh, 300);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (d != null) AppLog.i(TAG, "USB detached: " + UvcCamera.describeDevice(d));
                ctl.post(() -> onDetached(d));
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                AppLog.i(TAG, "USB permission " + (granted ? "granted" : "denied") + (d != null ? " for " + UvcCamera.describeDevice(d) : ""));
                ctl.post(() -> {
                    if (granted) refresh();
                    else setState(State.NEED_USB_PERMISSION, "Access was not allowed",
                            "The app needs permission to use the capture card. Tap Allow to ask again.");
                });
            }
        }
    };

    public static boolean isVideo(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    private List<UsbDevice> videoDevices() {
        List<UsbDevice> out = new ArrayList<>();
        try {
            for (UsbDevice d : usb.getDeviceList().values()) if (isVideo(d)) out.add(d);
        } catch (Throwable t) {
            AppLog.w(TAG, "Listing USB devices failed", t);
        }
        return out;
    }

    public boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestUsbPermission(UsbDevice d) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(app.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, i, flags);
        AppLog.i(TAG, "Asking for USB permission: " + UvcCamera.describeDevice(d));
        usb.requestPermission(d, pi);
    }

    /** Called when the user taps "Allow" on the status card. */
    public void requestAccess() {
        ctl.post(() -> {
            List<UsbDevice> devs = videoDevices();
            if (devs.isEmpty()) {
                refresh();
                return;
            }
            UsbDevice d = choose(devs);
            if (usb.hasPermission(d)) refresh();
            else requestUsbPermission(d);
        });
    }

    private UsbDevice choose(List<UsbDevice> devs) {
        String last = prefs.lastDevice();
        if (last != null) {
            for (UsbDevice d : devs) {
                if (last.equals(String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId()))) return d;
            }
        }
        return devs.get(0);
    }

    public List<UsbDevice> listDevices() {
        return videoDevices();
    }

    public void selectDevice(UsbDevice d) {
        ctl.post(() -> {
            prefs.put("last_device", String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId()));
            if (camera != null && !camera.device.getDeviceName().equals(d.getDeviceName())) closeDevice("switching device");
            permissionAsked.clear();
            refresh();
        });
    }

    private void refresh() {
        if (!active) return;
        List<UsbDevice> devs = videoDevices();
        if (camera != null) {
            boolean present = false;
            for (UsbDevice d : devs) if (d.getDeviceName().equals(camera.device.getDeviceName())) present = true;
            if (present && !camera.isClosed()) {
                if (stream == null && state != State.ERROR) startAuto();
                return;
            }
            closeDevice("device no longer present");
        }
        if (devs.isEmpty()) {
            if (testPatternRequested || prefs.idlePattern()) {
                startTestPattern();
            } else {
                stopTestPattern();
                setState(State.NO_DEVICE, "Connect a capture card or USB camera",
                        "Plug it into the phone's USB-C port (directly or with a USB-C OTG adapter). The video starts automatically.");
            }
            return;
        }
        UsbDevice d = choose(devs);
        if (!usb.hasPermission(d)) {
            if (!hasCameraPermission()) {
                setState(State.NEED_CAMERA_PERMISSION, "Camera permission needed",
                        "Android only lets apps use USB cameras and capture cards when they have the Camera permission. Nothing is recorded without you pressing Record.");
                return;
            }
            if (permissionAsked.add(d.getDeviceName())) requestUsbPermission(d);
            setState(State.NEED_USB_PERMISSION, "Allow access to " + friendlyName(d),
                    "Tap OK in the Android dialog. Tick \"Always open\" so it connects instantly next time.");
            return;
        }
        stopTestPattern();
        openDevice(d);
    }

    private static String friendlyName(UsbDevice d) {
        String n = d.getProductName();
        return n != null && !n.trim().isEmpty() ? n.trim() : "the USB video device";
    }

    private void onDetached(UsbDevice d) {
        if (camera == null) {
            refresh();
            return;
        }
        if (d == null || d.getDeviceName().equals(camera.device.getDeviceName())) {
            closeDevice("unplugged");
            setState(State.NO_DEVICE, "Capture card disconnected", "Plug it back in to continue. Video starts again by itself.");
            ctl.postDelayed(this::refresh, 500);
        }
    }

    private void openDevice(UsbDevice d) {
        setState(State.CONNECTING, "Connecting…", friendlyName(d));
        try {
            camera = UvcCamera.open(usb, d);
        } catch (UvcCamera.UvcException e) {
            AppLog.e(TAG, "Open failed: " + e.getMessage());
            setState(State.ERROR, "Couldn't open " + friendlyName(d), e.getMessage());
            return;
        } catch (Throwable t) {
            AppLog.e(TAG, "Open failed", t);
            setState(State.ERROR, "Couldn't open " + friendlyName(d), t.toString());
            return;
        }
        prefs.put("last_device", String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId()));
        modes = ModeSelector.allModes(camera);
        int supported = 0;
        for (VideoMode m : modes) if (m.isSupported()) supported++;
        AppLog.i(TAG, modes.size() + " video modes (" + supported + " supported) on " + camera.speedShort());
        if (supported == 0) {
            setState(State.ERROR, "This device's video format isn't supported",
                    "It only offers formats this app can't show yet. Details are in the log.");
            return;
        }
        recoveries = 0;
        startAuto();
        startAudio();
    }

    private void closeDevice(String why) {
        stopStream();
        recorder.configure(null);
        audio.stop();
        if (controls != null) {
            controls = null;
            postUi(u -> u.onControlsReady(null));
        }
        if (camera != null) {
            AppLog.i(TAG, "Closing device (" + why + ")");
            try {
                camera.close();
            } catch (Throwable t) {
                AppLog.w(TAG, "Close failed", t);
            }
            camera = null;
        }
        modes = new ArrayList<>();
        info = null;
        postUi(u -> u.onStreamInfo(null));
    }

    // ==================================================================
    // Streaming

    private void startAuto() {
        if (camera == null) return;
        List<VideoMode> cands = new ArrayList<>();
        if (prefs.customMode()) {
            VideoMode saved = ModeSelector.findByKey(modes, prefs.savedModeKey(camera.info.vendorId, camera.info.productId));
            if (saved != null && saved.isSupported()) cands.add(saved);
        }
        for (VideoMode m : ModeSelector.rank(modes, prefs.preset())) if (!cands.contains(m)) cands.add(m);
        startWithFallback(cands);
    }

    private boolean startWithFallback(List<VideoMode> cands) {
        String lastError = "No usable video mode";
        boolean bandwidth = false;
        int tries = 0;
        for (VideoMode m : cands) {
            if (tries++ >= 6 || camera == null || camera.isClosed()) break;
            try {
                startMode(m);
                if (tries > 1) toast("Using " + m.label() + " because the higher setting didn't work");
                return true;
            } catch (UvcCamera.UvcException e) {
                lastError = e.getMessage();
                bandwidth |= e.bandwidth;
                AppLog.w(TAG, "Mode " + m + " failed: " + e.getMessage());
                if (lastError != null && lastError.contains("disconnected")) break;
            } catch (Throwable t) {
                lastError = t.toString();
                AppLog.e(TAG, "Mode " + m + " crashed while starting", t);
            }
        }
        stopStream();
        setState(State.ERROR, "Couldn't start the video", lastError
                + (bandwidth ? "\n\nTip: a lower resolution or frame rate needs less USB bandwidth." : ""));
        return false;
    }

    private void startMode(VideoMode m) throws UvcCamera.UvcException {
        stopStream();
        if (camera == null) throw new UvcCamera.UvcException("No device");
        boolean mjpeg = m.pixelFormat == PixelFormat.MJPEG;
        int threads = prefs.decoderThreads() > 0 ? prefs.decoderThreads() : MjpegDecoder.autoThreads(m.width, m.height, m.fps());
        UvcCamera.Tuning t = prefs.tuning();
        t.consumers = mjpeg ? threads : 1;
        setState(State.CONNECTING, "Starting video…", m.label());
        NativeStream ns = camera.startStream(m, t);
        FrameSource src = mjpeg
                ? new MjpegDecoder(ns, m.width, m.height, m.fps(), threads, prefs.rgb565())
                : new RawFrameSource(ns, m.pixelFormat, m.width, m.height, m.fps());
        UvcDeviceInfo.Format fmt = findFormat(m);
        renderer.setFormatMatrixHint(fmt != null ? fmt.matrix : 0);
        stream = ns;
        source = src;
        mode = m;
        renderer.setSource(src);
        streamStartMs = SystemClock.uptimeMillis();
        firstFrameMs = 0;
        lastStatsMs = streamStartMs;
        lastFrames = lastBytes = lastDropError = lastDropsTotal = 0;
        noFrameMs = 0;
        toleranceAuto = false;
        prefs.saveModeKey(camera.info.vendorId, camera.info.productId, m.key());
        publishInfo();
        configureRecorder();
        ctl.removeCallbacks(tick);
        ctl.postDelayed(tick, 1000);
        if (controls == null) {
            final UvcCamera cam = camera;
            ctl.post(() -> {
                if (cam != camera || cam.isClosed()) return;
                controls = cam.controls();
                UvcControls c = controls;
                postUi(u -> u.onControlsReady(c));
            });
        }
    }

    private UvcDeviceInfo.Format findFormat(VideoMode m) {
        if (camera == null || m.streamIndex >= camera.info.streams.size()) return null;
        for (UvcDeviceInfo.Format f : camera.info.streams.get(m.streamIndex).formats) if (f.index == m.formatIndex) return f;
        return null;
    }

    private void stopStream() {
        ctl.removeCallbacks(tick);
        FrameSource s = source;
        source = null;
        if (s != null) {
            renderer.setSource(null);
            try {
                s.stop();
            } catch (Throwable t) {
                AppLog.w(TAG, "Stopping frame source failed", t);
            }
        }
        if (camera != null) {
            try {
                camera.stopStream();
            } catch (Throwable t) {
                AppLog.w(TAG, "Stopping stream failed", t);
            }
        }
        stream = null;
        mode = null;
    }

    private void publishInfo() {
        StreamInfo i = new StreamInfo();
        if (source instanceof TestPatternSource) {
            i.testPattern = true;
            i.deviceName = "Test pattern";
            i.width = source.width();
            i.height = source.height();
            i.fps = source.nominalFps();
        } else if (camera != null) {
            i.deviceName = friendlyName(camera.device);
            i.vendorId = camera.info.vendorId;
            i.productId = camera.info.productId;
            i.usbSpeed = UvcCamera.speedName(camera.usbSpeed);
            i.mode = mode;
            i.modes = new ArrayList<>(modes);
            if (mode != null) {
                i.width = mode.width;
                i.height = mode.height;
                i.fps = mode.fps();
            }
            UvcCamera.Negotiated n = camera.negotiated();
            if (n != null) i.transfer = n.describe();
            i.deviceReport = UvcCamera.describeDevice(camera.device) + "\n" + i.usbSpeed + "\n\n" + camera.info.describe();
            i.hint = hintFor(camera);
        }
        info = i;
        postUi(u -> u.onStreamInfo(i));
    }

    private static String hintFor(UvcCamera cam) {
        int v = cam.info.vendorId, p = cam.info.productId;
        StringBuilder sb = new StringBuilder();
        if (v == 0x534d && p == 0x2109) {
            sb.append("MacroSilicon MS2109 (USB 2.0): best modes are 1920×1080 at 30 fps and 1280×720 at 60 fps (MJPEG). "
                    + "If the sound is high-pitched or crackly, turn on the MS2109 audio fix.");
        } else if ((v == 0x345f || v == 0x534d) && (p == 0x2130 || p == 0x2131)) {
            sb.append("MacroSilicon MS2130/2131 (USB 3): 1080p60 uncompressed needs a USB 3 port and cable; on USB 2 use MJPEG.");
        }
        if (cam.info.superSpeed == false && cam.info.bcdUSB >= 0x0300) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("This is a USB 3 device running at USB 2 speed (phone port or cable). High uncompressed modes may be unavailable.");
        }
        return sb.toString();
    }

    // ---- watchdog ----------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                doTick();
            } catch (Throwable t) {
                AppLog.e(TAG, "Watchdog error", t);
            }
            if (stream != null || source instanceof TestPatternSource) ctl.postDelayed(this, 1000);
        }
    };

    private void doTick() {
        long now = SystemClock.uptimeMillis();
        long dt = Math.max(1, now - lastStatsMs);
        LiveStats ls = new LiveStats();
        GlRenderer.Stats rs = renderer.stats();
        ls.renderFps = rs.renderFps;
        ls.latencyMs = rs.latencyMs;
        ls.hiccups = rs.hiccups;
        StringBuilder sb = new StringBuilder();
        FrameSource src = source;
        NativeStream ns = stream;

        if (ns != null && camera != null) {
            long[] st = ns.stats();
            UvcCamera.drainNative(ns);
            int stop = (int) st[UvcNative.ST_STOP_REASON];
            if (stop == UvcNative.STOP_DEVICE_GONE) {
                closeDevice("unplugged");
                setState(State.NO_DEVICE, "Capture card disconnected", "Plug it back in to continue. Video starts again by itself.");
                ctl.postDelayed(this::refresh, 500);
                return;
            }
            if (stop == UvcNative.STOP_FATAL) {
                recover("the USB stream stopped (" + UvcNative.strerror((int) st[UvcNative.ST_LAST_ERRNO]) + ")");
                return;
            }
            long frames = st[UvcNative.ST_FRAMES_PUBLISHED];
            long bytes = st[UvcNative.ST_BYTES];
            long dropErr = st[UvcNative.ST_DROP_ERROR];
            long drops = dropErr + st[UvcNative.ST_DROP_INCOMPLETE] + st[UvcNative.ST_DROP_OVERFLOW]
                    + st[UvcNative.ST_DROP_INVALID] + st[UvcNative.ST_DROP_NO_BUFFER];
            ls.captureFps = (frames - lastFrames) * 1000f / dt;
            ls.mbps = (bytes - lastBytes) * 8.0 / dt / 1000.0;
            ls.dropped = drops;
            ls.skipped = st[UvcNative.ST_SKIPPED_BY_CONSUMER];
            ls.usbErrors = st[UvcNative.ST_ISO_PACKET_ERRORS] + st[UvcNative.ST_URB_ERRORS];

            if (frames > lastFrames) {
                noFrameMs = 0;
                if (state == State.NO_SIGNAL || (state == State.CONNECTING && firstFrameMs > 0)) {
                    setState(State.STREAMING, "", "");
                    recoveries = 0;
                }
            } else {
                noFrameMs += dt;
            }
            if (noFrameMs >= 2000 && (state == State.STREAMING || state == State.CONNECTING)) {
                boolean data = bytes > lastBytes;
                setState(State.NO_SIGNAL, "No signal",
                        data ? "Data is arriving but no complete pictures yet. Recovering…"
                                : "Check that the HDMI source (console, camera, computer) is on and the cable is plugged in.");
                AppLog.w(TAG, "No frames for " + noFrameMs + " ms (" + (data ? "data still arriving" : "no data") + ")");
            }
            if (!toleranceAuto && !prefs.tolerateErrors() && noFrameMs >= 3000 && dropErr - lastDropError > 3) {
                toleranceAuto = true;
                ns.setTolerateErrors(true);
                AppLog.w(TAG, "Every frame has USB transfer errors; showing them anyway (error tolerance on)");
                toast("USB transfer errors detected – showing frames anyway. Try another cable or port if you see glitches.");
            }
            if (prefs.autoRecover() && noFrameMs >= 8000 && now >= nextRecoveryMs && mode != null) {
                recover("no video for " + noFrameMs / 1000 + " s");
                return;
            }
            summaryDrops += drops - lastDropsTotal;
            lastFrames = frames;
            lastBytes = bytes;
            lastDropError = dropErr;
            lastDropsTotal = drops;

            VideoMode m = mode;
            if (m != null) {
                sb.append(m.label()).append('\n');
            }
            sb.append(String.format(Locale.US, "Capture %.1f fps · Shown %.1f fps · %.1f Mb/s\n",
                    ls.captureFps, ls.renderFps, ls.mbps));
            if (src instanceof MjpegDecoder) {
                MjpegDecoder md = (MjpegDecoder) src;
                ls.decodeMs = md.averageDecodeMs();
                sb.append(md.statsLine()).append('\n');
            }
            sb.append(String.format(Locale.US, "Latency ~%.0f ms (USB→screen, excl. display) · upload %.1f ms\n",
                    rs.latencyMs, rs.uploadMs));
            sb.append(String.format(Locale.US, "Dropped %d · Skipped %d · USB errors %d · Hiccups %d\n",
                    drops, ls.skipped, ls.usbErrors, rs.hiccups));
            UvcCamera.Negotiated n = camera.negotiated();
            sb.append(camera.speedShort()).append(" · ").append(n != null ? n.describe() : "");
            if (toleranceAuto || prefs.tolerateErrors()) sb.append("\nError tolerance ON");
        } else if (src instanceof TestPatternSource) {
            sb.append(String.format(Locale.US, "Test pattern 1920×1080 · Shown %.1f fps · latency %.0f ms", rs.renderFps, rs.latencyMs));
        }
        if (recorder.isRecording() || recorder.isReplayEnabled()) {
            sb.append('\n').append(recorder.encoderDescription()).append(" · ").append(rs.recordedFrames).append(" frames");
        }
        sb.append('\n').append(audio.status());
        if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sb.append("\nPhone temperature: ").append(thermalName(thermalStatus));
        }
        ls.text = sb.toString();
        lastStatsMs = now;
        postUi(u -> u.onStats(ls));

        if (now - lastSummaryMs >= 30_000) {
            if (summaryDrops > 0 || ls.hiccups > 0) {
                AppLog.i(TAG, String.format(Locale.US, "Last 30 s: capture %.1f fps, shown %.1f fps, %d frames dropped, %d hiccups total, %.1f Mb/s",
                        ls.captureFps, ls.renderFps, summaryDrops, ls.hiccups, ls.mbps));
            }
            summaryDrops = 0;
            lastSummaryMs = now;
        }
    }

    private void recover(String reason) {
        recoveries++;
        long backoff = Math.min(60_000, 8000L << Math.min(3, recoveries - 1));
        nextRecoveryMs = SystemClock.uptimeMillis() + backoff;
        AppLog.w(TAG, "Restarting the stream: " + reason + " (attempt " + recoveries + ")");
        VideoMode m = mode;
        stopStream();
        if (camera == null || camera.isClosed()) return;
        try {
            if (m != null) startMode(m);
            else startAuto();
        } catch (UvcCamera.UvcException e) {
            AppLog.w(TAG, "Restart failed: " + e.getMessage());
            startAuto();
        }
    }

    // ==================================================================
    // Test pattern

    public void setTestPattern(boolean on) {
        ctl.post(() -> {
            testPatternRequested = on;
            if (!on) stopTestPattern();
            refresh();
        });
    }

    private void startTestPattern() {
        if (source instanceof TestPatternSource) return;
        stopStream();
        TestPatternSource tp = new TestPatternSource(1920, 1080, 60);
        source = tp;
        renderer.setFormatMatrixHint(0);
        renderer.setSource(tp);
        setState(State.TEST_PATTERN, "", "");
        publishInfo();
        configureRecorder();
        ctl.removeCallbacks(tick);
        ctl.postDelayed(tick, 1000);
        if (audio != null && prefs.audio().micMix) startAudio();
    }

    private void stopTestPattern() {
        if (source instanceof TestPatternSource) {
            stopStream();
            recorder.configure(null);
            info = null;
            postUi(u -> u.onStreamInfo(null));
        }
    }

    // ==================================================================
    // User actions

    public void selectMode(VideoMode m) {
        ctl.post(() -> {
            if (camera == null) return;
            prefs.put("custom_mode", true);
            List<VideoMode> cands = new ArrayList<>();
            cands.add(m);
            for (VideoMode r : ModeSelector.rank(modes, prefs.preset())) if (!cands.contains(r)) cands.add(r);
            startWithFallback(cands);
        });
    }

    public void selectPreset(int preset) {
        ctl.post(() -> {
            prefs.put("preset", preset);
            prefs.put("custom_mode", false);
            if (camera != null) startAuto();
        });
    }

    /** Re-applies streaming settings (decoder threads, USB tuning...) by restarting the stream. */
    public void restartStream() {
        ctl.post(() -> {
            if (camera == null) return;
            VideoMode m = mode;
            if (m == null) {
                startAuto();
                return;
            }
            List<VideoMode> cands = new ArrayList<>();
            cands.add(m);
            for (VideoMode r : ModeSelector.rank(modes, prefs.preset())) if (!cands.contains(r)) cands.add(r);
            startWithFallback(cands);
        });
    }

    public void setDisplayParams(DisplayParams p) {
        DisplayParams old = params;
        params = p.copy();
        renderer.setParams(params);
        prefs.saveDisplay(params);
        if (old.rotation != p.rotation || old.crop != p.crop || old.aspect != p.aspect) {
            ctl.post(this::configureRecorder);
        }
    }

    public DisplayParams displayParams() {
        return params.copy();
    }

    public void setControl(UvcControls.Control c, int value) {
        ctl.post(() -> {
            UvcControls cs = controls;
            if (cs == null || camera == null) return;
            cs.set(c, value);
            if (c.kind != UvcControls.KIND_SLIDER) {
                for (UvcControls.Control o : cs.list) cs.refresh(o);
                postUi(u -> u.onControlsReady(cs));
            }
        });
    }

    public void resetControls() {
        ctl.post(() -> {
            UvcControls cs = controls;
            if (cs == null) return;
            cs.resetAll();
            for (UvcControls.Control o : cs.list) cs.refresh(o);
            postUi(u -> u.onControlsReady(cs));
            toast("Picture controls reset to the device defaults");
        });
    }

    public void updateAudio() {
        ctl.post(() -> {
            if (camera == null && !(source instanceof TestPatternSource)) return;
            AudioEngine.Config c = audioConfig();
            audio.update(c);
            configureRecorder();
        });
    }

    private AudioEngine.Config audioConfig() {
        AudioEngine.Config c = prefs.audio();
        if (camera != null) {
            c.vendorId = camera.info.vendorId;
            c.productId = camera.info.productId;
            c.deviceName = camera.device.getProductName();
        }
        if (!hasAudioPermission()) {
            c.enabled = false;
            c.micMix = false;
        }
        return c;
    }

    private void startAudio() {
        AudioEngine.Config c = audioConfig();
        if (!hasAudioPermission() && prefs.audio().enabled) {
            postUi(u -> u.onAudioStatus("Sound needs the microphone permission (the capture card's audio arrives as a USB microphone)", false));
        }
        audio.start(c);
    }

    private void configureRecorder() {
        FrameSource s = source;
        if (s == null) {
            recorder.configure(null);
            return;
        }
        int[] size = Geometry.captureSize(params, s.width(), s.height());
        Recorder.Config rc = new Recorder.Config();
        rc.width = size[0];
        rc.height = size[1];
        rc.fps = s.nominalFps() > 0 ? s.nominalFps() : 30;
        rc.hevc = prefs.hevc();
        rc.bitrate = prefs.bitrateMbps() * 1_000_000;
        rc.maxHeight = prefs.recordMaxHeight();
        AudioEngine.Config ac = prefs.audio();
        rc.audio = prefs.recordAudio() && hasAudioPermission() && (ac.enabled || ac.micMix);
        rc.sampleRate = audio.sampleRate();
        recorder.configure(rc);
    }

    public void refreshRecorderConfig() {
        ctl.post(this::configureRecorder);
    }

    public void toggleRecording() {
        if (recorder.isRecording()) recorder.stopRecording();
        else recorder.startRecording();
    }

    public void setReplay(boolean on) {
        prefs.put("replay", on);
        recorder.setReplay(on, prefs.replaySeconds());
    }

    public void saveReplay() {
        recorder.saveReplay();
    }

    public void takeScreenshot() {
        renderer.requestScreenshot((bmp, err) -> {
            if (bmp == null) {
                toast(err != null ? err : "Screenshot failed");
                return;
            }
            io.execute(() -> saveShot(bmp));
        });
    }

    private void saveShot(Bitmap bmp) {
        try {
            String loc = MediaSaver.saveImage(app, bmp, prefs.screenshotPng());
            AppLog.i(TAG, "Screenshot saved: " + loc);
            toast("Screenshot saved to " + loc);
        } catch (Throwable t) {
            AppLog.e(TAG, "Saving screenshot failed", t);
            toast("Couldn't save the screenshot: " + t.getMessage());
        } finally {
            bmp.recycle();
        }
    }

    public String deviceReport() {
        StreamInfo i = info;
        return i != null ? i.deviceReport : "";
    }

    public UvcControls controls() {
        return controls;
    }

    // ==================================================================
    // Callbacks

    @Override
    public void onFirstFrame(int width, int height) {
        ctl.post(() -> {
            if (firstFrameMs == 0) {
                firstFrameMs = SystemClock.uptimeMillis();
                AppLog.i(TAG, "First picture " + width + "x" + height + " after " + (firstFrameMs - streamStartMs) + " ms");
            }
            if (state == State.CONNECTING || state == State.NO_SIGNAL) setState(State.STREAMING, "", "");
        });
    }

    @Override
    public void onRenderError(String message) {
        AppLog.e(TAG, "Render error: " + message);
        toast(message);
    }

    @Override
    public void onRecordingState(boolean recording) {
        postUi(u -> u.onRecordingState(recording));
    }

    @Override
    public void onSaved(String what, String location, String error) {
        if (location != null) toast(what + " saved to " + location);
        else if (error != null) toast(what + ": " + error);
    }

    @Override
    public void onRecorderMessage(String message) {
        toast(message);
    }

    @Override
    public void onAudioStatus(String status, boolean ok) {
        postUi(u -> u.onAudioStatus(status, ok));
        if (ok) ctl.post(this::configureRecorder);
    }

    private void onThermal(int status) {
        thermalStatus = status;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        AppLog.w(TAG, "Thermal status: " + thermalName(status));
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            toast("The phone is getting very hot (" + thermalName(status) + "). Lower the resolution or frame rate to avoid slowdowns.");
        }
    }

    private static String thermalName(int s) {
        switch (s) {
            case 0: return "normal";
            case 1: return "light";
            case 2: return "moderate";
            case 3: return "severe";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutdown";
            default: return String.valueOf(s);
        }
    }

    // ==================================================================

    private void setState(State s, String title, String detail) {
        state = s;
        stateTitle = title;
        stateDetail = detail;
        postUi(u -> u.onState(s, title, detail));
    }

    private void toast(String msg) {
        postUi(u -> u.onToast(msg));
    }

    private interface UiCall {
        void run(Ui u);
    }

    private void postUi(UiCall c) {
        main.post(() -> {
            Ui u = ui;
            if (u != null) {
                try {
                    c.run(u);
                } catch (Throwable t) {
                    AppLog.e(TAG, "UI update failed", t);
                }
            }
        });
    }
}
