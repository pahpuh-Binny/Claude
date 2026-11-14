package io.github.pahpuhbinny.texteditor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class XlsxTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String NS =
            "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";

    private File zip(String... pathsAndContents) throws IOException {
        File file = folder.newFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(pathsAndContents[i]));
                out.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + pathsAndContents[i + 1])
                        .getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private File workbook(String sheet1, String sheet2) throws IOException {
        return zip(
                // Sheets are stored before the strings they use, and under odd names.
                "xl/worksheets/data.xml", sheet1,
                "xl/worksheets/other.xml", sheet2,
                "xl/workbook.xml", "<workbook " + NS + " xmlns:r=\"http://schemas.openxmlformats"
                        + ".org/officeDocument/2006/relationships\"><sheets>"
                        + "<sheet name=\"Sales &amp; costs\" sheetId=\"1\" r:id=\"rId7\"/>"
                        + "<sheet name=\"Notes\" sheetId=\"2\" r:id=\"rId8\"/>"
                        + "</sheets></workbook>",
                "xl/_rels/workbook.xml.rels", "<Relationships>"
                        + "<Relationship Id=\"rId8\" Type=\"x/worksheet\" Target=\"worksheets/other.xml\"/>"
                        + "<Relationship Id=\"rId7\" Type=\"x/worksheet\" Target=\"/xl/worksheets/data.xml\"/>"
                        + "<Relationship Id=\"rId9\" Type=\"x/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                        + "</Relationships>",
                "xl/sharedStrings.xml", "<sst " + NS + ">"
                        + "<si><t>Item</t></si>"
                        + "<si><r><t>Rich </t></r><r><t xml:space=\"preserve\">text</t></r>"
                        + "<rPh><t>ignored</t></rPh></si>"
                        + "</sst>",
                "xl/styles.xml", "<styleSheet " + NS + "><numFmts>"
                        + "<numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm\"/>"
                        + "<numFmt numFmtId=\"165\" formatCode=\"0.0 &quot;days&quot;\"/>"
                        + "</numFmts>"
                        + "<cellStyleXfs><xf numFmtId=\"14\"/></cellStyleXfs>"
                        + "<cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/>"
                        + "<xf numFmtId=\"164\"/><xf numFmtId=\"165\"/></cellXfs></styleSheet>");
    }

    @Test
    public void readsCellValues() throws Exception {
        String sheet1 = "<worksheet " + NS + "><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>"
                + "<c r=\"D1\" t=\"inlineStr\"><is><t>inline</t></is></c></row>"
                + "<row r=\"3\"><c r=\"A3\"><v>42</v></c><c r=\"B3\"><v>0.30000000000000004</v></c>"
                + "<c r=\"C3\" t=\"b\"><v>1</v></c><c r=\"D3\" t=\"str\"><f>A1&amp;\"!\"</f><v>Item!</v></c>"
                + "<c r=\"E3\" t=\"e\"><v>#DIV/0!</v></c><c r=\"F3\" s=\"1\"/></row>"
                + "<row r=\"4\"><c r=\"A4\" s=\"1\"><v>45658</v></c><c r=\"B4\" s=\"2\"><v>45658.75</v></c>"
                + "<c r=\"C4\" s=\"3\"><v>2.5</v></c><c r=\"D4\" s=\"1\"><v>0.5</v></c></row>"
                + "</sheetData></worksheet>";
        String sheet2 = "<worksheet " + NS + "><sheetData>"
                + "<row><c><v>1</v></c><c><v>2</v></c></row></sheetData></worksheet>";
        List<Xlsx.Sheet> sheets = Xlsx.read(workbook(sheet1, sheet2));

        assertEquals(2, sheets.size());
        Xlsx.Sheet sales = sheets.get(0);
        assertEquals("Sales & costs", sales.name);
        assertFalse(sales.truncated);
        assertEquals(4, sales.rows.size());
        assertArrayEquals(new String[] {"Item", "Rich text", "", "inline"}, sales.rows.get(0));
        assertArrayEquals(new String[0], sales.rows.get(1));
        assertArrayEquals(new String[] {"42", "0.3", "TRUE", "Item!", "#DIV/0!"}, sales.rows.get(2));
        assertArrayEquals(new String[] {"2025-01-01", "2025-01-01 18:00:00", "2.5", "12:00:00"},
                sales.rows.get(3));

        assertEquals("Notes", sheets.get(1).name);
        assertArrayEquals(new String[] {"1", "2"}, sheets.get(1).rows.get(0));
    }

    @Test
    public void stopsAtTheRowLimit() throws Exception {
        String sheet = "<worksheet " + NS + "><sheetData>"
                + "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>"
                + "<row r=\"1048576\"><c r=\"A1048576\"><v>2</v></c></row>"
                + "</sheetData></worksheet>";
        List<Xlsx.Sheet> sheets = Xlsx.read(workbook(sheet, sheet));
        assertTrue(sheets.get(0).truncated);
        assertEquals(1, sheets.get(0).rows.size());
    }

    @Test
    public void otherZipsAreNotWorkbooks() throws Exception {
        assertNull(Xlsx.read(zip("word/document.xml", "<document/>")));
    }

    @Test
    public void columnNames() {
        assertEquals(0, Xlsx.columnIndex("A1"));
        assertEquals(27, Xlsx.columnIndex("AB12"));
        assertEquals("A", Xlsx.columnName(0));
        assertEquals("Z", Xlsx.columnName(25));
        assertEquals("AA", Xlsx.columnName(26));
        assertEquals("AB", Xlsx.columnName(27));
        for (int i = 0; i < 20000; i += 7) assertEquals(i, Xlsx.columnIndex(Xlsx.columnName(i) + "5"));
    }

    @Test
    public void numbers() {
        assertEquals("0.3", Xlsx.formatNumber("0.30000000000000004"));
        assertEquals("100", Xlsx.formatNumber("100"));
        assertEquals("100", Xlsx.formatNumber("1E2"));
        assertEquals("0", Xlsx.formatNumber("0.0"));
        assertEquals("-12.5", Xlsx.formatNumber("-12.50"));
        assertEquals("0.012345678899", Xlsx.formatNumber("1.2345678899E-2"));
        assertEquals("1E+30", Xlsx.formatNumber("1E30"));
        assertEquals("n/a", Xlsx.formatNumber("n/a"));
    }

    @Test
    public void dateFormats() {
        assertTrue(Xlsx.isDateFormat(14, null));
        assertFalse(Xlsx.isDateFormat(2, null));
        assertTrue(Xlsx.isDateFormat(170, "[$-409]d\\-mmm\\-yy"));
        assertTrue(Xlsx.isDateFormat(170, "[h]:mm"));
        assertFalse(Xlsx.isDateFormat(170, "#,##0.00 \"hours\""));
        assertFalse(Xlsx.isDateFormat(170, "[Red]0.00"));
        assertEquals("1904-01-02", Xlsx.formatDate("1", true));
        assertEquals("1970-01-01", Xlsx.formatDate("25569", false));
    }
}
