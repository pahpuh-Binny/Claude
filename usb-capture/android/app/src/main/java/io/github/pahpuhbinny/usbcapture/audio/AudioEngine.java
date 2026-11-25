package io.github.pahpuhbinny.usbcapture.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/**
 * Captures the capture card's audio (Android sees it as a USB
 * microphone), plays it through the phone's speaker or headphones and
 * passes it to the recorder. Optionally captures a second microphone for
 * commentary and mixes it into recordings.
 *
 * Playback runs from its own buffer whose fill level is held steady by
 * dropping or repeating single samples now and then, so the small clock
 * difference between the USB device and the phone never builds up into
 * delay or crackles. The same buffer level sets the A/V sync delay.
 */
public final class AudioEngine {
    private static final String TAG = "Audio";
    private static final int BASE_LATENCY_MS = 30;

    public static final class Config {
        public boolean enabled = true;
        public boolean monitor = true;
        /** 0..2 (above 1 is a software boost). */
        public float volume = 1f;
        public int delayMs;
        /** AudioDeviceInfo id; 0 picks the USB input automatically. */
        public int inputDeviceId;
        /** 0 = auto, 1 = always, 2 = never. */
        public int ms2109Mode;
        public boolean micMix;
        /** AudioDeviceInfo id for the commentary mic; 0 = phone microphone. */
        public int micDeviceId;
        public float micGain = 1f;
        public boolean micMute;
        public int vendorId;
        public int productId;
        public String deviceName;

        public Config copy() {
            Config c = new Config();
            c.enabled = enabled;
            c.monitor = monitor;
            c.volume = volume;
            c.delayMs = delayMs;
            c.inputDeviceId = inputDeviceId;
            c.ms2109Mode = ms2109Mode;
            c.micMix = micMix;
            c.micDeviceId = micDeviceId;
            c.micGain = micGain;
            c.micMute = micMute;
            c.vendorId = vendorId;
            c.productId = productId;
            c.deviceName = deviceName;
            return c;
        }
    }

    public interface PcmSink {
        /** Interleaved 16-bit PCM, always 2 channels. */
        void onPcm(short[] data, int frames, int sampleRate, long ptsUs);
    }

    public interface StatusListener {
        void onAudioStatus(String status, boolean ok);
    }

    private final Context context;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Config cfg = new Config();
    private volatile boolean running;
    private Thread captureThread, micThread;
    private volatile Thread playThread;
    private final Object playLock = new Object();
    private volatile PcmSink sink;
    private volatile StatusListener statusListener;
    private volatile ShortRing monitorRing;
    private volatile ShortRing micRing;
    private volatile int outRate = 48000;
    private volatile boolean gameActive;
    private volatile boolean micActive;
    private volatile String status = "Audio off";
    private volatile String micStatus = "";
    private final float[] levels = new float[3];
    private volatile long underruns;
    private int generation;
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            for (AudioDeviceInfo d : added) {
                if (d.isSource() && isUsb(d)) {
                    AppLog.i(TAG, "Audio input appeared: " + describe(d));
                    if (running && !gameActive) restart();
                    return;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            for (AudioDeviceInfo d : removed) {
                if (d.isSource() && isUsb(d)) AppLog.i(TAG, "Audio input removed: " + describe(d));
            }
        }
    };

