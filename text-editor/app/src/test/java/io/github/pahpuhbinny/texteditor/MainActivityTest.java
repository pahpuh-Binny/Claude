package io.github.pahpuhbinny.texteditor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowAlertDialog;

/** Opens files in the real screen, on a simulated Android. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTest {

    private MainActivity open(String fileName, byte[] content) throws Exception {
        Uri uri = Uri.parse("content://test.files/" + fileName);
        shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
                .registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(content));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri,
                RuntimeEnvironment.getApplication(), MainActivity.class);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class, intent)
                .setup().get();
        // The file is read on a background thread; wait for the screen to show it.
        TextView status = activity.findViewById(R.id.status);
        String loading = activity.getString(R.string.loading);
        for (int i = 0; i < 500 && status.getText().toString().equals(loading); i++) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertFalse("still loading", status.getText().toString().equals(loading));
        return activity;
    }

    private static void tap(MainActivity activity, int menuItem) {
        activity.onOptionsItemSelected(new RoboMenuItem(menuItem));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String status(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.status)).getText().toString();
    }

    private static boolean shown(MainActivity activity, int id) {
        return activity.findViewById(id).getVisibility() == View.VISIBLE;
    }

    @Test
    public void jsonOpensAsText() throws Exception {
        MainActivity activity = open("data.json", "{\"a\":[1,2]}".getBytes(StandardCharsets.UTF_8));
        EditText editor = activity.findViewById(R.id.editor);
        assertTrue(shown(activity, R.id.editor));
        assertFalse(shown(activity, R.id.table_scroll));
        assertEquals("{\"a\":[1,2]}", editor.getText().toString());
        assertEquals("data.json", activity.getTitle().toString());

        tap(activity, R.id.action_format_json);
        assertEquals("{\n  \"a\": [\n    1,\n    2\n  ]\n}\n", editor.getText().toString());
        assertTrue(activity.getTitle().toString().startsWith("•"));
    }

    @Test
    public void binaryOpensAsHex() throws Exception {
        MainActivity activity = open("image.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1});
        EditText editor = activity.findViewById(R.id.editor);
        assertEquals("00000000: 89 50 4E 47 00 01       | .PNG..\n", editor.getText().toString());
        assertTrue(status(activity).startsWith("Hex"));
    }

    @Test
    public void csvOpensAsTableAndCellsCanBeEdited() throws Exception {
        String csv = "name,city\r\nAda,\"London, UK\"\r\nGrace,New York\r\n";
        MainActivity activity = open("people.csv", csv.getBytes(StandardCharsets.UTF_8));
        ListView list = activity.findViewById(R.id.table_list);
        EditText editor = activity.findViewById(R.id.editor);
        assertTrue(shown(activity, R.id.table_scroll));
        assertFalse(shown(activity, R.id.editor));
        assertEquals(3, list.getAdapter().getCount());
        assertTrue(status(activity), status(activity).contains("3 rows × 2 columns"));

        // Tapping a cell opens it for editing; OK writes the change into the file's text.
        list.measure(0, 0);
        list.layout(0, 0, 2000, 2000);
        View row = list.getAdapter().getView(1, null, list);
        list.getOnItemClickListener().onItemClick(list, row, 1, 1);
        // The row view was never touched, so its last touch is at x = 0: the row label.
        assertEquals(null, ShadowAlertDialog.getLatestAlertDialog());

        java.lang.reflect.Method click = MainActivity.class.getDeclaredMethod(
                "onCellClick", int.class, int.class);
        click.setAccessible(true);
        click.invoke(activity, 1, 0);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("A2", shadowOf(dialog).getTitle().toString());
        EditText input = findEditText(dialog.getWindow().getDecorView());
        assertEquals("Ada", input.getText().toString());
        input.setText("Ada L");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("name,city\nAda L,\"London, UK\"\nGrace,New York\n",
                editor.getText().toString());
        assertTrue(activity.getTitle().toString().startsWith("•"));

        // Switching the table off shows the same file as text.
        tap(activity, R.id.action_table);
        assertTrue(shown(activity, R.id.editor));
        assertFalse(shown(activity, R.id.table_scroll));
    }

    @Test
    public void workbookOpensAsTableWithSheets() throws Exception {
        String ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            String[] files = {
                "xl/workbook.xml", "<workbook " + ns + " xmlns:r=\"r\"><sheets>"
                        + "<sheet name=\"First\" sheetId=\"1\" r:id=\"rId1\"/>"
                        + "<sheet name=\"Second\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels", "<Relationships>"
                        + "<Relationship Id=\"rId1\" Type=\"t/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"t/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                        + "<Relationship Id=\"rId3\" Type=\"t/sharedStrings\" Target=\"sharedStrings.xml\"/>"
                        + "</Relationships>",
                "xl/sharedStrings.xml", "<sst " + ns + "><si><t>Total</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<worksheet " + ns + "><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>12.5</v></c></row>"
                        + "<row r=\"2\"><c r=\"C2\"><v>7</v></c></row></sheetData></worksheet>",
                "xl/worksheets/sheet2.xml", "<worksheet " + ns + "><sheetData>"
                        + "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>",
            };
            for (int i = 0; i < files.length; i += 2) {
                zip.putNextEntry(new ZipEntry(files[i]));
                zip.write(files[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        MainActivity activity = open("report.xlsx", bytes.toByteArray());
        ListView list = activity.findViewById(R.id.table_list);
        assertTrue(shown(activity, R.id.table_scroll));
        assertEquals(2, list.getAdapter().getCount());
        assertTrue(status(activity), status(activity).startsWith("First (1/2)"));
        assertTrue(status(activity), status(activity).contains("2 rows × 3 columns"));

        // Hex view shows the raw file; Table view brings the sheets back.
        tap(activity, R.id.action_hex);
        assertTrue(shown(activity, R.id.editor));
        assertTrue(((EditText) activity.findViewById(R.id.editor)).getText().toString()
                .startsWith("00000000: 50 4B"));
        tap(activity, R.id.action_table);
        assertTrue(shown(activity, R.id.table_scroll));

        tap(activity, R.id.action_sheet);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        shadowOf(dialog).clickOnItem(1);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(status(activity), status(activity).startsWith("Second (2/2)"));
        assertEquals(1, list.getAdapter().getCount());
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
