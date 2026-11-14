package io.github.pahpuhbinny.texteditor;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/** One row of the table: a row label followed by cells, drawn directly for speed. */
final class GridRowView extends View {
    /** Longest piece of a cell's text that is drawn; the rest would be cut off anyway. */
    private static final int MAX_DRAWN_CHARS = 64;

    /** Sizes and colours shared by every row of a table. */
    static final class Style {
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint();
        final Paint labelBackground = new Paint();
        final Paint highlight = new Paint();
        final int rowHeight;
        final int padding;
        final float charWidth;
        final float baseline;
        /** Left edge of each column; column 0 is the row label. Has one extra entry for the right edge. */
        int[] edges = {0, 0};

        Style(Context context) {
            float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1,
                    context.getResources().getDisplayMetrics());
            float dp = context.getResources().getDisplayMetrics().density;
            TypedArray a = context.obtainStyledAttributes(new int[] {
                    android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
            text.setColor(a.getColor(0, Color.BLACK));
            label.setColor(a.getColor(1, Color.GRAY));
            a.recycle();
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(13 * sp);
            label.setTypeface(Typeface.MONOSPACE);
            label.setTextSize(12 * sp);
            line.setColor(context.getColor(R.color.grid_line));
            line.setStrokeWidth(Math.max(1, dp));
            labelBackground.setColor(context.getColor(R.color.bar_background));
            highlight.setColor(context.getColor(R.color.cell_highlight));
            rowHeight = Math.round(36 * dp);
            padding = Math.round(8 * dp);
            charWidth = text.measureText("0");
            Paint.FontMetrics metrics = text.getFontMetrics();
            baseline = (rowHeight - metrics.ascent - metrics.descent) / 2;
        }
    }

    private Style style;
    private String label = "";
    private String[] cells = new String[0];
    private boolean header;
    private int highlighted = -1;
    private float touchX;

    public GridRowView(Context context) {
        super(context);
    }

    public GridRowView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** Shows a row. {@code highlighted} is the cell to mark, or -1 for none. */
    void bind(Style style, String label, String[] cells, boolean header, int highlighted) {
        this.style = style;
        this.label = label;
        this.cells = cells;
        this.header = header;
        this.highlighted = highlighted;
        invalidate();
    }

    /** The cell that was last touched, or -1 for the row label. */
    int touchedColumn() {
        if (style == null) return -1;
        int[] edges = style.edges;
        if (touchX < edges[1]) return -1;
        for (int c = 1; c < edges.length - 1; c++) {
            if (touchX < edges[c + 1]) return c - 1;
        }
        return edges.length - 3;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touchX = event.getX();
        return super.onTouchEvent(event);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (style == null) {
            setMeasuredDimension(0, 0);
        } else {
            setMeasuredDimension(style.edges[style.edges.length - 1], style.rowHeight);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (style == null) return;
        int[] edges = style.edges;
        int height = getHeight();
        // The header row and the row labels have a tinted background.
        canvas.drawRect(0, 0, header ? edges[edges.length - 1] : edges[1], height,
                style.labelBackground);
        for (int c = 0; c < edges.length - 1; c++) {
            int left = edges[c];
            int right = edges[c + 1];
            boolean isLabel = c == 0 || header;
            String value = c == 0 ? label : c - 1 < cells.length ? cells[c - 1] : "";
            if (c - 1 == highlighted && !header) {
                canvas.drawRect(left, 0, right, height, style.highlight);
            }
            if (value != null && !value.isEmpty()) {
                // Only the first line of a cell is shown; tap the cell to see it all.
                int end = Math.min(value.length(), MAX_DRAWN_CHARS);
                for (int i = 0; i < end; i++) {
                    char ch = value.charAt(i);
                    if (ch == '\n' || ch == '\r') end = i;
                }
                canvas.save();
                canvas.clipRect(left, 0, right - style.padding / 2, height);
                canvas.drawText(value, 0, end, left + style.padding, style.baseline,
                        isLabel ? style.label : style.text);
                canvas.restore();
            }
            canvas.drawLine(right, 0, right, height, style.line);
        }
        canvas.drawLine(0, height, edges[edges.length - 1], height, style.line);
    }
}
