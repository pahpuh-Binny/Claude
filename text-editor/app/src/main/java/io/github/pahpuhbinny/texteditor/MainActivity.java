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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A single-screen editor. Text files (JSON, HTML, XML, code, logs…) open as
 * text; anything that is not valid text opens as editable hex. CSV files and
 * Excel workbooks (.xlsx) also open as a table.
 */
public class MainActivity extends Activity {
    /** Larger text files open as a read-only preview of their first part. */
    private static final int TEXT_LIMIT = 1024 * 1024;
    /** Larger binary files open as a read-only preview of their first part. */
    private static final int HEX_LIMIT = 64 * 1024;

    private static final int REQUEST_OPEN = 1;
    private static final int REQUEST_SAVE_AS = 2;
    private static final int REQUEST_EXPORT = 3;
    /** Larger workbooks are not read as tables. */
    private static final long WORKBOOK_LIMIT = 64L * 1024 * 1024;

    private EditText editor;
    private TextView status;
    private View findBar;
    private EditText findInput;
    private View tableScroll;
    private SheetTable table;

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
    /** The table is shown instead of the editor. */
    private boolean tableMode;
    /** The sheets of the open Excel workbook, or null for any other file. */
    private List<Xlsx.Sheet> workbook;
    private int sheetIndex;
    /** The rows of the open CSV file while it is shown as a table. */
    private List<String[]> csvRows;
    private char csvDelimiter = ',';
    private boolean csvTrailingNewline;
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

