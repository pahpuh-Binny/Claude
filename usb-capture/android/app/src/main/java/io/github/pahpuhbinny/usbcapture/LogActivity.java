package io.github.pahpuhbinny.usbcapture;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.log.CrashHandler;

/** Shows the event log and saved crash/ANR reports, and shares them. */
public final class LogActivity extends AppCompatActivity implements AppLog.Listener {
    public static final String EXTRA_SHOW_CRASHES = "show_crashes";
    private static final int MAX_CHARS = 400_000;

    private TextView text;
    private ScrollView scroll;
    private ChipGroup tabs;
    private int current; // 0 = live log, 1 = previous log, 2.. = reports
    private final List<File> reports = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.logRoot), (v, wi) -> {
            Insets in = wi.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(in.left, in.top, in.right, in.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        text = findViewById(R.id.logText);
        scroll = findViewById(R.id.logScroll);
        tabs = findViewById(R.id.logTabs);
        findViewById(R.id.btnShare).setOnClickListener(v -> share());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copy());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        buildTabs();
        boolean crashes = getIntent().getBooleanExtra(EXTRA_SHOW_CRASHES, false);
        select(crashes && !reports.isEmpty() ? 2 : 0);
        CrashHandler.markReportsSeen(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLog.addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLog.removeListener(this);
    }

    private void buildTabs() {
        tabs.removeAllViews();
        reports.clear();
        reports.addAll(CrashHandler.allReports());
        List<String> names = new ArrayList<>();
        names.add("Event log");
        names.add("Previous log");
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (File f : reports) {
            String kind = f.getName().startsWith("anr") ? "Not responding"
                    : f.getName().startsWith("native") ? "Native crash"
                    : f.getName().startsWith("exit") ? "System exit" : "Crash";
            names.add(kind + " " + df.format(new Date(f.lastModified())));
        }
        for (int i = 0; i < names.size(); i++) {
            Chip c = new Chip(this);
            c.setText(names.get(i));
            c.setCheckable(true);
            c.setCheckedIconVisible(false);
            c.setId(View.generateViewId());
            final int idx = i;
            c.setOnClickListener(v -> select(idx));
            tabs.addView(c);
        }
    }

    private void select(int idx) {
        current = Math.max(0, Math.min(idx, tabs.getChildCount() - 1));
        for (int i = 0; i < tabs.getChildCount(); i++) ((Chip) tabs.getChildAt(i)).setChecked(i == current);
        String body;
        if (current == 0) {
            StringBuilder sb = new StringBuilder();
            for (String l : AppLog.recent()) sb.append(l).append('\n');
            body = sb.length() == 0 ? "(empty)" : sb.toString();
        } else if (current == 1) {
            File prev = AppLog.previousLogFile();
            File cur = AppLog.logFile();
            StringBuilder sb = new StringBuilder();
            if (prev != null && prev.exists()) sb.append(CrashHandler.readFile(prev, MAX_CHARS / 2));
            if (cur != null && cur.exists()) sb.append(CrashHandler.readFile(cur, MAX_CHARS / 2));
            body = sb.length() == 0 ? "(no saved log yet)" : sb.toString();
        } else {
            File f = reports.get(current - 2);
            body = CrashHandler.readFile(f, MAX_CHARS);
        }
        if (body.length() > MAX_CHARS) body = "…\n" + body.substring(body.length() - MAX_CHARS);
        text.setText(body);
        if (current <= 1) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        else scroll.post(() -> scroll.scrollTo(0, 0));
    }

    @Override
    public void onLine(String line) {
        runOnUiThread(() -> {
            if (current != 0) return;
            boolean atBottom = scroll.getChildAt(0).getBottom() - (scroll.getHeight() + scroll.getScrollY()) < 200;
            text.append(line + "\n");
            if (text.length() > MAX_CHARS) select(0);
            else if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void copy() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence t = text.getText();
        if (t.length() > 200_000) t = t.subSequence(t.length() - 200_000, t.length());
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("USB Capture log", t));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        try {
            AppLog.flushSync();
            ArrayList<Uri> uris = new ArrayList<>();
            File dir = AppLog.logDir();
            File diag = new File(dir, "diagnostics.txt");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(diag), StandardCharsets.UTF_8)) {
                w.write("USB Capture diagnostics\n");
                w.write(CrashHandler.deviceSummary());
                w.write("\nReports: " + reports.size() + "\n");
            }
            String authority = getPackageName() + ".files";
            uris.add(FileProvider.getUriForFile(this, authority, diag));
            File log = AppLog.logFile();
            if (log != null && log.exists()) uris.add(FileProvider.getUriForFile(this, authority, log));
            File prev = AppLog.previousLogFile();
            if (prev != null && prev.exists()) uris.add(FileProvider.getUriForFile(this, authority, prev));
            for (File f : reports) uris.add(FileProvider.getUriForFile(this, authority, f));
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("text/plain");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.putExtra(Intent.EXTRA_SUBJECT, "USB Capture logs");
            i.putExtra(Intent.EXTRA_TEXT, "USB Capture logs and crash reports are attached.\n\n" + CrashHandler.deviceSummary());
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share logs"));
        } catch (Throwable t) {
            AppLog.e("Logs", "Sharing logs failed", t);
            Toast.makeText(this, "Couldn't share: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Clear logs?")
                .setMessage("This deletes the event log and all saved crash reports.")
                .setPositiveButton("Clear", (d, w) -> {
                    AppLog.clear();
                    for (File f : CrashHandler.allReports()) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    AppLog.i("Logs", "Logs cleared by the user");
                    buildTabs();
                    select(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
