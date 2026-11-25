package io.github.pahpuhbinny.usbcapture.render;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.pahpuhbinny.usbcapture.log.AppLog;
import io.github.pahpuhbinny.usbcapture.uvc.PixelFormat;
import io.github.pahpuhbinny.usbcapture.video.Frame;
import io.github.pahpuhbinny.usbcapture.video.FrameSource;

/**
 * Draws video on its own high-priority thread with OpenGL ES 3.
 *
 * Every frame is first converted to an RGB texture at source resolution
 * (YUV maths happen on the GPU), then drawn to the screen with the
 * chosen scaling, colour adjustments and exposure aids. The same RGB
 * texture feeds the video encoder, screenshots and the histogram, so
 * those never cost a second conversion.
 */
public final class GlRenderer implements SurfaceHolder.Callback {
    private static final String TAG = "Renderer";

    public interface Listener {
        void onFirstFrame(int width, int height);

        void onRenderError(String message);
    }

    public interface ScreenshotCallback {
        void onScreenshot(Bitmap bitmap, String error);
    }

    public interface AnalysisListener {
        void onAnalysis(Analysis a);
    }

    public static final class Analysis {
        public final int[] luma = new int[256];
        public final int[] red = new int[256];
        public final int[] green = new int[256];
        public final int[] blue = new int[256];
        public int samples;
        public float averageLuma;
        public float lumaStdDev;
        public int averageColor;
    }

    public static final class Stats {
        public float renderFps;
        public float inputFps;
        public float uploadMs;
        public float latencyMs;
        public long hiccups;
        public long framesRendered;
        public long recordedFrames;
        public String gpu = "";
    }

    private final Listener listener;
    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean running;
    private volatile boolean dead;

    // ---- pending commands (guarded by lock)
    private boolean windowChanged;
    private Surface pendingWindow;
    private int pendingWindowW, pendingWindowH;
    private CountDownLatch windowLatch;
    private boolean sourceChanged;
    private FrameSource pendingSource;
    private CountDownLatch sourceLatch;
    private boolean encoderChanged;
    private Surface pendingEncoder;
    private int pendingEncW, pendingEncH;
    private CountDownLatch encoderLatch;
    private final List<ScreenshotCallback> screenshotRequests = new ArrayList<>();
    private boolean wake;

    private volatile DisplayParams params = new DisplayParams();
    private volatile boolean redraw = true;
    private volatile int formatMatrixHint;
    private volatile boolean lowLatency = true;
    private volatile boolean swapIntervalDirty;
    private volatile AnalysisListener analysisListener;
    private volatile boolean showIdlePattern;

    // ---- render-thread state
    private EglCore egl;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private Surface windowSurface;
    private int windowW, windowH;
    private EGLSurface encoder = EGL14.EGL_NO_SURFACE;
    private int encW, encH;
    private long lastEncoderTs;
    private FrameSource source;
    private boolean hasContent;
    private int contentW, contentH;
    private boolean firstFrameReported;
    private long lastFrameTs;

    private final int[] inTex = new int[3];
    private final int[][] inTexDesc = new int[3][4]; // internalFormat, w, h, extra
    private int rgbTex, rgbFbo, rgbW, rgbH;
    private boolean rgbMipmaps;
    private int shotTex, shotFbo, shotW, shotH;
    private int anaTex, anaFbo;
    private static final int ANA_W = 160, ANA_H = 90;
    private ByteBuffer anaBuf;
    private long lastAnalysis;

    private final Map<String, Integer> programs = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> uniforms = new HashMap<>();
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final FloatBuffer fullQuad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final long startNs = System.nanoTime();
    private long patternFrames;
    private int consecutiveErrors;
    private int maxTextureSize = 4096;
    private long errorCount;

    // ---- stats
    private final Stats stats = new Stats();
    private long statWindowStart = SystemClock.uptimeMillis();
    private int statRendered, statInput;
    private double statUploadNs, statLatencyNs;
    private int statLatencyCount;
    private long hiccupWindowStart = SystemClock.uptimeMillis();
    private int hiccupsInWindow;
    private long worstGapMs;

    private static final float[] M601 = {1f, 1f, 1f, 0f, -0.344136f, 1.772f, 1.402f, -0.714136f, 0f};
    private static final float[] M709 = {1f, 1f, 1f, 0f, -0.187324f, 1.8556f, 1.5748f, -0.468124f, 0f};

    public GlRenderer(Listener listener) {
        this.listener = listener;
        fullQuad.put(new float[]{-1, -1, 0, 0, -1, 1, 0, 1, 1, -1, 1, 0, 1, 1, 1, 1}).position(0);
    }

    // ==================================================================
    // Public API (any thread)

