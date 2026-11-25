package io.github.pahpuhbinny.usbcapture.record;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.Locale;

import io.github.pahpuhbinny.usbcapture.audio.AudioEngine;
import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.render.GlRenderer;

/**
 * Recording and instant replay. All work happens on a dedicated thread so
 * starting, stopping and saving never block the screen.
 *
 * Long recordings are split into parts before reaching 4 GB; the next
 * part starts at a key frame before the previous one is closed, so no
 * frames are lost between parts.
 */
public final class Recorder implements AudioEngine.PcmSink {
    private static final String TAG = "Recorder";
    private static final long MIN_FREE_BYTES = 300L * 1024 * 1024;

    public interface Listener {
        void onRecordingState(boolean recording);

        void onSaved(String what, String location, String error);

        void onRecorderMessage(String message);
    }

    /** What to encode. Width/height are the picture size after crop and rotation. */
    public static final class Config {
        public int width;
        public int height;
        public double fps;
        public boolean hevc;
        public int bitrate;       // 0 = automatic
        public int maxHeight;     // 0 = keep source size
        public boolean audio;
        public int sampleRate = 48000;

        boolean sameShape(Config o) {
            return o != null && o.width == width && o.height == height && Math.abs(o.fps - fps) < 0.01
                    && o.hevc == hevc && o.bitrate == bitrate && o.maxHeight == maxHeight && o.audio == audio
                    && o.sampleRate == sampleRate;
        }
    }

    private final Context ctx;
    private final GlRenderer renderer;
    private final Listener listener;
    private final Handler handler;

    private volatile EncoderSession session;
    private Config sessionCfg;
    private Config wanted;
    private FileSink file;
    private FileSink nextFile;
    private int part;
    private String baseName;
    private ReplayBuffer replay;
    private int replaySeconds = 30;
    private volatile boolean recording;
    private volatile long recordStartMs;
    private long bytesBefore;

    public Recorder(Context ctx, GlRenderer renderer, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.renderer = renderer;
        this.listener = listener;
        HandlerThread t = new HandlerThread("recorder");
        t.start();
        handler = new Handler(t.getLooper());
    }

    public boolean isRecording() {
        return recording;
    }

    public long recordingStartMs() {
        return recordStartMs;
    }

    public long recordedBytes() {
        FileSink f = file;
        return bytesBefore + (f != null ? f.bytes() : 0);
    }

    public boolean isReplayEnabled() {
        return replay != null;
    }

    public String encoderDescription() {
        EncoderSession s = session;
        if (s == null) return "Encoder idle";
        return String.format(Locale.US, "%s %dx%d %.0f fps %.1f Mbps", s.hevc ? "HEVC" : "H.264", s.width, s.height,
                s.fps, s.bitrate / 1e6);
    }

    @Override
    public void onPcm(short[] data, int frames, int sampleRate, long ptsUs) {
        EncoderSession s = session;
        if (s != null) s.queuePcm(data, frames, sampleRate, ptsUs);
    }

    /** Tells the recorder what the current picture looks like (null = no video). */
    public void configure(Config c) {
        handler.post(() -> {
            wanted = c;
            if (session != null && (c == null || !c.sameShape(sessionCfg))) {
                boolean hadReplay = replay != null;
                if (recording) {
                    finishRecording(c == null ? "The video stopped, so the recording was saved."
                            : "The video format changed, so the recording was saved and stopped.");
                }
                replay = null;
                stopSession();
                if (hadReplay) pendingReplay = true;
            }
            if (c != null && pendingReplay && replay == null) {
                pendingReplay = false;
                if (ensureSession()) {
                    replay = new ReplayBuffer(replaySeconds, session.bitrate);
                    session.addSink(replay);
                }
            }
        });
    }

    public void startRecording() {
        handler.post(() -> {
            if (recording) return;
            if (wanted == null) {
                listener.onSaved("Recording", null, "There is no video to record yet");
                return;
            }
            if (MediaSaver.freeBytes() < MIN_FREE_BYTES) {
                listener.onSaved("Recording", null, "Not enough free storage to record (need at least 300 MB)");
                return;
            }
            if (!ensureSession()) return;
            part = 1;
            baseName = MediaSaver.timestampName("USBCapture", "mp4");
            bytesBefore = 0;
            FileSink f = openFile(baseName);
            if (f == null) {
                if (replay == null) stopSession();
                return;
            }
            file = f;
            session.addSink(f);
            recording = true;
            recordStartMs = SystemClock.elapsedRealtime();
            listener.onRecordingState(true);
            AppLog.i(TAG, "Recording started: " + baseName);
            handler.postDelayed(spaceCheck, 5000);
        });
    }

    public void stopRecording() {
        handler.post(() -> {
            if (!recording) return;
            finishRecording(null);
            if (replay == null) stopSession();
        });
    }

    public void setReplay(boolean enabled, int seconds) {
        handler.post(() -> {
            replaySeconds = Math.max(5, Math.min(300, seconds));
            if (enabled) {
                if (replay != null) {
                    session.removeSink(replay);
                    replay = null;
                }
                if (wanted == null) {
                    pendingReplay = true; // starts once there is video (configure())
                    return;
                }
                if (!ensureSession()) return;
                replay = new ReplayBuffer(replaySeconds, session.bitrate);
                session.addSink(replay);
                AppLog.i(TAG, "Instant replay on (" + replaySeconds + " s)");
            } else {
                pendingReplay = false;
                if (replay != null && session != null) session.removeSink(replay);
                replay = null;
                if (!recording) stopSession();
            }
        });
    }