        tableScroll = findViewById(R.id.table_scroll);
        table = new SheetTable(this, (HorizontalScrollView) tableScroll,
                (GridRowView) findViewById(R.id.table_header),
                (ListView) findViewById(R.id.table_list), this::onCellClick);

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
        boolean sheets = tableMode && workbook != null;
        menu.findItem(R.id.action_sheet).setVisible(sheets && workbook.size() > 1);
        menu.findItem(R.id.action_export_csv).setVisible(sheets);
        menu.findItem(R.id.action_table).setChecked(tableMode)
                .setEnabled(workbook != null || !hexMode);
        menu.findItem(R.id.action_hex).setChecked(hexMode && !tableMode);
        menu.findItem(R.id.action_wrap).setChecked(wordWrap).setEnabled(!hexMode && !tableMode);
        menu.findItem(R.id.action_format_json).setEnabled(!hexMode && !preview && !tableMode);
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
        } else if (id == R.id.action_table) {
            if (tableMode) {
                setTableVisible(false);
            } else {
                enterTable();
            }
        } else if (id == R.id.action_sheet) {
            chooseSheet();
        } else if (id == R.id.action_export_csv) {
            exportSheet();
        } else if (id == R.id.action_hex) {
            if (tableMode) {
                // Leaving the table shows what is underneath: hex for a workbook.
                setTableVisible(false);
                if (!hexMode) toggleHex();
            } else {
                toggleHex();
            }
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
        } else if (requestCode == REQUEST_EXPORT) {
            byte[] bytes = pendingSave;
            pendingSave = null;
            if (picked != null && bytes != null) writeExport(picked, bytes);
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
        workbook = null;
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
                List<Xlsx.Sheet> sheets = decoded.isText ? null : readWorkbook(source, displayName);
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
                    workbook = sheets;
                    sheetIndex = 0;
                    showContent(text);
                    dirty = false;
                    // Spreadsheets start as a table; the menu switches to text or hex.
                    if (sheets != null || (decoded.isText && isCsvName(displayName)
                            && !text.isEmpty())) {
                        enterTable();
                    }
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

    private static boolean isCsvName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".tab");
    }

    /**
     * Reads an Excel workbook's sheets, or returns null if the file is not one
     * or cannot be read (it then stays open as hex).
     */
    private List<Xlsx.Sheet> readWorkbook(Uri source, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String mime = getContentResolver().getType(source);
        boolean isWorkbook = lower.endsWith(".xlsx") || lower.endsWith(".xlsm")
                || (mime != null && mime.contains("spreadsheetml"));
        if (!isWorkbook) return null;
        // Zip files are read from the end, so the workbook needs a real file.
        File copy = new File(getCacheDir(), "workbook.tmp");
        try (InputStream in = getContentResolver().openInputStream(source);
                OutputStream out = new FileOutputStream(copy)) {
            if (in == null) return null;
            byte[] buffer = new byte[64 * 1024];
            long count = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                count += n;
                if (count > WORKBOOK_LIMIT) return null;
                out.write(buffer, 0, n);
            }
            out.flush();
            List<Xlsx.Sheet> sheets = Xlsx.read(copy);
            return sheets == null || sheets.isEmpty() ? null : sheets;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            copy.delete();
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

    // ---- Table view ----

    /** The rows the table is showing. */
    private List<String[]> tableRows() {
        return workbook != null ? workbook.get(sheetIndex).rows : csvRows;
    }

    private void enterTable() {
        if (workbook != null) {
            table.setData(workbook.get(sheetIndex).rows);
        } else if (hexMode) {
            return;
        } else {
            String text = editor.getText().toString();
            csvDelimiter = Csv.detectDelimiter(text);
            csvTrailingNewline = text.endsWith("\n") || text.endsWith("\r");
            csvRows = Csv.parse(text, csvDelimiter);
            if (csvRows.isEmpty()) {
                Toast.makeText(this, R.string.table_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            table.setData(csvRows);
        }
        setTableVisible(true);
    }

    private void setTableVisible(boolean visible) {
        tableMode = visible;
        tableScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        editor.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible) {
            getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
        invalidateOptionsMenu();
        updateStatus();
    }

    private void chooseSheet() {
        String[] names = new String[workbook.size()];
        for (int i = 0; i < names.length; i++) names[i] = workbook.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_sheet)
                .setSingleChoiceItems(names, sheetIndex, (dialog, which) -> {
                    dialog.dismiss();
                    sheetIndex = which;
                    enterTable();
                })
                .show();
    }

    /** Shows a cell's full content. Cells of a CSV file can be changed here. */
    private void onCellClick(int row, int column) {
        List<String[]> rows = tableRows();
        String[] cells = rows.get(row);
        String value = column < cells.length ? cells[column] : "";
        boolean editable = workbook == null && !preview;

        EditText input = new EditText(this);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMaxLines(8);
        input.setText(value);
        if (!editable) {
            // The text can still be selected and copied, but not changed.
            input.setKeyListener(null);
            input.setTextIsSelectable(true);
            input.setBackground(null);
        }
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(Xlsx.columnName(column) + (row + 1))
                .setView(frame);
        if (editable) {
            dialog.setNegativeButton(android.R.string.cancel, null);
            dialog.setPositiveButton(android.R.string.ok, (d, w) -> {
                String changed = input.getText().toString();
                if (changed.equals(value)) return;
                String[] target = cells;
                if (column >= target.length) {
                    target = Arrays.copyOf(cells, column + 1);
                    Arrays.fill(target, cells.length, target.length, "");
                    rows.set(row, target);
                }
                target[column] = changed;
                // The editor's text is what gets saved, so rebuild it from the table.
                editor.setText(Csv.write(rows, csvDelimiter, csvTrailingNewline));
                table.refresh();
                updateStatus();
            });
        } else {
            dialog.setPositiveButton(android.R.string.ok, null);
        }
        dialog.show();
    }

    private void exportSheet() {
        Xlsx.Sheet sheet = workbook.get(sheetIndex);
        pendingSave = Csv.write(sheet.rows, ',', true).getBytes(StandardCharsets.UTF_8);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, base + " - " + sheet.name + ".csv");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    /** Writes an exported copy without changing which file is open. */
    private void writeExport(Uri target, byte[] bytes) {
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) throw new IOException("The file could not be opened.");
                out.write(bytes);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) showError(getString(R.string.export_failed), e);
                });
            }
        });
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
            imm.hideSoftInputFromWindow(findInput.getWindowToken(), 0);
            if (!tableMode) editor.requestFocus();
        }
    }

    private void findNext() {
        String query = findInput.getText().toString().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return;
        if (tableMode) {
            if (!table.findNext(query)) {
                Toast.makeText(this, R.string.not_found, Toast.LENGTH_SHORT).show();
            }
            return;
        }
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
        setTableVisible(false);
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
        if (tableMode && workbook != null) {
            Xlsx.Sheet sheet = workbook.get(sheetIndex);
            sb.append(sheet.name);
            if (workbook.size() > 1) {
                sb.append(" (").append(sheetIndex + 1).append('/').append(workbook.size())
                        .append(')');
            }
            sb.append(" · ").append(getString(R.string.workbook_read_only));
            if (sheet.truncated) sb.append(" · ").append(getString(R.string.sheet_cut));
        } else if (hexMode) {
            sb.append(getString(R.string.mode_hex));
        } else {
            sb.append(charset.name());
            if (bom) sb.append(" BOM");
            sb.append(" · ").append(crlf ? "CRLF" : "LF");
        }
        if (uri != null) {
            sb.append(" · ").append(Formatter.formatShortFileSize(this, fileSize));
        }
        if (tableMode) {
            int columns = table.columnCount();
            sb.append(" · ").append(getString(R.string.table_size, table.rowCount(), columns));
            if (columns > SheetTable.MAX_COLUMNS) {
                sb.append(" · ").append(getString(R.string.columns_cut, SheetTable.MAX_COLUMNS));
            }
        }
        if (preview && !(tableMode && workbook != null)) {
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
