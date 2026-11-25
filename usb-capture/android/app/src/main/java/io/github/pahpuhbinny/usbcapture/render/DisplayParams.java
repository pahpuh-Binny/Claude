package io.github.pahpuhbinny.usbcapture.render;

/** Everything that changes how the picture is drawn. Copied whole to the render thread. */
public final class DisplayParams {
    public static final int SCALE_FIT = 0;
    public static final int SCALE_FILL = 1;
    public static final int SCALE_STRETCH = 2;
    public static final int SCALE_INTEGER = 3;
    public static final int SCALE_ORIGINAL = 4;

    public static final int FILTER_SMOOTH = 0;
    public static final int FILTER_NEAREST = 1;
    public static final int FILTER_SHARP = 2;

    public static final int MATRIX_AUTO = 0, MATRIX_601 = 1, MATRIX_709 = 2;
    public static final int RANGE_AUTO = 0, RANGE_LIMITED = 1, RANGE_FULL = 2;

    public int scaleMode = SCALE_FIT;
    /** Forced display aspect ratio (width / height); 0 = use the source's. */
    public float aspect;
    public int rotation;          // 0, 90, 180, 270
    public boolean mirror;
    public boolean flip;
    public int filter = FILTER_SMOOTH;
    /** Overscan crop per edge, 0..0.15. */
    public float crop;
    public float zoom = 1f;
    public float panX;            // -1..1
    public float panY;

    public float brightness;      // -0.5..0.5
    public float contrast = 1f;   // 0..2
    public float saturation = 1f; // 0..2
    public float gamma = 1f;      // 0.3..3
    public float hue;             // degrees -180..180
    public float sharpen;         // 0..2
    public float scanlines;       // 0..1
    public boolean mono;

    public int yuvMatrix = MATRIX_AUTO;
    public int yuvRange = RANGE_AUTO;

    public boolean zebra;
    public float zebraLevel = 0.95f;
    public boolean peaking;
    public float peakThreshold = 0.18f;
    public int peakColor = 0xFFFF3B30;
    public boolean falseColor;

    /** Apply colour/sharpen settings to screenshots and recordings too. */
    public boolean adjustCaptures = true;

    public boolean colorAdjusted() {
        return brightness != 0f || contrast != 1f || saturation != 1f || gamma != 1f || hue != 0f || mono;
    }

    public DisplayParams copy() {
        DisplayParams p = new DisplayParams();
        p.scaleMode = scaleMode;
        p.aspect = aspect;
        p.rotation = rotation;
        p.mirror = mirror;
        p.flip = flip;
        p.filter = filter;
        p.crop = crop;
        p.zoom = zoom;
        p.panX = panX;
        p.panY = panY;
        p.brightness = brightness;
        p.contrast = contrast;
        p.saturation = saturation;
        p.gamma = gamma;
        p.hue = hue;
        p.sharpen = sharpen;
        p.scanlines = scanlines;
        p.mono = mono;
        p.yuvMatrix = yuvMatrix;
        p.yuvRange = yuvRange;
        p.zebra = zebra;
        p.zebraLevel = zebraLevel;
        p.peaking = peaking;
        p.peakThreshold = peakThreshold;
        p.peakColor = peakColor;
        p.falseColor = falseColor;
        p.adjustCaptures = adjustCaptures;
        return p;
    }
}
