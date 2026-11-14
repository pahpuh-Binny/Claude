package io.github.pahpuhbinny.texteditor;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ListView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shows rows of cells as a spreadsheet-style table that scrolls both ways,
 * with a fixed header of column letters.
 */
final class SheetTable {
    /** Columns past this are not shown, to keep wide sheets fast. */
    static final int MAX_COLUMNS = Xlsx.MAX_COLUMNS;
    private static final int MIN_CHARS = 4;
    private static final int MAX_CHARS = 24;
    /** Rows looked at when sizing the columns. */
    private static final int SAMPLE_ROWS = 300;

    interface Listener {
        void onCellClick(int row, int column);
    }

    private final HorizontalScrollView scroll;
    private final GridRowView header;
    private final ListView list;
    private final GridRowView.Style style;

    private List<String[]> rows = Collections.emptyList();
    private int columns;
    private int selectedRow = -1;
    private int selectedColumn = -1;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() {
            return rows.size();
        }

        @Override public Object getItem(int position) {
            return rows.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            GridRowView view = convertView instanceof GridRowView
                    ? (GridRowView) convertView : new GridRowView(parent.getContext());
            view.bind(style, String.valueOf(position + 1), rows.get(position), false,
                    position == selectedRow ? selectedColumn : -1);
            return view;
        }
    };

    SheetTable(Context context, HorizontalScrollView scroll, GridRowView header, ListView list,
            Listener listener) {
        this.scroll = scroll;
        this.header = header;
        this.list = list;
        style = new GridRowView.Style(context);
        list.setDividerHeight(0);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int column = ((GridRowView) view).touchedColumn();
            if (column >= 0) listener.onCellClick(position, column);
        });
    }

    /** Shows new rows from the top. The list is used as is, not copied. */
    void setData(List<String[]> rows) {
        this.rows = rows;
        selectedRow = -1;
        selectedColumn = -1;
        measureColumns();
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        scroll.scrollTo(0, 0);
    }

    /** Redraws after the rows passed to {@link #setData} have changed. */
    void refresh() {
        int before = columns;
        int widest = 0;
        for (String[] row : rows) widest = Math.max(widest, row.length);
        if (Math.min(widest, MAX_COLUMNS) != before) measureColumns();
        adapter.notifyDataSetChanged();
    }

    int rowCount() {
        return rows.size();
    }

    /** The number of columns in the widest row, including any that are not shown. */
    int columnCount() {
        int widest = 0;
        for (String[] row : rows) widest = Math.max(widest, row.length);
        return widest;
    }

    /**
     * Finds the next cell after the selected one that contains {@code query},
     * ignoring case, and scrolls to it. Wraps around to the top.
     */
    boolean findNext(String query) {
        if (rows.isEmpty() || columns == 0) return false;
        String needle = query.toLowerCase(Locale.ROOT);
        int total = rows.size();
        int startRow = Math.max(selectedRow, 0);
        // One extra pass covers the cells before the selection on its own row.
        for (int step = 0; step <= total; step++) {
            int r = (startRow + step) % total;
            String[] row = rows.get(r);
            int from = step == 0 ? selectedColumn + 1 : 0;
            int to = Math.min(row.length, columns);
            if (step == total) to = Math.min(to, selectedColumn + 1);
            for (int c = from; c < to; c++) {
                if (row[c].toLowerCase(Locale.ROOT).contains(needle)) {
                    select(r, c);
                    return true;
                }
            }
        }
        return false;
    }

    private void select(int row, int column) {
        selectedRow = row;
        selectedColumn = column;
        adapter.notifyDataSetChanged();
        list.setSelection(Math.max(0, row - 2));
        scroll.scrollTo(Math.max(0, style.edges[column + 1] - style.edges[1]), 0);
    }

    private void measureColumns() {
        int widest = 0;
        for (String[] row : rows) widest = Math.max(widest, row.length);
        columns = Math.min(widest, MAX_COLUMNS);

        int[] chars = new int[columns];
        int sample = Math.min(rows.size(), SAMPLE_ROWS);
        for (int r = 0; r < sample; r++) {
            String[] row = rows.get(r);
            for (int c = 0; c < row.length && c < columns; c++) {
                chars[c] = Math.max(chars[c], row[c].length());
            }
        }
        int[] edges = new int[columns + 2];
        int labelChars = Math.max(2, String.valueOf(rows.size()).length());
        edges[1] = Math.round(labelChars * style.charWidth) + 2 * style.padding;
        String[] letters = new String[columns];
        for (int c = 0; c < columns; c++) {
            int width = Math.max(MIN_CHARS, Math.min(MAX_CHARS, chars[c]));
            edges[c + 2] = edges[c + 1] + Math.round(width * style.charWidth) + 2 * style.padding;
            letters[c] = Xlsx.columnName(c);
        }
        style.edges = edges;
        header.bind(style, "", letters, true, -1);

        // Give the header and the list the table's full width, so both scroll sideways together.
        int width = edges[edges.length - 1];
        header.getLayoutParams().width = width;
        header.requestLayout();
        list.getLayoutParams().width = width;
        list.requestLayout();
    }
}
