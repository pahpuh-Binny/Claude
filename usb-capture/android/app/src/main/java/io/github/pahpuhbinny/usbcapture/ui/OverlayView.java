package io.github.pahpuhbinny.usbcapture.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import io.github.pahpuhbinny.usbcapture.Prefs;
import io.github.pahpuhbinny.usbcapture.render.DisplayParams;
import io.github.pahpuhbinny.usbcapture.render.Geometry;
import io.github.pahpuhbinny.usbcapture.render.GlRenderer;

/**
 * Draws framing guides (grids, centre mark, safe areas), the histogram
 * and audio level meters on top of the video. Guides are positioned with
 * the same maths as the renderer, so they always line up with the picture.
 */
public final class OverlayView extends View {
    private final float dp;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint safe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint();
    private final Paint hist = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meter = new Paint();
    private final Path path = new Path();
    private final RectF r = new RectF();

    private DisplayParams params = new DisplayParams();
    private int srcW, srcH;
    private int grid;
    private boolean crosshair, safeAreas;
    private int histMode;
    private GlRenderer.Analysis analysis;
    private boolean meters;
    private boolean micMeter;
    private float[] levels = new float[3];
    private final float[] peakHold = new float[3];
    private long[] peakTime = new long[3];
    private int topInset, bottomInset;

