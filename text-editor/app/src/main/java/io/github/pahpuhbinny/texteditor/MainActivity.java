package io.github.pahpuhbinny.texteditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A single-screen editor. Text files (JSON, HTML, XML, code, logs…) open as
 * text; anything that is not valid text opens as editable hex.
 */
public class MainActivity extends Activity {
    /** Larger text files open as a read-only preview of their first part. */
    private static final int TEXT_LIMIT = 1024 * 1024;
    /** Larger binary files open as a read-only preview of their first part. */
    private static final int HEX_LIMIT = 64 * 1024;

    private static final int REQUEST_OPEN = 1;
    private static final int REQUEST_SAVE_AS = 2;

    private EditText editor;
    private TextView status;
    private View findBar;
    private EditText findInput;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** The open document, or null for a new file that has not been saved. */
    private Uri uri;
    private String name;
    private boolean hexMode;
    private Charset charset = StandardCharsets.UTF_8;
    private boolean bom;
    private boolean crlf;
    /** The editor holds only the first part of a large file, so it cannot be saved. */
    private boolean preview;
    private long fileSize;
    private boolean dirty;
    private boolean wordWrap = true;
    /** True while the app itself changes the editor text. */
    private boolean settingText;
    /** Counts loads, so that a slow one cannot replace a newer one. */
    private int loadId;
    /** Bytes waiting to be written once the user has picked a "Save as" location. */
    private byte[] pendingSave;
    /** Runs after the next successful save (used by "Save, then open another file"). */
    private Runnable afterSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        editor = findViewById(R.id.editor);
        status = findViewById(R.id.status);
        findBar = findViewById(R.id.find_bar);
        findInput = findViewById(R.id.find_input);

