#include <jni.h>
#include <stdint.h>
#include <string.h>

#include "uvc_stream.h"

void crash_handler_install(const char *path);

#define FN(name) Java_io_github_pahpuhbinny_usbcapture_uvc_UvcNative_##name
#define H(h) ((uvc_stream_t *) (intptr_t) (h))

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *env, jclass cls, jint fd, jlongArray cfgArr) {
    jlong c[16];
    memset(c, 0, sizeof c);
    jsize n = (*env)->GetArrayLength(env, cfgArr);
    if (n > 16) n = 16;
    (*env)->GetLongArrayRegion(env, cfgArr, 0, n, c);
    uvc_config_t cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.endpoint = (int) c[0];
    cfg.xfer_type = (int) c[1];
    cfg.iso_packet_size = (int) c[2];
    cfg.iso_packets_per_urb = (int) c[3];
    cfg.bulk_urb_size = (int) c[4];
    cfg.num_urbs = (int) c[5];
    cfg.format = (int) c[6];
    cfg.expected_size = c[7];
    cfg.initial_cap = c[8];
    cfg.max_cap = c[9];
    cfg.num_bufs = (int) c[10];
    cfg.strict_mjpeg = (int) c[11];
    cfg.tolerate_errors = (int) c[12];
    return (jlong) (intptr_t) uvc_stream_create(fd, &cfg);
}

JNIEXPORT jint JNICALL FN(nativeStart)(JNIEnv *env, jclass cls, jlong h) {
    return h ? uvc_stream_start(H(h)) : -22;
}

JNIEXPORT void JNICALL FN(nativeStop)(JNIEnv *env, jclass cls, jlong h) {
    if (h) uvc_stream_stop(H(h));
}

JNIEXPORT jint JNICALL FN(nativeDestroy)(JNIEnv *env, jclass cls, jlong h) {
    return h ? uvc_stream_destroy(H(h)) : 0;
}

JNIEXPORT jint JNICALL FN(nativeAcquire)(JNIEnv *env, jclass cls, jlong h, jint timeoutMs, jlongArray infoArr) {
    if (!h) return -2;
    int64_t info[6];
    int idx = uvc_stream_acquire(H(h), timeoutMs, info);
    if (idx >= 0) {
        jlong j[6];
        for (int i = 0; i < 6; i++) j[i] = (jlong) info[i];
        (*env)->SetLongArrayRegion(env, infoArr, 0, 6, j);
    }
    return idx;
}

JNIEXPORT jobject JNICALL FN(nativeView)(JNIEnv *env, jclass cls, jlong h, jint idx) {
    if (!h) return NULL;
    uvc_stream_t *s = H(h);
    if (idx < 0 || idx >= s->cfg.num_bufs) return NULL;
    /* The caller holds the buffer locked, so data and size are stable. */
    uvc_frame_buf_t *b = &s->bufs[idx];
    if (!b->data || b->size == 0) return NULL;
    return (*env)->NewDirectByteBuffer(env, b->data, (jlong) b->size);
}

JNIEXPORT void JNICALL FN(nativeRelease)(JNIEnv *env, jclass cls, jlong h, jint idx) {
    if (h) uvc_stream_release(H(h), idx);
}

JNIEXPORT void JNICALL FN(nativeGetStats)(JNIEnv *env, jclass cls, jlong h, jlongArray out) {
    if (!h) return;
    int64_t st[ST_COUNT];
    uvc_stream_get_stats(H(h), st, ST_COUNT);
    jsize n = (*env)->GetArrayLength(env, out);
    if (n > ST_COUNT) n = ST_COUNT;
    jlong j[ST_COUNT];
    for (int i = 0; i < n; i++) j[i] = (jlong) st[i];
    (*env)->SetLongArrayRegion(env, out, 0, n, j);
}

JNIEXPORT jobjectArray JNICALL FN(nativeDrainLog)(JNIEnv *env, jclass cls, jlong h) {
    if (!h) return NULL;
    static char msgs[32][200];
    static pthread_mutex_t drain_lock = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&drain_lock);
    int n = uvc_stream_drain_log(H(h), msgs, 32);
    jobjectArray arr = NULL;
    if (n > 0) {
        jclass strCls = (*env)->FindClass(env, "java/lang/String");
        arr = (*env)->NewObjectArray(env, n, strCls, NULL);
        for (int i = 0; arr && i < n; i++) {
            msgs[i][199] = 0;
            jstring js = (*env)->NewStringUTF(env, msgs[i]);
            if (!js) break;
            (*env)->SetObjectArrayElement(env, arr, i, js);
            (*env)->DeleteLocalRef(env, js);
        }
    }
    pthread_mutex_unlock(&drain_lock);
    return arr;
}

JNIEXPORT void JNICALL FN(nativeSetTolerateErrors)(JNIEnv *env, jclass cls, jlong h, jboolean on) {
    if (h) H(h)->tolerate_errors = on ? 1 : 0;
}

JNIEXPORT jint JNICALL FN(nativeSetInterface)(JNIEnv *env, jclass cls, jint fd, jint iface, jint alt) {
    return uvc_set_interface(fd, iface, alt);
}

JNIEXPORT jint JNICALL FN(nativeClearHalt)(JNIEnv *env, jclass cls, jint fd, jint ep) {
    return uvc_clear_halt(fd, ep);
}

JNIEXPORT jint JNICALL FN(nativeGetSpeed)(JNIEnv *env, jclass cls, jint fd) {
    return uvc_get_speed(fd);
}

JNIEXPORT void JNICALL FN(nativeInstallCrashHandler)(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return;
    crash_handler_install(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT jstring JNICALL FN(nativeStrError)(JNIEnv *env, jclass cls, jint err) {
    char buf[128];
    const char *m = strerror(err < 0 ? -err : err);
    size_t n = strlen(m);
    if (n > sizeof buf - 1) n = sizeof buf - 1;
    for (size_t i = 0; i < n; i++) buf[i] = (m[i] >= 0x20 && m[i] < 0x7f) ? m[i] : '?';
    buf[n] = 0;
    return (*env)->NewStringUTF(env, buf);
}
