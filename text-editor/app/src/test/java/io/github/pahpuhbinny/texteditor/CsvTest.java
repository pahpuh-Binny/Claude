package io.github.pahpuhbinny.texteditor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class CsvTest {

    private static void assertRows(String[][] expected, List<String[]> actual) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual.get(i));
    }

    @Test
    public void parsesQuotedCells() {
        String text = "name,note\r\n\"Smith, J\",\"said \"\"hi\"\"\"\n\"two\nlines\",\n";
        assertRows(new String[][] {
                {"name", "note"}, {"Smith, J", "said \"hi\""}, {"two\nlines", ""}},
                Csv.parse(text, ','));
    }

    @Test
    public void keepsBlankLinesAndLastLineWithoutNewline() {
        assertRows(new String[][] {{"a"}, {""}, {"b", "c"}}, Csv.parse("a\n\nb,c", ','));
        assertRows(new String[][] {}, Csv.parse("", ','));
        assertRows(new String[][] {{"5\" disk"}}, Csv.parse("5\" disk", ','));
    }

    @Test
    public void detectsDelimiter() {
        assertEquals(',', Csv.detectDelimiter("a,b,c\n1,2,3\n"));
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1,5;2;3\n"));
        assertEquals('\t', Csv.detectDelimiter("a\tb\n\"x, y, z\"\t2\n"));
        assertEquals(',', Csv.detectDelimiter("no separators"));
    }

    @Test
    public void writeQuotesOnlyWhenNeeded() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"plain", "a,b", "q\"q", " pad", "x\ny"});
        assertEquals("plain,\"a,b\",\"q\"\"q\",\" pad\",\"x\ny\"\n", Csv.write(rows, ',', true));
        assertEquals("plain;a,b;\"q\"\"q\";\" pad\";\"x\ny\"", Csv.write(rows, ';', false));
    }

    @Test
    public void randomRowsRoundTrip() {
        Random random = new Random(7);
        String alphabet = "ab ,;\"\n\r\t1";
        for (int round = 0; round < 300; round++) {
            List<String[]> rows = new ArrayList<>();
            int rowCount = 1 + random.nextInt(4);
            for (int r = 0; r < rowCount; r++) {
                String[] row = new String[1 + random.nextInt(4)];
                for (int c = 0; c < row.length; c++) {
                    StringBuilder sb = new StringBuilder();
                    int len = random.nextInt(5);
                    for (int k = 0; k < len; k++) {
                        sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
                    }
                    row[c] = sb.toString();
                }
                rows.add(row);
            }
            // A lone empty cell on the last line is the same as no line at all.
            String[] last = rows.get(rows.size() - 1);
            if (last.length == 1 && last[0].isEmpty()) last[0] = "x";
            for (char delimiter : new char[] {',', ';', '\t'}) {
                List<String[]> back = Csv.parse(Csv.write(rows, delimiter, round % 2 == 0), delimiter);
                assertRows(rows.toArray(new String[0][]), back);
            }
        }
    }
}
