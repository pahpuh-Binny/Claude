package io.github.pahpuhbinny.usbcapture.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.render.GlRenderer;

/**
 * A running pair of hardware encoders (video from the renderer's encoder
 * surface, AAC audio from PCM pushed in) whose output is fanned out to
 * any number of sinks: an MP4 file, the instant-replay buffer, or both.
 */
final class EncoderSession {
    private static final String TAG = "Encoder";

    interface Sink {
        void onFormat(boolean video, MediaFormat format);

        void onSample(boolean video, ByteBuffer data, MediaCodec.BufferInfo info);
    }

    interface ErrorListener {
        void onEncoderError(String message);
    }

    private static final class Pcm {
        final short[] data;
        final int frames;
        final long ptsUs;

        Pcm(short[] data, int frames, long ptsUs) {
            this.data = data;
            this.frames = frames;
            this.ptsUs = ptsUs;
        }
    }

    final int width;
    final int height;
    final double fps;
    final int bitrate;
    final boolean hevc;
    final boolean audio;
    final int sampleRate;
    final String codecName;

    private final MediaCodec venc;
    private MediaCodec aenc;
    private final Surface input;
    private volatile MediaFormat vFormat;
    private volatile MediaFormat aFormat;
    private final CopyOnWriteArrayList<Sink> sinks = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Pcm> pcm = new LinkedBlockingQueue<>(400);
    private final ErrorListener errors;
    private volatile boolean running;
    private volatile boolean stopping;
    private Thread vThread, aThread, feedThread;
    private volatile long videoFrames;
    private boolean warnedRate;

    private EncoderSession(MediaCodec venc, Surface input, String codecName, int w, int h, double fps, int bitrate,
                           boolean hevc, boolean audio, int sampleRate, ErrorListener errors) {
        this.venc = venc;
        this.input = input;
        this.codecName = codecName;
        this.width = w;
        this.height = h;
        this.fps = fps;
        this.bitrate = bitrate;
        this.hevc = hevc;
        this.audio = audio;
        this.sampleRate = sampleRate;
        this.errors = errors;
    }

