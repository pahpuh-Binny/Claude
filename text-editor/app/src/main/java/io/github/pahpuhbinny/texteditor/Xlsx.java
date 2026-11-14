package io.github.pahpuhbinny.texteditor;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Reads the cell values of an Excel workbook (.xlsx, .xlsm), which is a zip
 * of XML files. Formulas show their last calculated result. Formatting,
 * charts and images are ignored. Has no Android dependencies.
 */
final class Xlsx {
    /** Limits that keep a huge workbook from using up the phone's memory. */
    static final int MAX_ROWS = 100_000;
    static final int MAX_COLUMNS = 256;
    static final int MAX_CELLS = 600_000;

    private static final String[] NO_CELLS = new String[0];

    private Xlsx() {}

    static final class Sheet {
        String name;
        final List<String[]> rows = new ArrayList<>();
        /** Some rows, columns or cells were left out because of the limits. */
        boolean truncated;
    }

    /** Thrown to stop reading a sheet once a limit is reached. */
    private static final class LimitReached extends SAXException {
        LimitReached() {
            super("limit reached");
        }
    }

    /**
     * Reads every sheet of a workbook.
     *
     * @return the sheets, or null if the file is a zip but not a workbook
     * @throws IOException if the file is not a readable zip or its XML is broken
     */
    static List<Sheet> read(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("xl/workbook.xml") == null) return null;

            WorkbookHandler workbook = new WorkbookHandler();
            parse(zip, "xl/workbook.xml", workbook);

            // The relationships file maps each sheet's id to its file in the zip.
            RelsHandler rels = new RelsHandler();
            parse(zip, "xl/_rels/workbook.xml.rels", rels);

            StringsHandler strings = new StringsHandler();
            parse(zip, rels.sharedStrings != null ? rels.sharedStrings : "xl/sharedStrings.xml",
                    strings);

            StylesHandler styles = new StylesHandler();
            parse(zip, "xl/styles.xml", styles);