        // The text can be far larger than Android allows in saved state.
        editor.setSaveEnabled(false);
        // While a preview is shown, undo every change the user tries to make.
        editor.setFilters(new InputFilter[] {(source, start, end, dest, dstart, dend) ->
                preview && !settingText ? dest.subSequence(dstart, dend) : null});
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!settingText && !dirty) {
                    dirty = true;
                    updateTitle();
                }
            }
        });

        findViewById(R.id.find_next).setOnClickListener(v -> findNext());
        findViewById(R.id.find_close).setOnClickListener(v -> showFindBar(false));
        findInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                findNext();
                return true;
            }
            return false;
        });

        newDocument();
        Uri incoming = uriFrom(getIntent());
        if (incoming != null) load(incoming);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri incoming = uriFrom(intent);
        if (incoming != null) confirmDiscard(() -> load(incoming));
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private static Uri uriFrom(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            return intent.getData();
        }
        return null;
    }

    // ---- Menu ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_save).setEnabled(!preview);
        menu.findItem(R.id.action_save_as).setEnabled(!preview);
        menu.findItem(R.id.action_hex).setChecked(hexMode);
        menu.findItem(R.id.action_wrap).setChecked(wordWrap).setEnabled(!hexMode);
        menu.findItem(R.id.action_format_json).setEnabled(!hexMode && !preview);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) {
            confirmDiscard(this::pickFile);
        } else if (id == R.id.action_save) {
            save(false);
        } else if (id == R.id.action_save_as) {
            save(true);
        } else if (id == R.id.action_new) {
            confirmDiscard(this::newDocument);
        } else if (id == R.id.action_find) {
            showFindBar(true);
        } else if (id == R.id.action_hex) {
            toggleHex();
        } else if (id == R.id.action_wrap) {
            wordWrap = !wordWrap;
            applyWrap();
        } else if (id == R.id.action_format_json) {
            formatJson();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    // ---- Opening ----

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri picked = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == REQUEST_OPEN) {
            if (picked != null) load(picked);
        } else if (requestCode == REQUEST_SAVE_AS) {
            byte[] bytes = pendingSave;
            pendingSave = null;
            if (picked != null && bytes != null) {
                write(picked, bytes);
            } else {
                afterSave = null;
            }
        }
    }

    private void newDocument() {
        loadId++;
        uri = null;
        name = getString(R.string.untitled);
        hexMode = false;
        charset = StandardCharsets.UTF_8;
        bom = false;
        crlf = false;
        preview = false;
        fileSize = 0;
        showContent("");
        dirty = false;
        updateTitle();
    }

    private void load(Uri source) {
        int id = ++loadId;
        status.setText(R.string.loading);
        io.execute(() -> {
            try {
                String displayName = queryName(source);
                long[] total = new long[1];
                byte[] data = read(source, TEXT_LIMIT, total);
                boolean cut = total[0] > data.length;
                FileCodec.Decoded decoded = FileCodec.decode(data);
                String text;
                if (decoded.isText) {
                    text = decoded.text;
                } else {
                    if (data.length > HEX_LIMIT) {
                        data = FileCodec.truncate(data, HEX_LIMIT);
                        cut = true;
                    }
                    text = FileCodec.toHex(data);
                }
                boolean isPreview = cut;
                runOnUiThread(() -> {
                    if (id != loadId || isDestroyed()) return;
                    uri = source;
                    name = displayName;
                    hexMode = !decoded.isText;
                    charset = decoded.charset;
                    bom = decoded.bom;
                    crlf = decoded.crlf;
                    preview = isPreview;
                    fileSize = total[0];
                    showContent(text);
                    dirty = false;
                    updateTitle();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (id != loadId || isDestroyed()) return;
                    updateStatus();
                    showError(getString(R.string.open_failed), e);
                });
            }
        });
    }

    /** Reads up to {@code limit} bytes and reports the file's full size in {@code total}. */
    private byte[] read(Uri source, int limit, long[] total) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("The file could not be opened.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long count = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                // One byte past the limit shows where a cut would fall.
                int keep = (int) Math.min(n, Math.max(0, limit + 1 - count));
                out.write(buffer, 0, keep);
                count += n;
            }
            total[0] = count;
            return FileCodec.truncate(out.toByteArray(), limit);
        }
    }

    private String queryName(Uri source) {
        try (Cursor c = getContentResolver().query(source,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // Not every app that shares a file answers this query.
        }
        String last = source.getLastPathSegment();
        return last != null ? last : getString(R.string.untitled);
    }

    // ---- Saving ----

    /** The editor's content as file bytes. */
    private byte[] currentBytes() throws ParseException {
        String text = editor.getText().toString();
        return hexMode ? FileCodec.fromHex(text) : FileCodec.encodeText(text, charset, bom, crlf);
    }

    private void save(boolean saveAs) {
        if (preview) return;
        byte[] bytes;
        try {
            bytes = currentBytes();
        } catch (ParseException e) {
            afterSave = null;
            showError(getString(R.string.save_failed), e);
            return;
        }
        if (saveAs || uri == null) {
            pickSaveLocation(bytes);
        } else {
            write(uri, bytes);
        }
    }

    private void pickSaveLocation(byte[] bytes) {
        pendingSave = bytes;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // A generic type stops the file picker from adding its own extension.
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, uri == null ? "untitled.txt" : name);
        startActivityForResult(intent, REQUEST_SAVE_AS);
    }

    private void write(Uri target, byte[] bytes) {
        io.execute(() -> {
            try {
                // "wt" replaces the old content instead of writing over its start.
                try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) throw new IOException("The file could not be opened.");
                    out.write(bytes);
                }
                String displayName = queryName(target);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    uri = target;
                    name = displayName;
                    fileSize = bytes.length;
                    dirty = false;
                    updateTitle();
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
                    Runnable next = afterSave;
                    afterSave = null;
                    if (next != null) next.run();
                });
            } catch (IOException | RuntimeException e) {
                // Files shared by another app are often read-only.
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.save_failed)
                            .setMessage(getString(R.string.save_failed_message, describe(e)))
                            .setPositiveButton(R.string.save_as, (d, w) -> pickSaveLocation(bytes))
                            .setNegativeButton(android.R.string.cancel, (d, w) -> afterSave = null)
                            .setOnCancelListener(d -> afterSave = null)
                            .show();
                });
            }
        });
    }

    /** Runs {@code action} now, or after asking what to do with unsaved changes. */
    private void confirmDiscard(Runnable action) {
        if (!dirty) {
            action.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.unsaved_message, name))
                .setPositiveButton(R.string.save, (d, w) -> {
                    afterSave = action;
                    save(false);
                })
                .setNegativeButton(R.string.discard, (d, w) -> action.run())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (findBar.getVisibility() == View.VISIBLE) {
            showFindBar(false);
        } else {
            confirmDiscard(this::finish);
        }
    }

    // ---- Editing ----

    private void toggleHex() {
        byte[] bytes;
        try {
            bytes = currentBytes();
        } catch (ParseException e) {
            showError(getString(R.string.hex_invalid), e);
            return;
        }
        boolean wasDirty = dirty;
        if (hexMode) {
            FileCodec.Decoded decoded = FileCodec.decode(bytes);
            if (!decoded.isText) decoded = FileCodec.decodeLatin1(bytes);
            hexMode = false;
            charset = decoded.charset;
            bom = decoded.bom;
            crlf = decoded.crlf;
            showContent(decoded.text);
        } else {
            if (bytes.length > HEX_LIMIT) {
                Toast.makeText(this, getString(R.string.too_big_for_hex,
                        Formatter.formatShortFileSize(this, HEX_LIMIT)), Toast.LENGTH_LONG).show();
                return;
            }
            hexMode = true;
            showContent(FileCodec.toHex(bytes));
        }
        dirty = wasDirty;
        updateTitle();
    }

    private void formatJson() {
        try {
            String formatted = FileCodec.formatJson(editor.getText().toString());
            // Replacing through the Editable keeps this one step for undo.
            editor.getText().replace(0, editor.length(), formatted);
            editor.setSelection(0);
        } catch (ParseException e) {
            showError(getString(R.string.json_invalid), e);
        }
    }

    private void showFindBar(boolean show) {
        findBar.setVisibility(show ? View.VISIBLE : View.GONE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (show) {
            findInput.requestFocus();
            findInput.selectAll();
            imm.showSoftInput(findInput, 0);
        } else {
            editor.requestFocus();
        }
    }

    private void findNext() {
        String query = findInput.getText().toString().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return;
        String text = editor.getText().toString().toLowerCase(Locale.ROOT);
        int from = Math.max(0, editor.getSelectionEnd());
        int at = text.indexOf(query, from);
        if (at < 0) at = text.indexOf(query); // wrap around to the top
        // Lower-casing can change a string's length in a few languages.
        if (at < 0 || at + query.length() > editor.length()) {
            Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show();
            return;
        }
        editor.requestFocus();
        editor.setSelection(at, at + query.length());
    }

    // ---- Display ----

    private void showContent(String text) {
        settingText = true;
        editor.setText(text);
        settingText = false;
        editor.setSelection(0);
        // A preview is only part of the file, so editing it is turned off.
        // (the input filter set in onCreate rejects changes).
        editor.setShowSoftInputOnFocus(!preview);
        applyWrap();
        invalidateOptionsMenu();
    }

    private void applyWrap() {
        editor.setHorizontallyScrolling(hexMode || !wordWrap);
        editor.requestLayout();
    }

    private void updateTitle() {
        setTitle((dirty ? "• " : "") + name);
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        if (hexMode) {
            sb.append(getString(R.string.mode_hex));
        } else {
            sb.append(charset.name());
            if (bom) sb.append(" BOM");
            sb.append(" · ").append(crlf ? "CRLF" : "LF");
        }
        if (uri != null) {
            sb.append(" · ").append(Formatter.formatShortFileSize(this, fileSize));
        }
        if (preview) {
            sb.append(" · ").append(getString(R.string.preview_note,
                    Formatter.formatShortFileSize(this, hexMode ? HEX_LIMIT : TEXT_LIMIT)));
        }
        status.setText(sb);
    }

    private void showError(String title, Exception e) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(describe(e))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }
}
