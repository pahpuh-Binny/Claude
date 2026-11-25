package io.github.pahpuhbinny.usbcapture.record;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.github.pahpuhbinny.usbcapture.log.AppLog;

/**
 * Instant replay: keeps the last N seconds of encoded video and audio in
 * memory so "Save replay" can write them to a file after something
 * happened, like a game console's capture button.
 */
final class ReplayBuffer implements EncoderSession.Sink {
    private static final String TAG = "Replay";

    private static final class Sample {
        final byte[] data;
        final long pts;
        final int flags;
        final boolean video;

        Sample(byte[] data, long pts, int flags, boolean video) {
            this.data = data;
            this.pts = pts;
            this.flags = flags;
            this.video = video;
        }
    }

    static final class Snapshot {
        List<Sample> samples;
        MediaFormat vf, af;
    }

    private final ArrayDeque<Sample> q = new ArrayDeque<>();
    private final long windowUs;
    private final long maxBytes;
    private long bytes;
    private long newestUs;
    private MediaFormat vf, af;

    ReplayBuffer(int seconds, int bitrate) {
        windowUs = seconds * 1_000_000L;
        long estimate = (long) seconds * (bitrate / 8 + 24_000) * 3 / 2;
        long heapCap = Runtime.getRuntime().maxMemory() / 3;
        maxBytes = Math.max(8L * 1024 * 1024, Math.min(estimate, heapCap));
        AppLog.i(TAG, "Replay buffer: " + seconds + " s, up to " + maxBytes / (1024 * 1024) + " MiB");
    }

    @Override
    public synchronized void onFormat(boolean video, MediaFormat format) {
        if (video) vf = format;
        else af = format;
    }

    @Override
    public void onSample(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
        byte[] d = new byte[info.size];
        ByteBuffer dup = data.duplicate();
        dup.limit(info.offset + info.size);
        dup.position(info.offset);
        dup.get(d);
        synchronized (this) {
            q.addLast(new Sample(d, info.presentationTimeUs, info.flags, video));
            bytes += d.length;
            if (video) newestUs = Math.max(newestUs, info.presentationTimeUs);
            while (!q.isEmpty()) {
                Sample first = q.peekFirst();
                if (bytes <= maxBytes && newestUs - first.pts <= windowUs) break;
                q.removeFirst();
                bytes -= first.data.length;
            }
        }
    }

    synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.samples = new ArrayList<>(q);
        s.vf = vf;
        s.af = af;
        return s;
    }

    synchronized void clear() {
        q.clear();
        bytes = 0;
    }

    /** Writes a snapshot to a new MP4. Runs on a background thread. */
    static FileSink.Result write(Context ctx, Snapshot snap, boolean withAudio) {
        FileSink.Result r = new FileSink.Result();
        if (snap.vf == null || snap.samples.isEmpty()) {
            r.error = "Nothing to save yet";
            return r;
        }
        int start = -1;
        for (int i = 0; i < snap.samples.size(); i++) {
            Sample s = snap.samples.get(i);
            if (s.video && (s.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            r.error = "The replay buffer has no key frame yet; try again in a second";
            return r;
        }
        boolean audio = withAudio && snap.af != null;
        MediaSaver.Output out = null;
        MediaMuxer mux = null;
        boolean ok = false;
        try {
            out = MediaSaver.createVideo(ctx, MediaSaver.timestampName("Replay", "mp4"));
            mux = new MediaMuxer(out.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = mux.addTrack(snap.vf);
            int at = audio ? mux.addTrack(snap.af) : -1;
            mux.start();
            long base = snap.samples.get(start).pts;
            long lastV = -1, lastA = -1;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            for (int i = start; i < snap.samples.size(); i++) {
                Sample s = snap.samples.get(i);
                if (!s.video && !audio) continue;
                long pts = s.pts - base;
                if (pts < 0) continue;
                long last = s.video ? lastV : lastA;
                if (pts <= last) pts = last + 1;
                bi.set(0, s.data.length, pts, s.flags);
                mux.writeSampleData(s.video ? vt : at, ByteBuffer.wrap(s.data), bi);
                if (s.video) lastV = pts;
                else lastA = pts;
                r.bytes += s.data.length;
                r.durationUs = Math.max(r.durationUs, pts);
            }
            mux.stop();
            ok = true;
        } catch (Throwable t) {
            AppLog.e(TAG, "Saving replay failed", t);
            r.error = "Saving replay failed: " + t.getMessage();
        } finally {
            if (mux != null) {
                try {
                    mux.release();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) MediaSaver.finish(ctx, out, ok);
        }
        r.ok = ok;
        if (out != null) r.location = out.location();
        if (ok) AppLog.i(TAG, "Saved replay " + r.location + " (" + r.durationUs / 1000 + " ms)");
        return r;
    }
}