            List<Sheet> sheets = new ArrayList<>();
            int[] cellBudget = {MAX_CELLS};
            for (int i = 0; i < workbook.names.size(); i++) {
                Sheet sheet = new Sheet();
                sheet.name = workbook.names.get(i);
                String path = rels.targets.get(workbook.ids.get(i));
                if (path == null) path = "xl/worksheets/sheet" + (i + 1) + ".xml";
                try {
                    parse(zip, path, new SheetHandler(sheet, strings.values, styles.dateStyles,
                            workbook.date1904, cellBudget));
                } catch (LimitReachedException e) {
                    sheet.truncated = true;
                }
                sheets.add(sheet);
            }
            return sheets;
        }
    }

    private static final class LimitReachedException extends IOException {}

    private static void parse(ZipFile zip, String path, DefaultHandler handler)
            throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return;
        try (InputStream in = zip.getInputStream(entry)) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            try {
                // Never fetch anything a document type declaration points to.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Android's parser has no such feature and does not fetch them anyway.
            }
            factory.newSAXParser().parse(in, handler);
        } catch (LimitReached e) {
            throw new LimitReachedException();
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("The workbook could not be read (" + path + ").", e);
        }
    }

    // Parsers differ on which name they fill in, and tags may carry a prefix.

    private static String tag(String localName, String qName) {
        String name = qName == null || qName.isEmpty() ? localName : qName;
        return name.substring(name.indexOf(':') + 1);
    }

    private static String attr(Attributes attrs, String name) {
        for (int i = 0; i < attrs.getLength(); i++) {
            if (name.equals(tag(attrs.getLocalName(i), attrs.getQName(i)))) {
                return attrs.getValue(i);
            }
        }
        return null;
    }

    private static int toInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class WorkbookHandler extends DefaultHandler {
        final List<String> names = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        boolean date1904;

        @Override
        public void startElement(String uri, String local, String q, Attributes attrs) {
            String tag = tag(local, q);
            if (tag.equals("sheet")) {
                String name = attr(attrs, "name");
                names.add(name != null ? name : "Sheet" + (names.size() + 1));
                ids.add(attr(attrs, "id"));
            } else if (tag.equals("workbookPr")) {
                String v = attr(attrs, "date1904");
                date1904 = "1".equals(v) || "true".equals(v);
            }
        }
    }

    private static final class RelsHandler extends DefaultHandler {
        final Map<String, String> targets = new HashMap<>();
        String sharedStrings;

        @Override
        public void startElement(String uri, String local, String q, Attributes attrs) {
            if (!tag(local, q).equals("Relationship")) return;
            String target = attr(attrs, "Target");
            String type = attr(attrs, "Type");
            if (target == null) return;
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            targets.put(attr(attrs, "Id"), path);
            if (type != null && type.endsWith("/sharedStrings")) sharedStrings = path;
        }
    }

    /** Reads the table of text values that cells refer to by number. */
    private static final class StringsHandler extends DefaultHandler {
        final List<String> values = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private boolean inText;
        private boolean inPhonetic;

        @Override
        public void startElement(String uri, String local, String q, Attributes attrs) {
            String tag = tag(local, q);
            if (tag.equals("si")) {
                text.setLength(0);
            } else if (tag.equals("rPh")) {
                inPhonetic = true; // a pronunciation guide, not part of the value
            } else if (tag.equals("t")) {
                inText = !inPhonetic;
            }
        }

        @Override
        public void endElement(String uri, String local, String q) {
            String tag = tag(local, q);
            if (tag.equals("si")) {
                values.add(text.toString());
            } else if (tag.equals("rPh")) {
                inPhonetic = false;
            } else if (tag.equals("t")) {
                inText = false;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inText) text.append(ch, start, length);
        }
    }

    /** Works out which cell styles show their number as a date or time. */
    private static final class StylesHandler extends DefaultHandler {
        final List<Boolean> dateStyles = new ArrayList<>();
        private final Map<Integer, String> formats = new HashMap<>();
        private boolean inCellXfs;

        @Override
        public void startElement(String uri, String local, String q, Attributes attrs) {
            String tag = tag(local, q);
            if (tag.equals("numFmt")) {
                formats.put(toInt(attr(attrs, "numFmtId"), -1), attr(attrs, "formatCode"));
            } else if (tag.equals("cellXfs")) {
                inCellXfs = true;
            } else if (tag.equals("xf") && inCellXfs) {
                int id = toInt(attr(attrs, "numFmtId"), 0);
                dateStyles.add(isDateFormat(id, formats.get(id)));
            }
        }

        @Override
        public void endElement(String uri, String local, String q) {
            if (tag(local, q).equals("cellXfs")) inCellXfs = false;
        }
    }

    static boolean isDateFormat(int id, String code) {
        if (code == null) {
            // Excel's built-in date and time formats.
            return (id >= 14 && id <= 22) || (id >= 27 && id <= 36)
                    || (id >= 45 && id <= 47) || (id >= 50 && id <= 58);
        }
        boolean inQuotes = false;
        boolean inBrackets = false;
        for (int i = 0; i < code.length(); i++) {
            char c = Character.toLowerCase(code.charAt(i));
            if (inQuotes) {
                if (c == '"') inQuotes = false;
            } else if (inBrackets) {
                if (c == ']') inBrackets = false;
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == '[') {
                inBrackets = true;
            } else if (c == '\\') {
                i++;
            } else if (c == 'd' || c == 'm' || c == 'y' || c == 'h' || c == 's') {
                return true;
            }
        }
        return false;
    }

    private static final class SheetHandler extends DefaultHandler {
        private final Sheet sheet;
        private final List<String> strings;
        private final List<Boolean> dateStyles;
        private final boolean date1904;
        private final int[] cellBudget;

        private final List<String> row = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private boolean inText;
        private int rowNumber; // 1-based number of the row being read
        private int column;
        private String type;
        private int style;

        SheetHandler(Sheet sheet, List<String> strings, List<Boolean> dateStyles,
                boolean date1904, int[] cellBudget) {
            this.sheet = sheet;
            this.strings = strings;
            this.dateStyles = dateStyles;
            this.date1904 = date1904;
            this.cellBudget = cellBudget;
        }

        @Override
        public void startElement(String uri, String local, String q, Attributes attrs)
                throws SAXException {
            String tag = tag(local, q);
            if (tag.equals("row")) {
                rowNumber = Math.max(rowNumber + 1, toInt(attr(attrs, "r"), rowNumber + 1));
                if (rowNumber > MAX_ROWS) throw new LimitReached();
                row.clear();
                column = -1;
            } else if (tag.equals("c")) {
                String ref = attr(attrs, "r");
                column = ref != null ? columnIndex(ref) : column + 1;
                type = attr(attrs, "t");
                style = toInt(attr(attrs, "s"), -1);
                text.setLength(0);
            } else if (tag.equals("v") || tag.equals("t")) {
                inText = true;
            }
        }

        @Override
        public void endElement(String uri, String local, String q) throws SAXException {
            String tag = tag(local, q);
            if (tag.equals("v") || tag.equals("t")) {
                inText = false;
            } else if (tag.equals("c")) {
                if (column < 0) return;
                if (column >= MAX_COLUMNS) {
                    sheet.truncated = true;
                    return;
                }
                String value = cellValue();
                if (value.isEmpty()) return;
                while (row.size() < column) row.add("");
                if (row.size() == column) row.add(value); else row.set(column, value);
            } else if (tag.equals("row")) {
                if (row.isEmpty()) return;
                cellBudget[0] -= row.size();
                if (cellBudget[0] < 0) throw new LimitReached();
                // Rows with nothing in them are left out of the file.
                while (sheet.rows.size() < rowNumber - 1) sheet.rows.add(NO_CELLS);
                sheet.rows.add(row.toArray(new String[0]));
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inText) text.append(ch, start, length);
        }

        private String cellValue() {
            String raw = text.toString();
            if (type == null || type.equals("n")) {
                boolean isDate = style >= 0 && style < dateStyles.size() && dateStyles.get(style);
                return isDate ? formatDate(raw, date1904) : formatNumber(raw);
            }
            switch (type) {
                case "s":
                    int index = toInt(raw, -1);
                    return index >= 0 && index < strings.size() ? strings.get(index) : "";
                case "b":
                    return raw.trim().equals("1") ? "TRUE" : "FALSE";
                default: // "str", "inlineStr", "e", "d"
                    return raw;
            }
        }
    }

    /** "A1" is column 0, "AB12" is column 27. */
    static int columnIndex(String ref) {
        int column = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = Character.toUpperCase(ref.charAt(i));
            if (c < 'A' || c > 'Z') break;
            column = column * 26 + (c - 'A' + 1);
            if (column > 1_000_000) break;
        }
        return column - 1;
    }

    /** Column 0 is "A", column 27 is "AB". */
    static String columnName(int column) {
        StringBuilder sb = new StringBuilder();
        for (int n = column + 1; n > 0; n = (n - 1) / 26) {
            sb.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return sb.toString();
    }

    /** Tidies numbers the way a spreadsheet shows them: 0.30000000000000004 becomes 0.3. */
    static String formatNumber(String raw) {
        raw = raw.trim();
        if (raw.isEmpty()) return raw;
        try {
            BigDecimal number = new BigDecimal(raw).round(new MathContext(15));
            if (number.signum() == 0) return "0";
            number = number.stripTrailingZeros();
            // Very large and very small numbers stay in scientific notation.
            int digits = number.precision() - number.scale();
            return digits > 20 || digits < -10 ? number.toString() : number.toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** Turns a spreadsheet's day count into a date, a time, or both. */
    static String formatDate(String raw, boolean date1904) {
        double serial;
        try {
            serial = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return raw;
        }
        if (serial < 0 || serial > 2_958_465) return formatNumber(raw); // past year 9999
        long days = (long) Math.floor(serial);
        long seconds = Math.round((serial - days) * 86_400);
        if (seconds >= 86_400) {
            days++;
            seconds = 0;
        }
        String time = String.format(Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60);
        if (days == 0 && !date1904) return time; // a time of day with no date
        // Day 25569 is 1 January 1970 in the usual system, day 24107 in the 1904 one.
        String date = LocalDate.ofEpochDay(days - (date1904 ? 24_107 : 25_569)).toString();
        return seconds == 0 ? date : date + " " + time;
    }
}
