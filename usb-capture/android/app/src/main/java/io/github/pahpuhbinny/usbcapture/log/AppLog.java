package io.github.pahpuhbinny.usbcapture.log;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The app's event log. Every message goes to logcat, to an in-memory ring
 * (shown in the log viewer and attached to crash reports) and to
 * files/logs/app.log, which rotates at 2 MB so it never fills the phone.
 */
public final class AppLog {
    public interface Listener {
        void onLine(String line);
    }

    private static final String TAG_PREFIX = "UsbCapture/";
    private static final int RING_SIZE = 800;
    private static final long MAX_FILE_BYTES = 2_000_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");

    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(20000);
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Object fileLock = new Object();
    private static volatile File logDir;
    private static volatile File logFile;
    private static Thread writerThread;

    private AppLog() {
    }

    public static synchronized void init(Context context) {
        if (writerThread != null) return;
        File dir = new File(context.getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        logDir = dir;
        logFile = new File(dir, "app.log");
        writerThread = new Thread(AppLog::writerLoop, "applog-writer");
        writerThread.setDaemon(true);
        writerThread.setPriority(Thread.MIN_PRIORITY);
        writerThread.start();
    }

    public static File logDir() {
        return logDir;
    }

    public static File logFile() {
        return logFile;
    }

    public static File previousLogFile() {
        return logDir == null ? null : new File(logDir, "app.1.log");
    }

    public static void d(String tag, String msg) {
        log(Log.DEBUG, 'D', tag, msg, null);
    }

    public static void i(String tag, String msg) {
        log(Log.INFO, 'I', tag, msg, null);
    }

    public static void w(String tag, String msg) {
        log(Log.WARN, 'W', tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        log(Log.WARN, 'W', tag, msg, t);
    }

    public static void e(String tag, String msg) {
        log(Log.ERROR, 'E', tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        log(Log.ERROR, 'E', tag, msg, t);
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    public static List<String> recent() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    public static List<String> recent(int max) {
        synchronized (ring) {
            List<String> all = new ArrayList<>(ring);
            return all.size() <= max ? all : all.subList(all.size() - max, all.size());
        }
    }

    private static void log(int priority, char level, String tag, String msg, Throwable t) {
        if (msg == null) msg = "null";
        String full = t == null ? msg : msg + "\n" + Log.getStackTraceString(t);
        try {
            Log.println(priority, TAG_PREFIX + tag, full);
        } catch (Throwable ignored) {
            // logcat can refuse very long lines; the file log still gets it
        }
        String line = LocalDateTime.now().format(TIME) + " " + level + " " + tag + ": " + full;
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > RING_SIZE) ring.removeFirst();
        }
        queue.offer(line);
        for (Listener l : listeners) {
            try {
                l.onLine(line);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Writes everything still queued straight to disk. Used by the crash handler. */
    public static void flushSync() {
        List<String> pending = new ArrayList<>();
        queue.drainTo(pending);
        if (pending.isEmpty() || logFile == null) return;
        synchronized (fileLock) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                for (String s : pending) {
                    w.write(s);
                    w.write('\n');
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static void clear() {
        synchronized (ring) {
            ring.clear();
        }
        queue.clear();
        synchronized (fileLock) {
            if (logFile != null) //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            File prev = previousLogFile();
            if (prev != null) //noinspection ResultOfMethodCallIgnored
                prev.delete();
        }
    }

    private static void writerLoop() {
        BufferedWriter out = null;
        while (true) {
            try {
                String line = queue.poll(2, TimeUnit.SECONDS);
                if (line == null) {
                    if (out != null) {
                        out.flush();
                    }
                    continue;
                }
                synchronized (fileLock) {
                    if (out == null || !logFile.exists()) {
                        if (out != null) closeQuietly(out);
                        out = new BufferedWriter(new OutputStreamWriter(
                                new FileOutputStream(logFile, true), StandardCharsets.UTF_8), 16384);
                    }
                    out.write(line);
                    out.write('\n');
                    String more;
                    int n = 0;
                    while (n++ < 500 && (more = queue.poll()) != null) {
                        out.write(more);
                        out.write('\n');
                    }
                    out.flush();
                    if (logFile.length() > MAX_FILE_BYTES) {
                        closeQuietly(out);
                        out = null;
                        File prev = previousLogFile();
                        //noinspection ResultOfMethodCallIgnored
                        prev.delete();
                        //noinspection ResultOfMethodCallIgnored
                        logFile.renameTo(prev);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.e(TAG_PREFIX + "AppLog", "log writer error", t);
                if (out != null) closeQuietly(out);
                out = null;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void closeQuietly(Writer w) {
        try {
            w.close();
        } catch (IOException ignored) {
        }
    }
}
