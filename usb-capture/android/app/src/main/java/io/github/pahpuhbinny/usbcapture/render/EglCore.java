package io.github.pahpuhbinny.usbcapture.render;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/** Minimal EGL helper: one ES 3 context that can draw to the screen and to a video encoder. */
final class EglCore {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    EGLContext context = EGL14.EGL_NO_CONTEXT;
    EGLConfig config;
    boolean recordable;

    EglCore() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) throw new RuntimeException("eglInitialize failed");
        config = choose(true);
        recordable = config != null;
        if (config == null) config = choose(false);
        if (config == null) throw new RuntimeException("No suitable EGL config (OpenGL ES 3 is required)");
        int[] attrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attrs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Could not create an OpenGL ES 3 context: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private EGLConfig choose(boolean withRecordable) {
        int[] attrs = withRecordable ? new int[]{
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        } : new int[]{
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, num, 0) || num[0] < 1) return null;
        return cfgs[0];
    }

    EGLSurface createWindow(Surface s) {
        int[] attrs = {EGL14.EGL_NONE};
        EGLSurface surf = EGL14.eglCreateWindowSurface(display, config, s, attrs, 0);
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        return surf;
    }

    EGLSurface createPbuffer(int w, int h) {
        int[] attrs = {EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE};
        EGLSurface surf = EGL14.eglCreatePbufferSurface(display, config, attrs, 0);
        if (surf == null || surf == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("eglCreatePbufferSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
        return surf;
    }

    void makeCurrent(EGLSurface s) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) {
            throw new RuntimeException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    boolean swap(EGLSurface s) {
        return EGL14.eglSwapBuffers(display, s);
    }

    void setPresentationTime(EGLSurface s, long nsecs) {
        EGLExt.eglPresentationTimeANDROID(display, s, nsecs);
    }

    void releaseSurface(EGLSurface s) {
        if (s != null && s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, s);
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
    }
}
