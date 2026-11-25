package io.github.pahpuhbinny.usbcapture.log;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.pahpuhbinny.usbcapture.BuildConfig;
import io.github.pahpuhbinny.usbcapture.uvc.UvcNative;

/**
 * Saves a crash report whenever the app goes down, whatever the cause:
 * <ul>
 *   <li>Java exceptions nobody caught (any thread),</li>
 *   <li>native crashes (the C streaming engine installs a signal handler),</li>
 *   <li>and, on Android 11+, anything the system recorded about the last
 *       exit: ANRs ("app not responding"), being killed for low memory,
 *       native crashes, and so on.</li>
 * </ul>
 * Reports go to files/crashes/. The next launch offers to show them.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "Crash";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String PREFS = "crash_state";

    private static File crashDir;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
    }

    public static void install(Context context) {
        crashDir = new File(context.getFilesDir(), "crashes");
        //noinspection ResultOfMethodCallIgnored
        crashDir.mkdirs();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(Thread.getDefaultUncaughtExceptionHandler()));
        if (UvcNative.AVAILABLE) {
            try {
                UvcNative.nativeInstallCrashHandler(nativeCrashFile().getAbsolutePath());
            } catch (Throwable t) {
                AppLog.e(TAG, "Could not install native crash handler", t);
            }
        } else {
            AppLog.e(TAG, "Native library failed to load: " + UvcNative.loadError());
        }
    }

    public static File crashDir() {
        return crashDir;
    }

    private static File nativeCrashFile() {
        return new File(crashDir, "native-crash.txt");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("=== USB Capture crash report ===");
            pw.println(deviceSummary());
            pw.println("Thread: " + thread.getName());
            pw.println();
            error.printStackTrace(pw);
            pw.println();
            pw.println("--- Recent log ---");
            for (String line : AppLog.recent(300)) pw.println(line);
            pw.flush();
            writeReport("crash", sw.toString());
            AppLog.e(TAG, "Uncaught exception on thread " + thread.getName(), error);
            AppLog.flushSync();
        } catch (Throwable t) {
            Log.e("UsbCapture/" + TAG, "Failed to write crash report", t);
        }
        if (previous != null) {
            previous.uncaughtException(thread, error);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    public static String deviceSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("App: ").append(BuildConfig.APPLICATION_ID).append(' ').append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Time: ").append(LocalDateTime.now()).append('\n');
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("SoC: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
        }
        sb.append("Hardware: ").append(Build.HARDWARE).append('\n');
        return sb.toString();
    }

    private static File writeReport(String kind, String text) {
        if (crashDir == null) return null;
        File f = new File(crashDir, kind + "-" + LocalDateTime.now().format(FILE_TIME) + ".txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (IOException e) {
            Log.e("UsbCapture/" + TAG, "write report", e);
        }
        return f;
    }

    /**
     * Called once at startup. Turns a native crash file from the last run
     * into a dated report and imports the system's record of recent exits.
     */
    public static void collectPreviousRunReports(Context context) {
        try {
            File nf = nativeCrashFile();
            if (nf.exists() && nf.length() > 0) {
                String body = readFile(nf, 256 * 1024);
                StringBuilder sb = new StringBuilder();
                sb.append("=== USB Capture native crash report ===\n");
                sb.append(deviceSummary()).append('\n').append(body).append('\n');
                File prevLog = AppLog.logFile();
                if (prevLog != null && prevLog.exists()) {
                    sb.append("--- Log from the crashed session (tail) ---\n");
                    sb.append(tail(readFile(prevLog, 2 * 1024 * 1024), 300));
                }
                writeReport("native-crash", sb.toString());
                //noinspection ResultOfMethodCallIgnored
                nf.delete();
                AppLog.w(TAG, "The app crashed in native code last time; a report was saved");
            }
        } catch (Throwable t) {
            AppLog.e(TAG, "Could not process native crash file", t);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                importExitInfo(context);
            } catch (Throwable t) {
                AppLog.w(TAG, "Could not read previous exit reasons", t);
            }
        }
    }

    private static void importExitInfo(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long lastSeen = sp.getLong("last_exit_ts", 0);
        long newest = lastSeen;
        List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
        for (ApplicationExitInfo info : list) {
            long ts = info.getTimestamp();
            if (ts <= lastSeen) continue;
            newest = Math.max(newest, ts);
            int reason = info.getReason();
            String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).toString();
            String summary = "Previous run ended " + when + ": " + reasonName(reason)
                    + (info.getDescription() != null ? " (" + info.getDescription() + ")" : "")
                    + ", importance " + info.getImportance() + ", status " + info.getStatus();
            boolean bad = reason == ApplicationExitInfo.REASON_ANR
                    || reason == ApplicationExitInfo.REASON_CRASH
                    || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_LOW_MEMORY
                    || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
                    || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                    || reason == ApplicationExitInfo.REASON_DEPENDENCY_DIED;
            if (!bad) {
                AppLog.i(TAG, summary);
                continue;
            }
            AppLog.w(TAG, summary);
            StringBuilder sb = new StringBuilder();
            sb.append("=== System exit report ===\n").append(deviceSummary()).append('\n');
            sb.append(summary).append('\n');
            sb.append("PSS: ").append(info.getPss()).append(" kB, RSS: ").append(info.getRss()).append(" kB\n\n");
            if (reason == ApplicationExitInfo.REASON_ANR || reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                try (InputStream in = info.getTraceInputStream()) {
                    if (in != null) {
                        sb.append("--- Trace ---\n");
                        sb.append(readStream(in, 400 * 1024));
                    }
                } catch (IOException e) {
                    sb.append("(trace unavailable: ").append(e).append(")\n");
                }
            }
            writeReport(reason == ApplicationExitInfo.REASON_ANR ? "anr" : "exit", sb.toString());
        }
        sp.edit().putLong("last_exit_ts", newest).apply();
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_ANR: return "app not responding (ANR)";
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "closed normally";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "killed by system (low memory)";
            case ApplicationExitInfo.REASON_SIGNALED: return "killed by a signal";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "closed by the user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "force-stopped by the user";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "killed for excessive resource use";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission changed";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "failed to start";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "a dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "other system reason";
            default: return "reason " + r;
        }
    }

    /** Crash/ANR reports that the user hasn't been told about yet. */
    public static List<File> unseenReports(Context context) {
        List<File> out = new ArrayList<>();
        if (crashDir == null) return out;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long seen = sp.getLong("seen_until", 0);
        File[] files = crashDir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.getName().equals("native-crash.txt")) continue;
            if (f.lastModified() > seen) out.add(f);
        }
        return out;
    }

    public static void markReportsSeen(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("seen_until", System.currentTimeMillis()).apply();
    }

    public static List<File> allReports() {
        List<File> out = new ArrayList<>();
        if (crashDir == null) return out;
        File[] files = crashDir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().equals("native-crash.txt")) out.add(f);
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static String readFile(File f, int maxBytes) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            long len = f.length();
            if (len > maxBytes) {
                //noinspection ResultOfMethodCallIgnored
                in.skip(len - maxBytes);
            }
            return readStream(in, maxBytes);
        } catch (IOException e) {
            return "(could not read " + f.getName() + ": " + e + ")";
        }
    }

    private static String readStream(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bos.size() < maxBytes) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static String tail(String s, int lines) {
        String[] parts = s.split("\n");
        int start = Math.max(0, parts.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) sb.append(parts[i]).append('\n');
        return sb.toString();
    }
}
