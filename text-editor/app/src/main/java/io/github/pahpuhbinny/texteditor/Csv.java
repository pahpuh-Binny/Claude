package io.github.pahpuhbinny.texteditor;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes delimiter-separated text (CSV, TSV), with no Android dependencies. */
final class Csv {
    private static final char[] DELIMITERS = {',', ';', '\t', '|'};

    private Csv() {}

    /** Picks the separator used most often in the first lines, outside quotes. */
    static char detectDelimiter(String text) {
        int[] counts = new int[DELIMITERS.length];
        boolean inQuotes = false;
        int lines = 0;
        for (int i = 0; i < text.length() && lines < 10; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes) {
                if (c == '\n') lines++;
                for (int d = 0; d < DELIMITERS.length; d++) {
                    if (c == DELIMITERS[d]) counts[d]++;
                }
            }
        }
        int best = 0;
        for (int d = 1; d < DELIMITERS.length; d++) {
            if (counts[d] > counts[best]) best = d;
        }
        return DELIMITERS[best];
    }

    /**
     * Splits text into rows of cells. Cells may be wrapped in double quotes,
     * in which case they can hold the delimiter, line breaks and "" for a quote.
     */
    static List<String[]> parse(String text, char delimiter) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean quoted = false; // the current cell began with a quote
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < n && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"' && cell.length() == 0 && !quoted) {
                inQuotes = true;
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                quoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                quoted = false;
                rows.add(row.toArray(new String[0]));
                row.clear();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || quoted || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    /** Joins rows back into text, quoting only the cells that need it. */
    static String write(List<String[]> rows, char delimiter, boolean trailingNewline) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) sb.append('\n');
            String[] row = rows.get(r);
            for (int c = 0; c < row.length; c++) {
                if (c > 0) sb.append(delimiter);
                appendCell(sb, row[c], delimiter);
            }
        }
        if (trailingNewline && !rows.isEmpty()) sb.append('\n');
        return sb.toString();
    }

    private static void appendCell(StringBuilder sb, String cell, char delimiter) {
        boolean quote = cell.indexOf(delimiter) >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0
                || cell.startsWith(" ") || cell.endsWith(" ");
        if (!quote) {
            sb.append(cell);
            return;
        }
        sb.append('"');
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c == '"') sb.append('"');
            sb.append(c);
        }
        sb.append('"');
    }
}