    private boolean pendingReplay;

    public void saveReplay() {
        handler.post(() -> {
            ReplayBuffer r = replay;
            if (r == null) {
                listener.onSaved("Replay", null, "Instant replay is off");
                return;
            }
            ReplayBuffer.Snapshot snap = r.snapshot();
            boolean audio = sessionCfg != null && sessionCfg.audio;
            new Thread(() -> {
                FileSink.Result res = ReplayBuffer.write(ctx, snap, audio);
                listener.onSaved("Replay", res.ok ? res.location : null, res.ok ? null : res.error);
            }, "replay-save").start();
        });
    }

    /** Stops everything and saves any recording in progress. Blocks until done (max ~5 s). */
    public void shutdown() {
        final Object done = new Object();
        final boolean[] finished = new boolean[1];
        handler.post(() -> {
            if (recording) finishRecording(null);
            replay = null;
            pendingReplay = false;
            stopSession();
            synchronized (done) {
                finished[0] = true;
                done.notifyAll();
            }
        });
        synchronized (done) {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!finished[0] && SystemClock.uptimeMillis() < deadline) {
                try {
                    done.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ (recorder thread)

    private boolean ensureSession() {
        if (session != null) return true;
        Config c = wanted;
        if (c == null) return false;
        int w = c.width, h = c.height;
        if (c.maxHeight > 0 && Math.min(w, h) > c.maxHeight) {
            double s = (double) c.maxHeight / Math.min(w, h);
            w = (int) Math.round(w * s / 2) * 2;
            h = (int) Math.round(h * s / 2) * 2;
        }
        try {
            EncoderSession s = EncoderSession.create(w, h, c.fps, c.hevc, c.bitrate, c.audio, c.sampleRate,
                    msg -> handler.post(() -> onEncoderError(msg)));
            s.start(renderer);
            session = s;
            sessionCfg = c;
            return true;
        } catch (Throwable t) {
            AppLog.e(TAG, "Could not start the encoder", t);
            listener.onSaved("Recording", null, t.getMessage());
            return false;
        }
    }

    private void stopSession() {
        EncoderSession s = session;
        session = null;
        sessionCfg = null;
        if (s != null) s.stop(renderer);
    }

    private FileSink openFile(String name) {
        try {
            MediaSaver.Output out = MediaSaver.createVideo(ctx, name);
            return new FileSink(ctx, out, session.audio, fileCallback);
        } catch (Throwable t) {
            AppLog.e(TAG, "Could not create " + name, t);
            listener.onSaved("Recording", null, "Could not create the video file: " + t.getMessage());
            return null;
        }
    }

    private final FileSink.Callback fileCallback = new FileSink.Callback() {
        @Override
        public void onFirstKeyFrame(FileSink s) {
            handler.post(() -> {
                if (s == nextFile && file != null) {
                    FileSink old = file;
                    session.removeSink(old);
                    bytesBefore += old.bytes();
                    file = nextFile;
                    nextFile = null;
                    new Thread(() -> {
                        FileSink.Result r = old.finish();
                        listener.onSaved("Recording part", r.ok ? r.location : null, r.ok ? null : r.error);
                    }, "file-finish").start();
                    AppLog.i(TAG, "Continuing recording in " + s.out.name);
                }
            });
        }

        @Override
        public void onSizeLimit(FileSink s) {
            handler.post(() -> {
                if (s != file || nextFile != null || session == null) return;
                part++;
                String name = baseName.replace(".mp4", "_part" + part + ".mp4");
                FileSink n = openFile(name);
                if (n != null) {
                    nextFile = n;
                    session.addSink(n);
                }
            });
        }
    };

    private void finishRecording(String reason) {
        handler.removeCallbacks(spaceCheck);
        FileSink f = file;
        FileSink n = nextFile;
        file = null;
        nextFile = null;
        recording = false;
        if (session != null) {
            if (f != null) session.removeSink(f);
            if (n != null) session.removeSink(n);
        }
        listener.onRecordingState(false);
        if (n != null) n.finish();
        if (f != null) {
            FileSink.Result r = f.finish();
            String err = r.ok ? null : r.error;
            listener.onSaved("Recording", r.ok ? r.location : null, err);
        }
        if (reason != null) listener.onRecorderMessage(reason);
    }

    private final Runnable spaceCheck = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            if (MediaSaver.freeBytes() < 150L * 1024 * 1024) {
                AppLog.w(TAG, "Storage almost full; stopping recording");
                finishRecording("Storage is almost full, so recording stopped.");
                if (replay == null) stopSession();
                return;
            }
            FileSink f = file;
            if (f != null && f.failed()) {
                finishRecording("Writing the video file failed; what was recorded so far was saved.");
                if (replay == null) stopSession();
                return;
            }
            handler.postDelayed(this, 5000);
        }
    };

    private void onEncoderError(String msg) {
        listener.onRecorderMessage(msg);
        if (recording) finishRecording(null);
        replay = null;
        stopSession();
    }
}
