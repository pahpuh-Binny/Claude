package io.github.pahpuhbinny.usbcapture.render;

import android.graphics.RectF;

/**
 * Works out where the picture goes on screen (scale mode, aspect ratio,
 * zoom/pan) and which part of the source each corner shows (crop,
 * rotation, mirror, flip). Shared by the renderer and the overlay view so
 * grids line up exactly with the video.
 */
public final class Geometry {
    /** Picture rectangle in view pixels (may extend past the view). */
    public final RectF rect = new RectF();
    /** Texture coordinates for screen corners TL, TR, BR, BL (u,v pairs; v = 0 is the image top). */
    public final float[] uv = new float[8];
    /** Screen pixels per source pixel (for sharp-bilinear). */
    public float scale = 1f;
    /** Size of the source after crop and rotation, in source pixels. */
    public float contentW, contentH;

    private Geometry() {
    }

    public static Geometry compute(DisplayParams p, int viewW, int viewH, int srcW, int srcH) {
        Geometry g = new Geometry();
        if (viewW <= 0 || viewH <= 0 || srcW <= 0 || srcH <= 0) {
            setRect(g, 0, 0, Math.max(viewW, 1), Math.max(viewH, 1));
            setUv(g, p, 0f);
            return g;
        }
        float crop = Math.max(0f, Math.min(0.2f, p.crop));
        float cw = srcW * (1f - 2f * crop);
        float ch = srcH * (1f - 2f * crop);
        boolean rotated = p.rotation == 90 || p.rotation == 270;
        float aspect = p.aspect > 0 ? p.aspect : cw / ch;
        // Natural pixel size of the content (honouring a forced aspect ratio).
        float pw = ch * aspect;
        float ph = ch;
        if (rotated) {
            aspect = 1f / aspect;
            float t = pw;
            pw = ph;
            ph = t;
        }
        g.contentW = pw;
        g.contentH = ph;

        float w, h;
        float viewAspect = (float) viewW / viewH;
        int k = (int) Math.floor(Math.min(viewW / pw, viewH / ph));
        if (p.scaleMode == DisplayParams.SCALE_FILL) {
            if (viewAspect > aspect) {
                w = viewW;
                h = w / aspect;
            } else {
                h = viewH;
                w = h * aspect;
            }
        } else if (p.scaleMode == DisplayParams.SCALE_STRETCH) {
            w = viewW;
            h = viewH;
        } else if (p.scaleMode == DisplayParams.SCALE_ORIGINAL) {
            w = pw;
            h = ph;
        } else if (p.scaleMode == DisplayParams.SCALE_INTEGER && k >= 1) {
            w = pw * k;
            h = ph * k;
        } else {
            // Fit (also used by pixel-perfect when the source is larger than the screen)
            if (viewAspect > aspect) {
                h = viewH;
                w = h * aspect;
            } else {
                w = viewW;
                h = w / aspect;
            }
        }
        float zoom = Math.max(1f, Math.min(8f, p.zoom));
        w *= zoom;
        h *= zoom;
        float maxPanX = Math.max(0f, (w - viewW) / 2f);
        float maxPanY = Math.max(0f, (h - viewH) / 2f);
        float cx = viewW / 2f - clamp(p.panX) * maxPanX;
        float cy = viewH / 2f - clamp(p.panY) * maxPanY;
        setRect(g, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        g.scale = Math.min(w / pw, h / ph);
        setUv(g, p, crop);
        return g;
    }

    /** Geometry for an off-screen target of exactly the content size (recording/screenshot). */
    public static Geometry forCapture(DisplayParams p, int targetW, int targetH, int srcW, int srcH) {
        DisplayParams q = p.copy();
        q.scaleMode = DisplayParams.SCALE_FIT;
        q.zoom = 1f;
        q.panX = 0f;
        q.panY = 0f;
        return compute(q, targetW, targetH, srcW, srcH);
    }

    /** Size of the content after crop/rotation/aspect, rounded to even numbers. */
    public static int[] captureSize(DisplayParams p, int srcW, int srcH) {
        float crop = Math.max(0f, Math.min(0.2f, p.crop));
        float cw = srcW * (1f - 2f * crop);
        float ch = srcH * (1f - 2f * crop);
        float aspect = p.aspect > 0 ? p.aspect : cw / ch;
        float w = ch * aspect;
        float h = ch;
        if (p.rotation == 90 || p.rotation == 270) {
            float t = w;
            w = h;
            h = t;
        }
        int iw = Math.max(2, Math.round(w / 2f) * 2);
        int ih = Math.max(2, Math.round(h / 2f) * 2);
        return new int[]{iw, ih};
    }

    /** Plain field writes (keeps this class testable on the JVM). */
    private static void setRect(Geometry g, float l, float t, float r, float b) {
        g.rect.left = l;
        g.rect.top = t;
        g.rect.right = r;
        g.rect.bottom = b;
    }

    public float width() {
        return rect.right - rect.left;
    }

    public float height() {
        return rect.bottom - rect.top;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static void setUv(Geometry g, DisplayParams p, float crop) {
        float u0 = crop, u1 = 1f - crop, v0 = crop, v1 = 1f - crop;
        if (p.mirror) {
            float t = u0;
            u0 = u1;
            u1 = t;
        }
        if (p.flip) {
            float t = v0;
            v0 = v1;
            v1 = t;
        }
        // Image corners clockwise from top-left.
        float[][] img = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        int k = ((p.rotation % 360) + 360) % 360 / 90;
        for (int i = 0; i < 4; i++) {
            float[] c = img[((i - k) % 4 + 4) % 4];
            g.uv[i * 2] = c[0];
            g.uv[i * 2 + 1] = c[1];
        }
    }
}
