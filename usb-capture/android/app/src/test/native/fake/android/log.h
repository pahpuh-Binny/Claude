/* Host stand-in for <android/log.h> so the engine can be unit-tested on Linux. */
#pragma once
#include <stdio.h>
#include <stdarg.h>
#define ANDROID_LOG_INFO 4
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void) prio;
    if (getenv("UVC_TEST_VERBOSE")) {
        va_list ap;
        va_start(ap, fmt);
        fprintf(stderr, "[%s] ", tag);
        vfprintf(stderr, fmt, ap);
        fprintf(stderr, "\n");
        va_end(ap);
    }
    return 0;
}