    public AudioEngine(Context context) {
        this.context = context.getApplicationContext();
        this.am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setSink(PcmSink s) {
        sink = s;
    }

    public void setStatusListener(StatusListener l) {
        statusListener = l;
    }

    public String status() {
        String m = micStatus;
        return m.isEmpty() ? status : status + " · " + m;
    }

    /** Peak levels 0..1: [left, right, mic]. */
    public float[] levels() {
        synchronized (levels) {
            float[] r = levels.clone();
            levels[0] *= 0.6f;
            levels[1] *= 0.6f;
            levels[2] *= 0.6f;
            return r;
        }
    }

    public long underruns() {
        return underruns;
    }

    public int sampleRate() {
        return outRate;
    }

    public boolean isCapturing() {
        return gameActive || micActive;
    }

    public synchronized void start(Config c) {
        stop();
        cfg = c.copy();
        if (!cfg.enabled && !cfg.micMix) {
            setStatus("Audio off", true);
            return;
        }
        running = true;
        final int gen = ++generation;
        am.registerAudioDeviceCallback(deviceCallback, main);
        if (cfg.enabled) {
            captureThread = new Thread(() -> captureLoop(gen), "audio-capture");
            captureThread.start();
        }
        if (cfg.micMix) {
            micThread = new Thread(() -> micLoop(gen), "audio-mic");
            micThread.start();
        }
    }

    /** Applies settings that don't need a restart (volume, delay, monitor, mic gain/mute). */
    public synchronized void update(Config c) {
        Config old = cfg;
        boolean restart = old.enabled != c.enabled || old.inputDeviceId != c.inputDeviceId
                || old.ms2109Mode != c.ms2109Mode || old.micMix != c.micMix || old.micDeviceId != c.micDeviceId
                || (old.monitor != c.monitor && c.monitor && playThread == null);
        cfg = c.copy();
        if (restart && running) start(c);
        else if (restart && (c.enabled || c.micMix)) start(c);
    }

    public synchronized void restart() {
        if (running) start(cfg);
    }

    public synchronized void stop() {
        if (!running && captureThread == null && micThread == null) return;
        running = false;
        generation++;
        try {
            am.unregisterAudioDeviceCallback(deviceCallback);
        } catch (Throwable ignored) {
        }
        joinQuietly(captureThread);
        joinQuietly(micThread);
        joinQuietly(playThread);
        captureThread = micThread = playThread = null;
        gameActive = false;
        micActive = false;
        micStatus = "";
        setStatus("Audio off", true);
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) AppLog.w(TAG, t.getName() + " did not stop in time");
    }

    private boolean alive(int gen) {
        return running && gen == generation;
    }

    private void setStatus(String s, boolean ok) {
        status = s;
        StatusListener l = statusListener;
        if (l != null) main.post(() -> l.onAudioStatus(status(), ok));
    }

    // ------------------------------------------------------------------
    // Device helpers

    public static boolean isUsb(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    public static String describe(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = "Phone microphone"; break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = "USB audio"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB headset"; break;
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: type = "USB accessory"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = "Headset mic"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = "Bluetooth mic"; break;
            case AudioDeviceInfo.TYPE_TELEPHONY: type = "Telephony"; break;
            default: type = "Input type " + d.getType();
        }
        CharSequence name = d.getProductName();
        String n = name != null ? name.toString() : "";
        return n.isEmpty() || n.equalsIgnoreCase(type) ? type : type + " (" + n + ")";
    }

