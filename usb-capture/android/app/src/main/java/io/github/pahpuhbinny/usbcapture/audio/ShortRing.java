package io.github.pahpuhbinny.usbcapture.audio;

/** Thread-safe ring buffer of 16-bit samples. When full, the oldest samples are overwritten. */
final class ShortRing {
    private final short[] buf;
    private int read;
    private int count;

    ShortRing(int capacity) {
        buf = new short[capacity];
    }

    synchronized void write(short[] src, int off, int n) {
        if (n > buf.length) {
            off += n - buf.length;
            n = buf.length;
        }
        int overflow = count + n - buf.length;
        if (overflow > 0) {
            read = (read + overflow) % buf.length;
            count -= overflow;
        }
        int w = (read + count) % buf.length;
        int first = Math.min(n, buf.length - w);
        System.arraycopy(src, off, buf, w, first);
        if (n > first) System.arraycopy(src, off + first, buf, 0, n - first);
        count += n;
        notifyAll();
    }

    /** Reads up to n samples; returns how many were read. */
    synchronized int read(short[] dst, int off, int n) {
        n = Math.min(n, count);
        int first = Math.min(n, buf.length - read);
        System.arraycopy(buf, read, dst, off, first);
        if (n > first) System.arraycopy(buf, 0, dst, off + first, n - first);
        read = (read + n) % buf.length;
        count -= n;
        return n;
    }

    synchronized void skip(int n) {
        n = Math.min(n, count);
        read = (read + n) % buf.length;
        count -= n;
    }

    synchronized int available() {
        return count;
    }

    synchronized void clear() {
        read = 0;
        count = 0;
    }

    synchronized boolean awaitAvailable(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (count < n) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    int capacity() {
        return buf.length;
    }
}