    public void start() {
        running = true;
        thread = new Thread(this::threadMain, "gl-render");
        thread.start();
    }

    public void release() {
        running = false;
        poke();
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void setParams(DisplayParams p) {
        params = p.copy();
        redraw = true;
        poke();
    }

    public DisplayParams params() {
        return params.copy();
    }

    public void setFormatMatrixHint(int uvcMatrix) {
        formatMatrixHint = uvcMatrix;
    }

    public void setLowLatency(boolean on) {
        lowLatency = on;
        swapIntervalDirty = true;
        poke();
    }

    public void setShowIdlePattern(boolean on) {
        showIdlePattern = on;
        redraw = true;
        poke();
    }

    public void setAnalysisListener(AnalysisListener l) {
        analysisListener = l;
    }

    public Stats stats() {
        synchronized (stats) {
            Stats s = new Stats();
            s.renderFps = stats.renderFps;
            s.inputFps = stats.inputFps;
            s.uploadMs = stats.uploadMs;
            s.latencyMs = stats.latencyMs;
            s.hiccups = stats.hiccups;
            s.framesRendered = stats.framesRendered;
            s.recordedFrames = stats.recordedFrames;
            s.gpu = stats.gpu;
            return s;
        }
    }

    /** Switches the frame source; waits until the render thread no longer touches the old one. */
    public void setSource(FrameSource s) {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            if (dead) return;
            sourceChanged = true;
            pendingSource = s;
            if (sourceLatch != null) sourceLatch.countDown();
            sourceLatch = latch;
            wake = true;
            lock.notifyAll();
        }
        await(latch, "source switch");
    }

