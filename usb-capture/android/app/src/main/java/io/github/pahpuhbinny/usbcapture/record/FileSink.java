package io.github.pahpuhbinny.usbcapture.record;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/** Writes encoder output into one MP4 file, starting at a key frame. */
final class FileSink implements EncoderSession.Sink {
    private static final String TAG = "FileSink";
    /** Start a new file before MP4's 4 GB limit for 32-bit offsets. */
    static final long SPLIT_BYTES = 3_800_000_000L;

    interface Callback {
        void onFirstKeyFrame(FileSink s);

        void onSizeLimit(FileSink s);
    }

    final MediaSaver.Output out;
    private final Context ctx;
    private final boolean expectAudio;
    private final Callback cb;
    private final MediaMuxer muxer;
    private MediaFormat vf, af;
    private int vt = -1, at = -1;
    private boolean started, gotKey, finished, failed, limitSignalled;
    private long baseUs = -1, lastV = -1, lastA = -1;
    private long bytes;
    private long durationUs;
    private long samples;

    static final class Result {
        boolean ok;
        String location;
        long durationUs;
        long bytes;
        String error;
    }

    FileSink(Context ctx, MediaSaver.Output out, boolean expectAudio, Callback cb) throws IOException {
        this.ctx = ctx;
        this.out = out;
        this.expectAudio = expectAudio;
        this.cb = cb;
        this.muxer = new MediaMuxer(out.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    @Override
    public synchronized void onFormat(boolean video, MediaFormat format) {
        if (finished) return;
        if (video && vf == null) vf = format;
        if (!video && af == null) af = format;
        if (started || vf == null || (expectAudio && af == null)) return;
        try {
            vt = muxer.addTrack(vf);
            if (expectAudio) at = muxer.addTrack(af);
            muxer.start();
            started = true;
        } catch (Throwable t) {
            AppLog.e(TAG, "Could not start MP4 writer", t);
            failed = true;
        }
    }

    @Override
    public void onSample(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
        boolean firstKey = false;
        boolean limit = false;
        synchronized (this) {
            if (finished || !started || failed) return;
            if (video && !gotKey) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) return;
                gotKey = true;
                baseUs = info.presentationTimeUs;
                firstKey = true;
            }
            if (!gotKey) return;
            int track = video ? vt : at;
            if (track < 0) return;
            long pts = info.presentationTimeUs - baseUs;
            if (pts < 0) return;
            long last = video ? lastV : lastA;
            if (pts <= last) pts = last + 1;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            bi.set(info.offset, info.size, pts, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            try {
                muxer.writeSampleData(track, data, bi);
            } catch (Throwable t) {
                AppLog.e(TAG, "Writing to " + out.name + " failed", t);
                failed = true;
                return;
            }
            if (video) lastV = pts;
            else lastA = pts;
            bytes += info.size;
            samples++;
            durationUs = Math.max(durationUs, pts);
            if (bytes > SPLIT_BYTES && !limitSignalled) {
                limitSignalled = true;
                limit = true;
            }
        }
        if (firstKey) cb.onFirstKeyFrame(this);
        if (limit) cb.onSizeLimit(this);
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized boolean failed() {
        return failed;
    }

    synchronized Result finish() {
        Result r = new Result();
        if (finished) {
            r.error = "already finished";
            return r;
        }
        finished = true;
        boolean ok = started && gotKey && samples > 0;
        if (started) {
            try {
                muxer.stop();
            } catch (Throwable t) {
                AppLog.e(TAG, "Finishing MP4 failed", t);
                ok = false;
            }
        }
        try {
            muxer.release();
        } catch (Throwable ignored) {
        }
        MediaSaver.finish(ctx, out, ok);
        r.ok = ok;
        r.location = out.location();
        r.durationUs = durationUs;
        r.bytes = bytes;
        if (!ok) r.error = started ? "No video frames were recorded" : "The recording never started (no video from the encoder)";
        AppLog.i(TAG, (ok ? "Saved " : "Discarded ") + out.name + " (" + bytes / 1024 + " KiB, " + durationUs / 1000 + " ms)");
        return r;
    }
}