    /** Input devices the user can choose from. */
    public List<AudioDeviceInfo> inputs() {
        List<AudioDeviceInfo> out = new ArrayList<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY || d.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX
                    || d.getType() == AudioDeviceInfo.TYPE_FM_TUNER) continue;
            out.add(d);
        }
        return out;
    }

    private AudioDeviceInfo findInput(int id, boolean preferUsb, String nameHint) {
        AudioDeviceInfo[] all = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (id != 0) {
            for (AudioDeviceInfo d : all) if (d.getId() == id) return d;
        }
        if (!preferUsb) {
            for (AudioDeviceInfo d : all) if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) return d;
            return null;
        }
        AudioDeviceInfo firstUsb = null;
        for (AudioDeviceInfo d : all) {
            if (!isUsb(d)) continue;
            if (firstUsb == null) firstUsb = d;
            CharSequence pn = d.getProductName();
            if (nameHint != null && pn != null && pn.toString().equalsIgnoreCase(nameHint)) return d;
        }
        return firstUsb;
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    private int pickSource() {
        String p = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
        if ("true".equals(p)) return MediaRecorder.AudioSource.UNPROCESSED;
        return MediaRecorder.AudioSource.MIC;
    }

    private AudioRecord buildRecord(int source, int rate, int channels, AudioDeviceInfo dev) {
        int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        int min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        int size = Math.max(min * 2, rate * channels * 2 / 10); // >= 100 ms
        AudioRecord r;
        try {
            r = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(size)
                    .build();
        } catch (SecurityException e) {
            AppLog.w(TAG, "Microphone permission missing");
            return null;
        } catch (Throwable t) {
            AppLog.w(TAG, "AudioRecord " + rate + " Hz x" + channels + " failed: " + t);
            return null;
        }
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            return null;
        }
        if (dev != null) r.setPreferredDevice(dev);
        return r;
    }

    // ------------------------------------------------------------------
    // Capture card audio

    private void captureLoop(int gen) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {
        }
        int failures = 0;
        while (alive(gen)) {
            Config c = cfg;
            AudioDeviceInfo dev = findInput(c.inputDeviceId, true, c.deviceName);
            if (dev == null) {
                setStatus("No audio from the capture card (no USB audio input found)", false);
                // The USB audio device often appears a moment after the video one.
                for (int i = 0; i < 20 && alive(gen); i++) SystemClock.sleep(100);
                continue;
            }
            boolean ok = runCapture(gen, dev);
            if (!alive(gen)) break;
            failures++;
            if (!ok && failures > 5) {
                setStatus("Audio could not start; see the log", false);
                SystemClock.sleep(3000);
            } else {
                SystemClock.sleep(Math.min(2000, 200L * failures));
            }
        }
        gameActive = false;
    }

    private boolean runCapture(int gen, AudioDeviceInfo dev) {
        Config c = cfg;
        int[] rates = dev.getSampleRates();
        int[] chans = dev.getChannelCounts();
        boolean msChip = c.vendorId == 0x534d && c.productId == 0x2109;
        boolean monoOnly = chans != null && chans.length > 0 && !contains(chans, 2);
        boolean fix = c.ms2109Mode == 1
                || (c.ms2109Mode == 0 && msChip && contains(rates, 96000) && (monoOnly || chans == null || chans.length == 0));
        int inRate, inCh;
        if (fix) {
            inRate = 96000;
            inCh = 1;
        } else {
            if (rates == null || rates.length == 0 || contains(rates, 48000)) inRate = 48000;
            else if (contains(rates, 44100)) inRate = 44100;
            else {
                inRate = rates[0];
                for (int r : rates) if (Math.abs(r - 48000) < Math.abs(inRate - 48000)) inRate = r;
            }
            inCh = (chans == null || chans.length == 0 || contains(chans, 2)) ? 2 : 1;
        }
        int source = pickSource();
        AudioRecord rec = buildRecord(source, inRate, inCh, dev);
        if (rec == null && source != MediaRecorder.AudioSource.MIC) {
            source = MediaRecorder.AudioSource.MIC;
            rec = buildRecord(source, inRate, inCh, dev);
        }
        if (rec == null && inCh == 2) {
            inCh = 1;
            rec = buildRecord(source, inRate, inCh, dev);
        }
        if (rec == null) {
            setStatus("Could not open audio input " + describe(dev), false);
            return false;
        }
        int rate = fix ? 48000 : inRate;
        outRate = rate;
        try {
            rec.startRecording();
        } catch (Throwable t) {
            AppLog.e(TAG, "startRecording failed", t);
            rec.release();
            setStatus("Audio input is busy (another app may be recording)", false);
            return false;
        }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release();
            setStatus("Audio input is busy (another app may be recording)", false);
            return false;
        }
        AudioDeviceInfo routed = rec.getRoutedDevice();
        String routedDesc = routed != null ? describe(routed) : "unknown";
        AppLog.i(TAG, String.format(Locale.US, "Capturing %s at %d Hz x%d (source %d)%s, routed to %s",
                describe(dev), inRate, inCh, source, fix ? " with MS2109 fix (96k mono -> 48k stereo)" : "", routedDesc));
        boolean wrongRoute = routed != null && !isUsb(routed) && isUsb(dev);
        String label = String.format(Locale.US, "%s · %.1f kHz %s%s", describe(dev), rate / 1000.0,
                fix || inCh == 2 ? "stereo" : "mono", fix ? " · MS2109 fix" : "");
        setStatus(wrongRoute ? "Android sent audio from " + routedDesc + " instead of the capture card" : label, !wrongRoute);

        monitorRing = new ShortRing(rate * 2 * 2); // 2 s stereo
        gameActive = true;
        if (c.monitor) startPlayback(gen, rate);

        int chunkFrames = Math.max(64, inRate / 200); // 5 ms
        short[] in = new short[chunkFrames * inCh];
        short[] out = new short[chunkFrames * 2 * 2];
        short[] mix = new short[chunkFrames * 2 * 2];
        short[] micBuf = new short[chunkFrames * 2];
        AudioTimestamp ts = new AudioTimestamp();
        long framesRead = 0;
        long silentSince = SystemClock.uptimeMillis();
        boolean warnedSilent = false;
        boolean ok = true;
        while (alive(gen)) {
            int n = rec.read(in, 0, in.length);
            if (n < 0) {
                AppLog.w(TAG, "Audio read error " + n + (n == AudioRecord.ERROR_DEAD_OBJECT ? " (device gone)" : ""));
                ok = n != AudioRecord.ERROR_INVALID_OPERATION;
                break;
            }
            if (n == 0) continue;
            int inFrames = n / inCh;
            long pts;
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                pts = (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / inRate) / 1000L;
            } else {
                pts = System.nanoTime() / 1000L - (long) inFrames * 1_000_000L / inRate;
            }
            framesRead += inFrames;

            int outFrames;
            if (fix) {
                // The MS2109 reports 96 kHz mono but actually sends 48 kHz stereo.
                outFrames = n / 2;
                System.arraycopy(in, 0, out, 0, outFrames * 2);
            } else if (inCh == 1) {
                outFrames = n;
                for (int i = 0; i < n; i++) {
                    out[2 * i] = in[i];
                    out[2 * i + 1] = in[i];
                }
            } else {
                outFrames = inFrames;
                System.arraycopy(in, 0, out, 0, outFrames * 2);
            }
            int samples = outFrames * 2;

            int pl = 0, pr = 0;
            for (int i = 0; i < samples; i += 2) {
                pl = Math.max(pl, Math.abs((int) out[i]));
                pr = Math.max(pr, Math.abs((int) out[i + 1]));
            }
            synchronized (levels) {
                levels[0] = Math.max(levels[0], pl / 32768f);
                levels[1] = Math.max(levels[1], pr / 32768f);
            }
            if (pl > 8 || pr > 8) {
                silentSince = SystemClock.uptimeMillis();
                warnedSilent = false;
            } else if (!warnedSilent && SystemClock.uptimeMillis() - silentSince > 15000) {
                warnedSilent = true;
                AppLog.i(TAG, "Capture audio has been silent for 15 s");
            }

            ShortRing mr = monitorRing;
            if (cfg.monitor && mr != null) {
                mr.write(out, 0, samples);
                if (playThread == null) startPlayback(gen, rate);
            }

            PcmSink s = sink;
            if (s != null) {
                ShortRing mic = micRing;
                if (micActive && mic != null && !cfg.micMute) {
                    float g = cfg.micGain;
                    int got = mic.read(micBuf, 0, outFrames);
                    System.arraycopy(out, 0, mix, 0, samples);
                    for (int i = 0; i < got; i++) {
                        int m = (int) (micBuf[i] * g);
                        mix[2 * i] = clip(mix[2 * i] + m);
                        mix[2 * i + 1] = clip(mix[2 * i + 1] + m);
                    }
                    s.onPcm(mix, outFrames, rate, pts);
                } else {
                    s.onPcm(out, outFrames, rate, pts);
                }
            }
        }
        gameActive = false;
        try {
            rec.stop();
        } catch (Throwable ignored) {
        }
        rec.release();
        Thread pt = playThread;
        playThread = null;
        joinQuietly(pt);
        return ok;
    }

    private static short clip(int v) {
        return (short) Math.max(-32768, Math.min(32767, v));
    }

    // ------------------------------------------------------------------
    // Monitoring (playback)

    private void startPlayback(int gen, int rate) {
        synchronized (playLock) {
            if (playThread != null || !alive(gen)) return;
            Thread t = new Thread(() -> playLoop(gen, rate), "audio-play");
            playThread = t;
            t.start();
        }
    }

    private void playLoop(int gen, int rate) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {
        }
        int mask = AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, rate * 4 / 50)) // >= 20 ms
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (Throwable t) {
            AppLog.e(TAG, "Could not create audio output", t);
            return;
        }
        AppLog.i(TAG, "Monitoring at " + rate + " Hz, output buffer " + track.getBufferSizeInFrames() + " frames");
        int chunk = Math.max(64, rate / 200); // 5 ms
        short[] buf = new short[(chunk + 2) * 2];
        boolean playing = false;
        long lastUnderruns = 0;
        float lastVolume = -1;
        while (alive(gen) && gameActive) {
            Config c = cfg;
            ShortRing ring = monitorRing;
            if (ring == null || !c.monitor) {
                if (playing) {
                    track.pause();
                    track.flush();
                    playing = false;
                }
                if (ring != null) ring.clear();
                SystemClock.sleep(50);
                continue;
            }
            int target = (BASE_LATENCY_MS + Math.max(0, Math.min(1000, c.delayMs))) * rate / 1000 * 2;
            if (!playing) {
                if (!ring.awaitAvailable(target, 100)) continue;
                track.play();
                playing = true;
            }
            int avail = ring.available();
            int want = chunk * 2;
            if (avail > target + rate * 2 * 60 / 1000) {
                ring.skip(avail - target); // way behind: jump back to target
                avail = target;
            }
            int n;
            if (avail > target + rate * 2 * 15 / 1000) {
                ring.skip(2); // slightly behind: drop one frame
                n = ring.read(buf, 0, want);
            } else if (avail < target - rate * 2 * 15 / 1000 && avail >= want) {
                n = ring.read(buf, 0, want - 2); // slightly ahead: repeat one frame
                if (n >= 2) {
                    buf[n] = buf[n - 2];
                    buf[n + 1] = buf[n - 1];
                    n += 2;
                }
            } else {
                if (!ring.awaitAvailable(want, 30)) {
                    continue;
                }
                n = ring.read(buf, 0, want);
            }
            if (n <= 0) continue;
            float vol = Math.max(0f, Math.min(2f, c.volume));
            if (vol > 1f) {
                for (int i = 0; i < n; i++) buf[i] = clip((int) (buf[i] * vol));
            }
            float trackVol = Math.min(1f, vol);
            if (trackVol != lastVolume) {
                track.setVolume(trackVol);
                lastVolume = trackVol;
            }
            int w = track.write(buf, 0, n);
            if (w < 0) {
                AppLog.w(TAG, "Audio output error " + w);
                break;
            }
            long u = track.getUnderrunCount();
            if (u != lastUnderruns) {
                underruns += u - lastUnderruns;
                lastUnderruns = u;
            }
        }
        try {
            track.pause();
            track.flush();
        } catch (Throwable ignored) {
        }
        track.release();
    }

    // ------------------------------------------------------------------
    // Commentary microphone

    private void micLoop(int gen) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {
        }
        Config c = cfg;
        AudioDeviceInfo dev = findInput(c.micDeviceId, false, null);
        int rate = 48000;
        AudioRecord rec = buildRecord(MediaRecorder.AudioSource.MIC, rate, 1, dev);
        if (rec == null) {
            micStatus = "Commentary mic unavailable";
            setStatus(status, false);
            return;
        }
        try {
            rec.startRecording();
        } catch (Throwable t) {
            rec.release();
            micStatus = "Commentary mic busy";
            setStatus(status, false);
            return;
        }
        micRing = new ShortRing(rate / 2);
        micActive = true;
        micStatus = "Mic: " + (dev != null ? describe(dev) : "default");
        setStatus(status, true);
        AppLog.i(TAG, "Commentary mic started (" + micStatus + ")");
        int chunk = rate / 200;
        short[] in = new short[chunk];
        short[] stereo = new short[chunk * 2];
        AudioTimestamp ts = new AudioTimestamp();
        long framesRead = 0;
        long silentChunks = 0;
        while (alive(gen)) {
            int n = rec.read(in, 0, in.length);
            if (n < 0) {
                AppLog.w(TAG, "Mic read error " + n);
                break;
            }
            if (n == 0) continue;
            long pts;
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                pts = (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / rate) / 1000L;
            } else {
                pts = System.nanoTime() / 1000L - (long) n * 1_000_000L / rate;
            }
            framesRead += n;
            int peak = 0;
            for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs((int) in[i]));
            synchronized (levels) {
                levels[2] = Math.max(levels[2], cfg.micMute ? 0f : peak / 32768f);
            }
            silentChunks = peak == 0 ? silentChunks + 1 : 0;
            if (silentChunks == 200 * 10) {
                AppLog.w(TAG, "Commentary mic delivered only silence for 10 s; this phone may not allow two inputs at once");
            }
            if (gameActive) {
                ShortRing r = micRing;
                if (r != null) {
                    if (r.available() > rate / 10) r.skip(r.available() - rate / 20);
                    r.write(in, 0, n);
                }
            } else {
                // No capture-card audio: the mic alone feeds recordings.
                PcmSink s = sink;
                if (s != null) {
                    float g = cfg.micMute ? 0f : cfg.micGain;
                    for (int i = 0; i < n; i++) {
                        short v = clip((int) (in[i] * g));
                        stereo[2 * i] = v;
                        stereo[2 * i + 1] = v;
                    }
                    outRate = rate;
                    s.onPcm(stereo, n, rate, pts);
                }
            }
        }
        micActive = false;
        try {
            rec.stop();
        } catch (Throwable ignored) {
        }
        rec.release();
    }
}