    /** Attaches (or with null, detaches) a video encoder's input surface. Blocks until applied. */
    public void setEncoderSurface(Surface s, int w, int h) {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            if (dead) return;
            encoderChanged = true;
            pendingEncoder = s;
            pendingEncW = w;
            pendingEncH = h;
            if (encoderLatch != null) encoderLatch.countDown();
            encoderLatch = latch;
            wake = true;
            lock.notifyAll();
        }
        await(latch, "encoder switch");
    }

    public void requestScreenshot(ScreenshotCallback cb) {
        synchronized (lock) {
            if (dead) {
                cb.onScreenshot(null, "Renderer is not running");
                return;
            }
            screenshotRequests.add(cb);
            redraw = true;
            wake = true;
            lock.notifyAll();
        }
    }

    private void poke() {
        synchronized (lock) {
            wake = true;
            lock.notifyAll();
        }
    }

    private void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(2500, TimeUnit.MILLISECONDS)) AppLog.w(TAG, "Timed out waiting for " + what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // SurfaceHolder.Callback (UI thread)
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        setWindow(holder.getSurface(), width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        setWindow(null, 0, 0);
    }

    private void setWindow(Surface s, int w, int h) {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            if (dead) return;
            windowChanged = true;
            pendingWindow = s;
            pendingWindowW = w;
            pendingWindowH = h;
            if (windowLatch != null) windowLatch.countDown();
            windowLatch = latch;
            wake = true;
            lock.notifyAll();
        }
        await(latch, "display surface");
    }

    // ==================================================================
    // Render thread

    private void threadMain() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
        }
        try {
            egl = new EglCore();
            try {
                pbuffer = egl.createPbuffer(1, 1);
            } catch (Throwable t) {
                AppLog.w(TAG, "No pbuffer support; using surfaceless context");
                pbuffer = EGL14.EGL_NO_SURFACE;
            }
            egl.makeCurrent(pbuffer);
            initGl();
        } catch (Throwable t) {
            AppLog.e(TAG, "OpenGL setup failed", t);
            listener.onRenderError("This phone's graphics driver could not start (" + t.getMessage() + ")");
            markDead();
            return;
        }
        while (running) {
            try {
                iterate();
                consecutiveErrors = 0;
            } catch (Throwable t) {
                errorCount++;
                consecutiveErrors++;
                if (errorCount <= 5 || errorCount % 500 == 0) AppLog.e(TAG, "Render error #" + errorCount, t);
                if (consecutiveErrors == 200) listener.onRenderError("Video drawing keeps failing; see the log for details");
                SystemClock.sleep(10);
            }
        }
        cleanup();
        markDead();
    }

    private void markDead() {
        synchronized (lock) {
            dead = true;
            if (sourceLatch != null) sourceLatch.countDown();
            if (encoderLatch != null) encoderLatch.countDown();
            if (windowLatch != null) windowLatch.countDown();
            for (ScreenshotCallback cb : screenshotRequests) cb.onScreenshot(null, "Renderer stopped");
            screenshotRequests.clear();
        }
    }

    private void initGl() {
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        int[] max = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0);
        if (max[0] > 0) maxTextureSize = max[0];
        synchronized (stats) {
            stats.gpu = renderer + " · " + version;
        }
        AppLog.i(TAG, "GPU: " + renderer + ", " + version + ", max texture " + max[0]
                + (egl.recordable ? ", recordable" : ", NOT recordable (recording may fail)"));
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
        GLES20.glGenTextures(3, inTex, 0);
        for (int t : inTex) setupTexture(t, GLES20.GL_NEAREST);
    }

    private static void setupTexture(int tex, int filter) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void iterate() {
        handleCommands();
        if (swapIntervalDirty && window != EGL14.EGL_NO_SURFACE) {
            swapIntervalDirty = false;
            egl.makeCurrent(window);
            EGL14.eglSwapInterval(egl.display, lowLatency ? 0 : 1);
        }
        FrameSource src = source;
        boolean canDraw = window != EGL14.EGL_NO_SURFACE || encoder != EGL14.EGL_NO_SURFACE;
        boolean newFrame = false;
        long frameTs = 0;

        if (src != null) {
            Frame f = src.acquire(redraw ? 0 : 16);
            if (f != null) {
                try {
                    if (canDraw) {
                        makeAnyCurrent();
                        long t0 = System.nanoTime();
                        if (uploadAndConvert(f)) {
                            statUploadNs += System.nanoTime() - t0;
                            newFrame = true;
                            frameTs = f.timestampNs;
                        }
                    }
                } finally {
                    src.release(f);
                }
                statInput++;
                trackGaps(f.timestampNs, src.nominalFps());
            }
        } else {
            waitForWake(redraw ? 0 : (showIdlePattern ? 16 : 100));
            if (showIdlePattern && canDraw) {
                makeAnyCurrent();
                Frame f = new Frame();
                f.pixelFormat = PixelFormat.PATTERN;
                f.width = 1920;
                f.height = 1080;
                f.timestampNs = System.nanoTime();
                if (uploadAndConvert(f)) {
                    newFrame = true;
                    frameTs = f.timestampNs;
                }
            }
        }
        if (!canDraw) {
            updateStats();
            return;
        }
        boolean wantShot;
        synchronized (lock) {
            wantShot = !screenshotRequests.isEmpty();
        }
        if (newFrame || redraw || wantShot) {
            redraw = false;
            DisplayParams p = params;
            if (window != EGL14.EGL_NO_SURFACE) {
                egl.makeCurrent(window);
                drawToScreen(p);
                if (!egl.swap(window)) {
                    int err = EGL14.eglGetError();
                    if (err == 0x300D /* EGL_BAD_SURFACE */) AppLog.w(TAG, "Display surface lost");
                }
                statRendered++;
                if (newFrame && frameTs > 0) {
                    statLatencyNs += System.nanoTime() - frameTs;
                    statLatencyCount++;
                }
            }
            if (newFrame && encoder != EGL14.EGL_NO_SURFACE && hasContent) {
                long ts = Math.max(frameTs, lastEncoderTs + 1000);
                lastEncoderTs = ts;
                egl.makeCurrent(encoder);
                drawCapture(p, encW, encH, 0, false);
                egl.setPresentationTime(encoder, ts);
                egl.swap(encoder);
                synchronized (stats) {
                    stats.recordedFrames++;
                }
            }
            if (wantShot && hasContent) doScreenshots(p);
            else if (wantShot) failScreenshots("No picture yet");
            AnalysisListener al = analysisListener;
            if (al != null && hasContent && newFrame && SystemClock.uptimeMillis() - lastAnalysis >= 100) {
                lastAnalysis = SystemClock.uptimeMillis();
                doAnalysis(p, al);
            }
        }
        updateStats();
    }

    private void makeAnyCurrent() {
        if (window != EGL14.EGL_NO_SURFACE) egl.makeCurrent(window);
        else if (encoder != EGL14.EGL_NO_SURFACE) egl.makeCurrent(encoder);
        else makeIdle();
    }

    private void waitForWake(long ms) {
        synchronized (lock) {
            if (!wake && ms > 0) {
                try {
                    lock.wait(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            wake = false;
        }
    }

    private void handleCommands() {
        boolean wc, sc, ec;
        Surface w, e;
        int ww, wh, ew, eh;
        FrameSource s;
        CountDownLatch wl, sl, el;
        synchronized (lock) {
            wc = windowChanged;
            sc = sourceChanged;
            ec = encoderChanged;
            w = pendingWindow;
            ww = pendingWindowW;
            wh = pendingWindowH;
            s = pendingSource;
            e = pendingEncoder;
            ew = pendingEncW;
            eh = pendingEncH;
            wl = windowLatch;
            sl = sourceLatch;
            el = encoderLatch;
            windowChanged = sourceChanged = encoderChanged = false;
            windowLatch = sourceLatch = encoderLatch = null;
            wake = false;
        }
        if (wc) {
            try {
                if (w != windowSurface || w == null) {
                    if (window != EGL14.EGL_NO_SURFACE) {
                        makeIdle();
                        egl.releaseSurface(window);
                        window = EGL14.EGL_NO_SURFACE;
                    }
                    windowSurface = null;
                    if (w != null && w.isValid()) {
                        window = egl.createWindow(w);
                        windowSurface = w;
                    }
                }
                windowW = ww;
                windowH = wh;
                if (window != EGL14.EGL_NO_SURFACE) {
                    egl.makeCurrent(window);
                    EGL14.eglSwapInterval(egl.display, lowLatency ? 0 : 1);
                }
                redraw = true;
            } catch (Throwable t) {
                AppLog.e(TAG, "Display surface setup failed", t);
                window = EGL14.EGL_NO_SURFACE;
                windowSurface = null;
            } finally {
                if (wl != null) wl.countDown();
            }
        }
        if (sc) {
            source = s;
            if (s == null) {
                hasContent = false;
                firstFrameReported = false;
            } else {
                firstFrameReported = false;
            }
            lastFrameTs = 0;
            redraw = true;
            if (sl != null) sl.countDown();
        }
        if (ec) {
            try {
                if (encoder != EGL14.EGL_NO_SURFACE) {
                    makeAnyCurrentExcept();
                    egl.releaseSurface(encoder);
                    encoder = EGL14.EGL_NO_SURFACE;
                }
                if (e != null && e.isValid()) {
                    encoder = egl.createWindow(e);
                    encW = ew;
                    encH = eh;
                    lastEncoderTs = 0;
                    synchronized (stats) {
                        stats.recordedFrames = 0;
                    }
                }
            } catch (Throwable t) {
                AppLog.e(TAG, "Encoder surface setup failed", t);
                encoder = EGL14.EGL_NO_SURFACE;
            } finally {
                if (el != null) el.countDown();
            }
        }
    }

    private void makeAnyCurrentExcept() {
        if (window != EGL14.EGL_NO_SURFACE) egl.makeCurrent(window);
        else makeIdle();
    }

    /** Keeps the context current without a drawable when possible; otherwise releases it. */
    private void makeIdle() {
        try {
            egl.makeCurrent(pbuffer);
        } catch (Throwable t) {
            EGL14.eglMakeCurrent(egl.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
    }

    // ------------------------------------------------------------------
    // Upload / convert

    private boolean uploadAndConvert(Frame f) {
        int w = f.width, h = f.height;
        if (w <= 0 || h <= 0) return false;
        if (w > maxTextureSize || h > maxTextureSize) {
            warnOnce("too-big", "Frame " + w + "x" + h + " is larger than this GPU supports (" + maxTextureSize + ")");
            if (warned.add("too-big-ui")) listener.onRenderError("This resolution is too large for this phone's GPU; choose a lower one");
            return false;
        }
        String prog;
        int swap = 0;
        int flipY = 0;
        ByteBuffer d = f.data;
        int fmt = f.pixelFormat;
        if (fmt != PixelFormat.BITMAP && fmt != PixelFormat.PATTERN) {
            long need = PixelFormat.frameBytes(fmt, w, h);
            if (d == null || need <= 0 || d.capacity() < need) {
                warnOnce("short-" + fmt, "Skipping a frame that is smaller than expected (" + (d == null ? 0 : d.capacity())
                        + " of " + need + " bytes)");
                return false;
            }
        }
        int cw = (w + 1) / 2, ch = (h + 1) / 2;
        switch (fmt) {
            case PixelFormat.YUY2:
            case PixelFormat.UYVY:
            case PixelFormat.YVYU:
                uploadPlane(0, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, cw, h, d, 0);
                prog = fmt == PixelFormat.YUY2 ? "yuy2" : fmt == PixelFormat.UYVY ? "uyvy" : "yvyu";
                break;
            case PixelFormat.NV12:
            case PixelFormat.NV21:
                uploadPlane(0, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, w, h, d, 0);
                uploadPlane(1, GLES30.GL_RG8, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, cw, ch, d, w * h);
                swap = fmt == PixelFormat.NV21 ? 1 : 0;
                prog = "nv12";
                break;
            case PixelFormat.I420:
            case PixelFormat.YV12: {
                int uOff = w * h, vOff = w * h + cw * ch;
                if (fmt == PixelFormat.YV12) {
                    int t = uOff;
                    uOff = vOff;
                    vOff = t;
                }
                uploadPlane(0, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, w, h, d, 0);
                uploadPlane(1, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, cw, ch, d, uOff);
                uploadPlane(2, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, cw, ch, d, vOff);
                prog = "planar";
                break;
            }
            case PixelFormat.GREY:
                uploadPlane(0, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, w, h, d, 0);
                prog = "grey";
                break;
            case PixelFormat.RGB565:
                uploadPlane(0, GLES30.GL_RGB565, GLES30.GL_RGB, GLES30.GL_UNSIGNED_SHORT_5_6_5, w, h, d, 0);
                prog = "rgb";
                break;
            case PixelFormat.BGR24:
            case PixelFormat.RGB24:
                uploadPlane(0, GLES30.GL_RGB8, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, w, h, d, 0);
                swap = fmt == PixelFormat.BGR24 ? 1 : 0;
                flipY = fmt == PixelFormat.BGR24 ? 1 : 0; // DIB-style bottom-up rows
                prog = "rgb";
                break;
            case PixelFormat.BGRA32:
                uploadPlane(0, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, w, h, d, 0);
                swap = 1;
                prog = "rgb";
                break;
            case PixelFormat.BITMAP:
                if (f.bitmap == null || f.bitmap.isRecycled()) return false;
                uploadBitmap(f.bitmap);
                prog = "rgb";
                break;
            case PixelFormat.PATTERN:
                prog = "pattern";
                break;
            default:
                warnOnce("fmt-" + fmt, "Cannot draw pixel format " + PixelFormat.name(fmt));
                return false;
        }
        int program = program(prog);
        if (program == 0) return false;

        ensureRgbTarget(w, h);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rgbFbo);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glUseProgram(program);
        for (int i = 0; i < 3; i++) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex[i]);
            GLES30.glUniform1i(loc(program, "t" + i), i);
        }
        setYuvUniforms(program, h);
        GLES30.glUniform2i(loc(program, "u_size"), w, h);
        GLES30.glUniform1i(loc(program, "u_flipY"), flipY);
        GLES30.glUniform1i(loc(program, "u_swap"), swap);
        GLES30.glUniform1f(loc(program, "u_time"), (System.nanoTime() - startNs) / 1e9f);
        GLES30.glUniform1f(loc(program, "u_frame"), (float) (patternFrames++ % 6000));
        drawQuad(fullQuad);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        rgbMipmaps = false;

        hasContent = true;
        contentW = w;
        contentH = h;
        if (!firstFrameReported && fmt != PixelFormat.PATTERN) {
            firstFrameReported = true;
            listener.onFirstFrame(w, h);
        }
        return true;
    }

    private void setYuvUniforms(int program, int h) {
        DisplayParams p = params;
        boolean bt709;
        if (p.yuvMatrix == DisplayParams.MATRIX_709) bt709 = true;
        else if (p.yuvMatrix == DisplayParams.MATRIX_601) bt709 = false;
        else if (formatMatrixHint == 1) bt709 = true;
        else if (formatMatrixHint == 4 || formatMatrixHint == 5 || formatMatrixHint == 6) bt709 = false;
        else bt709 = h >= 720;
        boolean full = p.yuvRange == DisplayParams.RANGE_FULL;
        GLES30.glUniformMatrix3fv(loc(program, "u_yuv"), 1, false, bt709 ? M709 : M601, 0);
        if (full) {
            GLES30.glUniform3f(loc(program, "u_off"), 0f, -128f / 255f, -128f / 255f);
            GLES30.glUniform2f(loc(program, "u_scale"), 1f, 1f);
        } else {
            GLES30.glUniform3f(loc(program, "u_off"), -16f / 255f, -128f / 255f, -128f / 255f);
            GLES30.glUniform2f(loc(program, "u_scale"), 255f / 219f, 255f / 224f);
        }
    }

    private void uploadPlane(int i, int internal, int format, int type, int w, int h, ByteBuffer data, int offset) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex[i]);
        int[] desc = inTexDesc[i];
        ByteBuffer slice = data.duplicate();
        slice.position(offset);
        if (desc[0] != internal || desc[1] != w || desc[2] != h) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, slice);
            desc[0] = internal;
            desc[1] = w;
            desc[2] = h;
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, slice);
        }
    }

    private void uploadBitmap(Bitmap b) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex[0]);
        int[] desc = inTexDesc[0];
        int cfg = b.getConfig() == Bitmap.Config.RGB_565 ? -2 : -1;
        if (desc[0] != cfg || desc[1] != b.getWidth() || desc[2] != b.getHeight()) {
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
            desc[0] = cfg;
            desc[1] = b.getWidth();
            desc[2] = b.getHeight();
        } else {
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, b);
        }
    }

    private void ensureRgbTarget(int w, int h) {
        if (rgbTex != 0 && rgbW == w && rgbH == h) return;
        int[] ids = new int[1];
        if (rgbTex == 0) {
            GLES30.glGenTextures(1, ids, 0);
            rgbTex = ids[0];
            GLES30.glGenFramebuffers(1, ids, 0);
            rgbFbo = ids[0];
        }
        setupTexture(rgbTex, GLES30.GL_LINEAR);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rgbFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, rgbTex, 0);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) AppLog.e(TAG, "Frame buffer incomplete: 0x" + Integer.toHexString(st));
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        rgbW = w;
        rgbH = h;
        rgbMipmaps = false;
        AppLog.i(TAG, "Video size " + w + "x" + h);
    }

    // ------------------------------------------------------------------
    // Drawing

    private void drawToScreen(DisplayParams p) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, windowW, windowH);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        if (!hasContent) return;
        Geometry g = Geometry.compute(p, windowW, windowH, contentW, contentH);
        float shrink = g.scale;
        int filter;
        if (p.filter == DisplayParams.FILTER_NEAREST) filter = GLES30.GL_NEAREST;
        else filter = GLES30.GL_LINEAR;
        boolean useMips = p.filter == DisplayParams.FILTER_SMOOTH && shrink < 0.6f;
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTex);
        if (useMips && !rgbMipmaps) {
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            rgbMipmaps = true;
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                useMips ? GLES30.GL_LINEAR_MIPMAP_LINEAR : filter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
        drawPresent(p, g, windowW, windowH, true, false);
    }

    /** Draws the clean picture (no zoom/overlays) into the current target of size w x h. */
    private void drawCapture(DisplayParams p, int w, int h, int fbo, boolean flipY) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        Geometry g = Geometry.forCapture(p, w, h, contentW, contentH);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        drawPresent(p, g, w, h, false, flipY);
    }

    private void drawPresent(DisplayParams p, Geometry g, int vw, int vh, boolean overlays, boolean flipY) {
        int program = program("present");
        if (program == 0) return;
        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTex);
        GLES30.glUniform1i(loc(program, "u_tex"), 0);
        GLES30.glUniform2f(loc(program, "u_texSize"), contentW, contentH);
        boolean sharp = overlays && p.filter == DisplayParams.FILTER_SHARP && g.scale > 1.01f;
        GLES30.glUniform1i(loc(program, "u_sharpFilter"), sharp ? 1 : 0);
        GLES30.glUniform1f(loc(program, "u_prescale"), Math.max(1f, (float) Math.floor(g.scale)));
        boolean adjust = (overlays || p.adjustCaptures) && p.colorAdjusted();
        GLES30.glUniform1i(loc(program, "u_adjust"), adjust ? 1 : 0);
        GLES30.glUniform1f(loc(program, "u_brightness"), p.brightness);
        GLES30.glUniform1f(loc(program, "u_contrast"), p.contrast);
        GLES30.glUniform1f(loc(program, "u_saturation"), p.saturation);
        GLES30.glUniform1f(loc(program, "u_gamma"), Math.max(0.1f, p.gamma));
        GLES30.glUniform1f(loc(program, "u_hue"), (float) Math.toRadians(p.hue));
        GLES30.glUniform1i(loc(program, "u_mono"), p.mono ? 1 : 0);
        GLES30.glUniform1f(loc(program, "u_sharpen"), (overlays || p.adjustCaptures) ? p.sharpen : 0f);
        GLES30.glUniform1f(loc(program, "u_scanlines"), overlays ? p.scanlines : 0f);
        GLES30.glUniform1i(loc(program, "u_zebra"), overlays && p.zebra ? 1 : 0);
        GLES30.glUniform1f(loc(program, "u_zebraLevel"), p.zebraLevel);
        GLES30.glUniform1i(loc(program, "u_peaking"), overlays && p.peaking ? 1 : 0);
        GLES30.glUniform1f(loc(program, "u_peakThreshold"), p.peakThreshold);
        int c = p.peakColor;
        GLES30.glUniform3f(loc(program, "u_peakColor"), ((c >> 16) & 0xff) / 255f, ((c >> 8) & 0xff) / 255f, (c & 0xff) / 255f);
        GLES30.glUniform1i(loc(program, "u_falseColor"), overlays && p.falseColor ? 1 : 0);

        float l = g.rect.left / vw * 2f - 1f;
        float r = g.rect.right / vw * 2f - 1f;
        float t = 1f - g.rect.top / vh * 2f;
        float b = 1f - g.rect.bottom / vh * 2f;
        if (flipY) {
            t = -t;
            b = -b;
        }
        float[] uv = g.uv;
        quad.position(0);
        // strip order: TL, BL, TR, BR
        quad.put(l).put(t).put(uv[0]).put(uv[1]);
        quad.put(l).put(b).put(uv[6]).put(uv[7]);
        quad.put(r).put(t).put(uv[2]).put(uv[3]);
        quad.put(r).put(b).put(uv[4]).put(uv[5]);
        quad.position(0);
        drawQuad(quad);
    }

    private void drawQuad(FloatBuffer q) {
        q.position(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, q);
        GLES30.glEnableVertexAttribArray(0);
        q.position(2);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, q);
        GLES30.glEnableVertexAttribArray(1);
        q.position(0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ------------------------------------------------------------------
    // Screenshots & analysis

    private void failScreenshots(String why) {
        List<ScreenshotCallback> list;
        synchronized (lock) {
            list = new ArrayList<>(screenshotRequests);
            screenshotRequests.clear();
        }
        for (ScreenshotCallback cb : list) cb.onScreenshot(null, why);
    }

    private void doScreenshots(DisplayParams p) {
        List<ScreenshotCallback> list;
        synchronized (lock) {
            list = new ArrayList<>(screenshotRequests);
            screenshotRequests.clear();
        }
        Bitmap bmp = null;
        String err = null;
        try {
            makeAnyCurrent();
            int[] size = Geometry.captureSize(p, contentW, contentH);
            int w = size[0], h = size[1];
            int[] ids = new int[1];
            if (shotTex == 0) {
                GLES30.glGenTextures(1, ids, 0);
                shotTex = ids[0];
                GLES30.glGenFramebuffers(1, ids, 0);
                shotFbo = ids[0];
            }
            if (shotW != w || shotH != h) {
                setupTexture(shotTex, GLES30.GL_LINEAR);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shotFbo);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, shotTex, 0);
                shotW = w;
                shotH = h;
            }
            drawCapture(p, w, h, shotFbo, true);
            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shotFbo);
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            buf.position(0);
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);
        } catch (Throwable t) {
            AppLog.e(TAG, "Screenshot failed", t);
            err = "Screenshot failed: " + t.getMessage();
            bmp = null;
        }
        for (int i = 0; i < list.size(); i++) {
            Bitmap b = bmp;
            if (b != null && i > 0) b = bmp.copy(Bitmap.Config.ARGB_8888, false);
            list.get(i).onScreenshot(b, err);
        }
    }

    private void doAnalysis(DisplayParams p, AnalysisListener al) {
        try {
            int[] ids = new int[1];
            if (anaTex == 0) {
                GLES30.glGenTextures(1, ids, 0);
                anaTex = ids[0];
                setupTexture(anaTex, GLES30.GL_LINEAR);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, ANA_W, ANA_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
                GLES30.glGenFramebuffers(1, ids, 0);
                anaFbo = ids[0];
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, anaFbo);
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, anaTex, 0);
                anaBuf = ByteBuffer.allocateDirect(ANA_W * ANA_H * 4).order(ByteOrder.nativeOrder());
            }
            DisplayParams q = p.copy();
            q.adjustCaptures = true;
            q.rotation = 0;
            q.crop = 0;
            q.aspect = 0;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgbTex);
            if (rgbMipmaps) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, anaFbo);
            GLES30.glViewport(0, 0, ANA_W, ANA_H);
            Geometry g = Geometry.compute(q, ANA_W, ANA_H, contentW, contentH);
            g.rect.set(0, 0, ANA_W, ANA_H);
            drawPresent(q, g, ANA_W, ANA_H, false, false);
            anaBuf.position(0);
            GLES30.glReadPixels(0, 0, ANA_W, ANA_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, anaBuf);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            Analysis a = new Analysis();
            int n = ANA_W * ANA_H;
            double sum = 0, sum2 = 0;
            long sr = 0, sg = 0, sb = 0;
            for (int i = 0; i < n; i++) {
                int r = anaBuf.get(i * 4) & 0xff;
                int gr = anaBuf.get(i * 4 + 1) & 0xff;
                int b = anaBuf.get(i * 4 + 2) & 0xff;
                int y = (r * 54 + gr * 183 + b * 19) >> 8;
                a.luma[y]++;
                a.red[r]++;
                a.green[gr]++;
                a.blue[b]++;
                sum += y;
                sum2 += (double) y * y;
                sr += r;
                sg += gr;
                sb += b;
            }
            a.samples = n;
            a.averageLuma = (float) (sum / n / 255.0);
            double var = sum2 / n - (sum / n) * (sum / n);
            a.lumaStdDev = (float) (Math.sqrt(Math.max(0, var)) / 255.0);
            a.averageColor = 0xff000000 | ((int) (sr / n) << 16) | ((int) (sg / n) << 8) | (int) (sb / n);
            al.onAnalysis(a);
        } catch (Throwable t) {
            warnOnce("analysis", "Histogram failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Stats

    private void trackGaps(long ts, double nominalFps) {
        if (ts <= 0) return;
        if (lastFrameTs > 0 && nominalFps > 0) {
            long gapMs = (ts - lastFrameTs) / 1_000_000L;
            long limit = (long) Math.max(50, 2.5 * 1000.0 / nominalFps);
            if (gapMs > limit && gapMs < 5000) {
                hiccupsInWindow++;
                worstGapMs = Math.max(worstGapMs, gapMs);
                synchronized (stats) {
                    stats.hiccups++;
                }
            }
        }
        lastFrameTs = ts;
        long now = SystemClock.uptimeMillis();
        if (now - hiccupWindowStart >= 10_000) {
            if (hiccupsInWindow > 0) {
                AppLog.w(TAG, String.format(Locale.US, "Video hiccups: %d frame gaps over %d ms in the last %d s (worst %d ms)",
                        hiccupsInWindow, (long) Math.max(50, 2.5 * 1000.0 / Math.max(1, nominalFps)),
                        (now - hiccupWindowStart) / 1000, worstGapMs));
            }
            hiccupWindowStart = now;
            hiccupsInWindow = 0;
            worstGapMs = 0;
        }
    }

    private void updateStats() {
        long now = SystemClock.uptimeMillis();
        long dt = now - statWindowStart;
        if (dt < 1000) return;
        synchronized (stats) {
            stats.renderFps = statRendered * 1000f / dt;
            stats.inputFps = statInput * 1000f / dt;
            stats.uploadMs = statInput > 0 ? (float) (statUploadNs / statInput / 1e6) : 0f;
            stats.latencyMs = statLatencyCount > 0 ? (float) (statLatencyNs / statLatencyCount / 1e6) : 0f;
            stats.framesRendered += statRendered;
        }
        statWindowStart = now;
        statRendered = 0;
        statInput = 0;
        statUploadNs = 0;
        statLatencyNs = 0;
        statLatencyCount = 0;
    }

    private final java.util.Set<String> warned = new java.util.HashSet<>();

    private void warnOnce(String key, String msg) {
        if (warned.add(key)) AppLog.w(TAG, msg);
    }

    // ------------------------------------------------------------------
    // Programs

    private int program(String name) {
        Integer p = programs.get(name);
        if (p != null) return p;
        String frag;
        switch (name) {
            case "yuy2": frag = Shaders.YUY2; break;
            case "uyvy": frag = Shaders.UYVY; break;
            case "yvyu": frag = Shaders.YVYU; break;
            case "nv12": frag = Shaders.NV12; break;
            case "planar": frag = Shaders.PLANAR; break;
            case "grey": frag = Shaders.GREY; break;
            case "rgb": frag = Shaders.RGB; break;
            case "pattern": frag = Shaders.PATTERN; break;
            case "present": frag = Shaders.PRESENT; break;
            default: return 0;
        }
        int prog = build(name, Shaders.VERTEX, frag);
        programs.put(name, prog);
        if (prog == 0) listener.onRenderError("A video shader failed to compile (" + name + "); see the log");
        return prog;
    }

    private int loc(int program, String name) {
        Map<String, Integer> m = uniforms.get(program);
        if (m == null) {
            m = new HashMap<>();
            uniforms.put(program, m);
        }
        Integer l = m.get(name);
        if (l == null) {
            l = GLES30.glGetUniformLocation(program, name);
            m.put(name, l);
        }
        return l;
    }

    private static int compile(int type, String src, String name) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            AppLog.e(TAG, "Shader " + name + " failed: " + GLES30.glGetShaderInfoLog(s));
            GLES30.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static int build(String name, String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs, name + ".vert");
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs, name + ".frag");
        if (v == 0 || f == 0) return 0;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glBindAttribLocation(p, 0, "a_pos");
        GLES30.glBindAttribLocation(p, 1, "a_uv");
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        if (ok[0] == 0) {
            AppLog.e(TAG, "Program " + name + " failed to link: " + GLES30.glGetProgramInfoLog(p));
            GLES30.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private void cleanup() {
        try {
            if (encoder != EGL14.EGL_NO_SURFACE) egl.releaseSurface(encoder);
            if (window != EGL14.EGL_NO_SURFACE) egl.releaseSurface(window);
            if (pbuffer != EGL14.EGL_NO_SURFACE) egl.releaseSurface(pbuffer);
            egl.release();
        } catch (Throwable t) {
            AppLog.w(TAG, "GL cleanup", t);
        }
    }
}