    /** Picks an encoder that can do the requested size/rate, stepping down if needed. */
    static EncoderSession create(int w, int h, double fps, boolean hevc, int bitrate, boolean audio, int sampleRate,
                                 ErrorListener errors) throws IOException {
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        Choice c = choose(mime, w, h, fps);
        if (c == null && hevc) {
            AppLog.w(TAG, "No HEVC encoder fits " + w + "x" + h + "; using H.264");
            hevc = false;
            mime = MediaFormat.MIMETYPE_VIDEO_AVC;
            c = choose(mime, w, h, fps);
        }
        if (c == null) throw new IOException("This phone has no video encoder for " + w + "x" + h);
        int br = bitrate > 0 ? bitrate : autoBitrate(c.w, c.h, c.fps, hevc);
        if (c.bitrates != null) br = c.bitrates.clamp(br);

        MediaCodec venc = MediaCodec.createByCodecName(c.name);
        Surface surface;
        try {
            MediaFormat f = videoFormat(mime, c, br, true);
            try {
                venc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable first) {
                AppLog.w(TAG, "Encoder rejected tuned settings (" + first + "); retrying with basic ones");
                venc.reset();
                venc.configure(videoFormat(mime, c, br, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            surface = venc.createInputSurface();
            venc.start();
        } catch (Throwable t) {
            venc.release();
            throw new IOException("Video encoder failed to start: " + t.getMessage(), t);
        }
        EncoderSession s = new EncoderSession(venc, surface, c.name, c.w, c.h, c.fps, br, hevc, audio, sampleRate, errors);
        if (audio) {
            try {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 192_000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
                MediaCodec a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                a.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                a.start();
                s.aenc = a;
            } catch (Throwable t) {
                s.release();
                throw new IOException("Audio encoder failed to start: " + t.getMessage(), t);
            }
        }
        AppLog.i(TAG, String.format(Locale.US, "Encoder %s: %dx%d @ %.2f fps, %.1f Mbps %s%s",
                c.name, c.w, c.h, c.fps, br / 1e6, hevc ? "HEVC" : "H.264", audio ? " + AAC " + sampleRate + " Hz" : ""));
        return s;
    }

    static int autoBitrate(int w, int h, double fps, boolean hevc) {
        double bpp = hevc ? 0.08 : 0.12;
        long br = (long) (w * (double) h * Math.max(24, fps) * bpp);
        return (int) Math.max(2_000_000, Math.min(100_000_000, br));
    }

    private static MediaFormat videoFormat(String mime, Choice c, int br, boolean tuned) {
        MediaFormat f = MediaFormat.createVideoFormat(mime, c.w, c.h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, br);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, (int) Math.max(1, Math.round(c.fps)));
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (tuned) {
            if (c.vbr) f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (c.fps > 30) f.setInteger(MediaFormat.KEY_OPERATING_RATE, (int) Math.round(c.fps));
        }
        return f;
    }

    private static final class Choice {
        String name;
        int w, h;
        double fps;
        Range<Integer> bitrates;
        boolean vbr;
    }

    private static Choice choose(String mime, int w, int h, double fps) {
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        int[][] sizes = {{w, h}, scaled(w, h, 3840, 2160), scaled(w, h, 1920, 1080), scaled(w, h, 1280, 720)};
        double[] rates = fps > 31 ? new double[]{fps, 30} : new double[]{fps};
        for (int[] sz : sizes) {
            for (double r : rates) {
                for (int pass = 0; pass < 2; pass++) {
                    for (MediaCodecInfo info : infos) {
                        if (!info.isEncoder()) continue;
                        boolean hw = isHardware(info);
                        if ((pass == 0) != hw) continue;
                        boolean match = false;
                        for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) match = true;
                        if (!match) continue;
                        try {
                            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                            if (vc == null) continue;
                            int wa = Math.max(2, vc.getWidthAlignment());
                            int ha = Math.max(2, vc.getHeightAlignment());
                            int aw = sz[0] / wa * wa;
                            int ah = sz[1] / ha * ha;
                            if (aw <= 0 || ah <= 0) continue;
                            if (!vc.areSizeAndRateSupported(aw, ah, r)) continue;
                            Choice c = new Choice();
                            c.name = info.getName();
                            c.w = aw;
                            c.h = ah;
                            c.fps = r;
                            c.bitrates = vc.getBitrateRange();
                            MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
                            c.vbr = ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                            if (aw != w || ah != h || r != fps) {
                                AppLog.w(TAG, String.format(Locale.US, "Recording at %dx%d %.0f fps (the encoder can't do %dx%d %.0f fps)",
                                        aw, ah, r, w, h, fps));
                            }
                            return c;
                        } catch (Throwable t) {
                            AppLog.d(TAG, "Skipping encoder " + info.getName() + ": " + t);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated();
        String n = info.getName().toLowerCase(Locale.US);
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.");
    }

    private static int[] scaled(int w, int h, int maxLong, int maxShort) {
        boolean landscape = w >= h;
        int lw = landscape ? maxLong : maxShort;
        int lh = landscape ? maxShort : maxLong;
        double s = Math.min(1.0, Math.min((double) lw / w, (double) lh / h));
        return new int[]{(int) Math.round(w * s / 2) * 2, (int) Math.round(h * s / 2) * 2};
    }

    // ------------------------------------------------------------------

    void start(GlRenderer renderer) {
        running = true;
        renderer.setEncoderSurface(input, width, height);
        vThread = new Thread(() -> drain(venc, true), "enc-video");
        vThread.start();
        if (aenc != null) {
            aThread = new Thread(() -> drain(aenc, false), "enc-audio");
            aThread.start();
            feedThread = new Thread(this::feedLoop, "enc-audio-feed");
            feedThread.start();
        }
    }

    void stop(GlRenderer renderer) {
        stopping = true;
        renderer.setEncoderSurface(null, 0, 0);
        running = false;
        join(feedThread);
        try {
            venc.signalEndOfInputStream();
        } catch (Throwable t) {
            AppLog.d(TAG, "signalEndOfInputStream: " + t);
        }
        if (aenc != null) {
            try {
                int idx = aenc.dequeueInputBuffer(50_000);
                if (idx >= 0) aenc.queueInputBuffer(idx, 0, 0, lastAudioPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } catch (Throwable t) {
                AppLog.d(TAG, "audio EOS: " + t);
            }
        }
        join(vThread);
        join(aThread);
        release();
        AppLog.i(TAG, "Encoder stopped after " + videoFrames + " video frames");
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(2500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void release() {
        try {
            venc.stop();
        } catch (Throwable ignored) {
        }
        venc.release();
        if (aenc != null) {
            try {
                aenc.stop();
            } catch (Throwable ignored) {
            }
            aenc.release();
        }
        input.release();
    }

    void addSink(Sink s) {
        sinks.add(s);
        MediaFormat v = vFormat, a = aFormat;
        if (v != null) s.onFormat(true, v);
        if (a != null) s.onFormat(false, a);
        requestKeyFrame();
    }

    void removeSink(Sink s) {
        sinks.remove(s);
    }

    void requestKeyFrame() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            venc.setParameters(b);
        } catch (Throwable t) {
            AppLog.d(TAG, "Key frame request failed: " + t);
        }
    }

    long videoFrames() {
        return videoFrames;
    }

    /** Called from the audio thread. */
    void queuePcm(short[] data, int frames, int rate, long ptsUs) {
        if (!running || aenc == null) return;
        if (rate != sampleRate) {
            if (!warnedRate) {
                warnedRate = true;
                AppLog.w(TAG, "Audio sample rate changed to " + rate + " Hz; recording keeps " + sampleRate + " Hz with silence");
            }
            return;
        }
        short[] copy = new short[frames * 2];
        System.arraycopy(data, 0, copy, 0, frames * 2);
        if (!pcm.offer(new Pcm(copy, frames, ptsUs))) {
            pcm.poll();
            pcm.offer(new Pcm(copy, frames, ptsUs));
        }
    }

    private long lastAudioPts = -1;

    private void feedLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        } catch (Throwable ignored) {
        }
        long lastRealMs = 0;
        long nextPts = -1;
        short[] silence = new short[sampleRate / 50 * 2]; // 20 ms
        while (running) {
            Pcm p;
            try {
                p = pcm.poll(40, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            long nowUs = System.nanoTime() / 1000L;
            if (p == null) {
                if (SystemClock.uptimeMillis() - lastRealMs > 300) {
                    // No audio arriving: keep the audio track continuous with silence.
                    long pts = nextPts < 0 || nextPts < nowUs - 500_000 ? nowUs - 20_000 : nextPts;
                    if (pts <= nowUs) {
                        feed(silence, silence.length / 2, pts);
                        nextPts = pts + 20_000;
                    }
                }
                continue;
            }
            lastRealMs = SystemClock.uptimeMillis();
            long pts = p.ptsUs;
            if (nextPts > 0 && pts < nextPts) pts = nextPts;
            feed(p.data, p.frames, pts);
            nextPts = pts + (long) p.frames * 1_000_000L / sampleRate;
        }
    }

    private void feed(short[] data, int frames, long ptsUs) {
        int offset = 0;
        try {
            while (offset < frames && running) {
                int idx = aenc.dequeueInputBuffer(20_000);
                if (idx < 0) continue;
                ByteBuffer in = aenc.getInputBuffer(idx);
                if (in == null) continue;
                in.clear();
                int canFrames = Math.min(frames - offset, in.capacity() / 4);
                in.order(ByteOrder.nativeOrder()).asShortBuffer().put(data, offset * 2, canFrames * 2);
                long pts = ptsUs + (long) offset * 1_000_000L / sampleRate;
                aenc.queueInputBuffer(idx, 0, canFrames * 4, pts, 0);
                lastAudioPts = pts;
                offset += canFrames;
            }
        } catch (Throwable t) {
            if (running) {
                AppLog.e(TAG, "Audio encoder input failed", t);
                errors.onEncoderError("Audio encoder failed: " + t.getMessage());
                running = false;
            }
        }
    }

    private void drain(MediaCodec codec, boolean video) {
        try {
            Process.setThreadPriority(video ? Process.THREAD_PRIORITY_DISPLAY : Process.THREAD_PRIORITY_AUDIO);
        } catch (Throwable ignored) {
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long stopDeadline = 0;
        try {
            while (true) {
                int idx = codec.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (video) vFormat = f;
                    else aFormat = f;
                    AppLog.d(TAG, (video ? "Video" : "Audio") + " output format: " + f);
                    for (Sink s : sinks) s.onFormat(video, f);
                } else if (idx >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(idx);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (b != null && !config && info.size > 0) {
                        if (video) videoFrames++;
                        for (Sink s : sinks) {
                            try {
                                s.onSample(video, b, info);
                            } catch (Throwable t) {
                                AppLog.e(TAG, "Sink failed", t);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (stopping) {
                        if (stopDeadline == 0) stopDeadline = SystemClock.uptimeMillis() + 1500;
                        else if (SystemClock.uptimeMillis() > stopDeadline) break;
                    }
                }
            }
        } catch (Throwable t) {
            if (!stopping) {
                AppLog.e(TAG, (video ? "Video" : "Audio") + " encoder failed", t);
                errors.onEncoderError((video ? "Video" : "Audio") + " encoder failed: " + t.getMessage());
            }
        }
    }
}
