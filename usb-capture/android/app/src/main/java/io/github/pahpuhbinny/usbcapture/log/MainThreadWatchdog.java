package io.github.pahpuhbinny.usbcapture.log;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Detects when the UI thread stops responding (a "hiccup" the user would
 * see as frozen controls) and writes where it was stuck to the log,
 * long before Android would declare an ANR.
 */
public final class MainThreadWatchdog extends Thread {
    private static final String TAG = "Watchdog";
    private static final long POLL_MS = 500;
    private static final long STALL_MS = 2000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long lastPong;

    private MainThreadWatchdog() {
        super("ui-watchdog");
        setDaemon(true);
        setPriority(Thread.MIN_PRIORITY + 1);
    }

    public static void start(String reason) {
        new MainThreadWatchdog().start();
        AppLog.d(TAG, "UI watchdog started (" + reason + ")");
    }

    @Override
    public void run() {
        long pendingSince = 0;
        boolean reported = false;
        int stalls = 0;
        while (true) {
            if (pendingSince == 0) {
                pendingSince = SystemClock.uptimeMillis();
                main.post(() -> lastPong = SystemClock.uptimeMillis());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (lastPong >= pendingSince) {
                if (reported) {
                    AppLog.i(TAG, "UI thread responsive again after " + (lastPong - pendingSince) + " ms");
                }
                reported = false;
                pendingSince = 0;
                continue;
            }
            long blocked = SystemClock.uptimeMillis() - pendingSince;
            if (blocked >= STALL_MS && !reported) {
                reported = true;
                stalls++;
                StringBuilder sb = new StringBuilder();
                sb.append("UI thread blocked for ").append(blocked).append(" ms (stall #").append(stalls).append("). Stack:\n");
                for (StackTraceElement el : Looper.getMainLooper().getThread().getStackTrace()) {
                    sb.append("    at ").append(el).append('\n');
                }
                AppLog.w(TAG, sb.toString());
            }
        }
    }
}