    public OverlayView(Context c) {
        this(c, null);
    }

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, dp));
        line.setColor(0xB3FFFFFF);
        line.setShadowLayer(dp, 0, 0, 0x80000000);
        safe.setStyle(Paint.Style.STROKE);
        safe.setStrokeWidth(Math.max(1f, dp));
        safe.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
        label.setColor(0xCCFFFFFF);
        label.setTextSize(10 * dp);
        label.setShadowLayer(2 * dp, 0, 0, 0xFF000000);
        box.setColor(0x99000000);
        hist.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    public void setSource(int w, int h) {
        srcW = w;
        srcH = h;
        invalidate();
    }

    public void setParams(DisplayParams p) {
        params = p.copy();
        invalidate();
    }

    public void setGuides(int grid, boolean crosshair, boolean safeAreas) {
        this.grid = grid;
        this.crosshair = crosshair;
        this.safeAreas = safeAreas;
        invalidate();
    }

    public void setHistogramMode(int mode) {
        histMode = mode;
        if (mode == Prefs.HIST_OFF) analysis = null;
        invalidate();
    }

    public void setAnalysis(GlRenderer.Analysis a) {
        analysis = a;
        if (histMode != Prefs.HIST_OFF) invalidate();
    }

    public void setMeters(boolean on, boolean mic) {
        meters = on;
        micMeter = mic;
        invalidate();
    }

    public void setLevels(float[] l) {
        levels = l;
        if (meters) invalidate();
    }

    public void setInsets(int top, int bottom) {
        topInset = top;
        bottomInset = bottom;
        invalidate();
    }

    public boolean needsAnalysis() {
        return histMode != Prefs.HIST_OFF;
    }

    @Override
    protected void onDraw(Canvas c) {
        int vw = getWidth(), vh = getHeight();
        if (srcW > 0 && srcH > 0 && (grid != Prefs.GRID_NONE || crosshair || safeAreas)) {
            Geometry g = Geometry.compute(params, vw, vh, srcW, srcH);
            RectF pic = new RectF(g.rect);
            if (!pic.intersect(0, 0, vw, vh)) pic.set(g.rect);
            drawGuides(c, g.rect, pic);
        }
        if (histMode != Prefs.HIST_OFF && analysis != null) drawHistogram(c, vw);
        if (meters) drawMeters(c, vw, vh);
    }

    private void drawGuides(Canvas c, RectF full, RectF vis) {
        c.save();
        c.clipRect(vis);
        float[] fr;
        switch (grid) {
            case Prefs.GRID_THIRDS: fr = new float[]{1f / 3, 2f / 3}; break;
            case Prefs.GRID_QUARTERS: fr = new float[]{0.25f, 0.5f, 0.75f}; break;
            case Prefs.GRID_GOLDEN: fr = new float[]{0.382f, 0.618f}; break;
            default: fr = new float[0];
        }
        for (float f : fr) {
            float x = full.left + full.width() * f;
            float y = full.top + full.height() * f;
            c.drawLine(x, full.top, x, full.bottom, line);
            c.drawLine(full.left, y, full.right, y, line);
        }
        if (crosshair) {
            float cx = full.centerX(), cy = full.centerY(), s = 14 * dp;
            c.drawLine(cx - s, cy, cx + s, cy, line);
            c.drawLine(cx, cy - s, cx, cy + s, line);
        }
        if (safeAreas) {
            drawSafe(c, full, 0.93f, 0xCC4CAF50, "Action safe 93%");
            drawSafe(c, full, 0.90f, 0xCCFFC107, "Title safe 90%");
            drawSafe(c, full, 0.80f, 0x99FF5252, "Graphics safe 80%");
        }
        c.restore();
    }

    private void drawSafe(Canvas c, RectF full, float frac, int color, String text) {
        float dx = full.width() * (1 - frac) / 2, dy = full.height() * (1 - frac) / 2;
        r.set(full.left + dx, full.top + dy, full.right - dx, full.bottom - dy);
        safe.setColor(color);
        c.drawRect(r, safe);
        label.setColor(color);
        c.drawText(text, r.left + 4 * dp, r.top + 12 * dp, label);
    }

    private void drawHistogram(Canvas c, int vw) {
        GlRenderer.Analysis a = analysis;
        float w = 200 * dp, h = 76 * dp;
        float left = vw - w - 12 * dp - (meters ? 44 * dp : 0);
        float top = topInset + 12 * dp;
        r.set(left, top, left + w, top + h);
        c.drawRoundRect(r, 8 * dp, 8 * dp, box);
        float pad = 6 * dp;
        RectF plot = new RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad - 10 * dp);
        if (histMode == Prefs.HIST_RGB) {
            hist.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            drawCurve(c, plot, a.red, 0xB0FF3B30, a.samples);
            drawCurve(c, plot, a.green, 0xB034C759, a.samples);
            drawCurve(c, plot, a.blue, 0xB00A84FF, a.samples);
            hist.setXfermode(null);
        } else {
            drawCurve(c, plot, a.luma, 0xD0FFFFFF, a.samples);
        }
        label.setColor(0xCCFFFFFF);
        String s = String.format(java.util.Locale.US, "Avg %d%%  ·  clip %d%%", Math.round(a.averageLuma * 100),
                Math.round(100f * (a.luma[255] + a.luma[254]) / Math.max(1, a.samples)));
        c.drawText(s, r.left + pad, r.bottom - 4 * dp, label);
    }

    private void drawCurve(Canvas c, RectF plot, int[] bins, int color, int samples) {
        int max = 1;
        for (int i = 1; i < 255; i++) max = Math.max(max, bins[i]);
        path.reset();
        path.moveTo(plot.left, plot.bottom);
        for (int i = 0; i < 256; i++) {
            float x = plot.left + plot.width() * i / 255f;
            float v = Math.min(1f, bins[i] / (float) max);
            path.lineTo(x, plot.bottom - v * plot.height());
        }
        path.lineTo(plot.right, plot.bottom);
        path.close();
        hist.setColor(color);
        c.drawPath(path, hist);
    }

    private void drawMeters(Canvas c, int vw, int vh) {
        int n = micMeter ? 3 : 2;
        float bw = 8 * dp, gap = 4 * dp, h = Math.min(160 * dp, vh * 0.4f);
        float right = vw - 12 * dp;
        float top = topInset + 12 * dp;
        float left = right - n * bw - (n - 1) * gap;
        r.set(left - 6 * dp, top - 6 * dp, right + 6 * dp, top + h + 18 * dp);
        c.drawRoundRect(r, 6 * dp, 6 * dp, box);
        long now = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            float lv = i < levels.length ? levels[i] : 0f;
            float db = lv <= 0.00001f ? -60f : (float) (20 * Math.log10(lv));
            float frac = Math.max(0f, Math.min(1f, (db + 60f) / 60f));
            if (frac >= peakHold[i] || now - peakTime[i] > 1500) {
                peakHold[i] = frac;
                peakTime[i] = now;
            }
            float x = left + i * (bw + gap);
            meter.setColor(0x33FFFFFF);
            c.drawRect(x, top, x + bw, top + h, meter);
            float yTop = top + h * (1 - frac);
            meter.setColor(db > -3 ? 0xFFFF3B30 : db > -12 ? 0xFFFFCC00 : 0xFF34C759);
            c.drawRect(x, yTop, x + bw, top + h, meter);
            float py = top + h * (1 - peakHold[i]);
            meter.setColor(Color.WHITE);
            c.drawRect(x, py - dp, x + bw, py + dp, meter);
        }
        label.setColor(0xCCFFFFFF);
        c.drawText(micMeter ? "L R M" : "L  R", left - 2 * dp, top + h + 13 * dp, label);
    }
}
