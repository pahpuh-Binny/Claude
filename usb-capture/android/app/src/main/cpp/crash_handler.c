/*
 * Writes a short report to a file when native code crashes, then hands the
 * signal back to the previous handler so Android still records its own
 * tombstone. ART's libsigchain keeps its own SIGSEGV handling (used for
 * Java null checks) ahead of this one.
 *
 * Only async-signal-safe calls are used for the essential part (open,
 * write, close); the backtrace with symbol names is best effort.
 */
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

static char g_path[512];
static struct sigaction g_old[65];
static volatile int g_in_handler;
static const int kSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSYS};

static void w(int fd, const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    while (n > 0) {
        ssize_t r = write(fd, s, n);
        if (r <= 0) return;
        s += r;
        n -= (size_t) r;
    }
}

static void wdec(int fd, long long v) {
    char b[24];
    int i = 23;
    int neg = v < 0;
    unsigned long long u = neg ? (unsigned long long) (-(v + 1)) + 1 : (unsigned long long) v;
    b[i] = 0;
    do {
        b[--i] = (char) ('0' + u % 10);
        u /= 10;
    } while (u && i > 1);
    if (neg) b[--i] = '-';
    w(fd, b + i);
}

static void whex(int fd, uintptr_t v) {
    char b[2 + sizeof(uintptr_t) * 2 + 1];
    static const char *hx = "0123456789abcdef";
    int n = (int) sizeof(uintptr_t) * 2;
    b[0] = '0';
    b[1] = 'x';
    for (int i = 0; i < n; i++) b[2 + i] = hx[(v >> ((n - 1 - i) * 4)) & 0xf];
    b[2 + n] = 0;
    w(fd, b);
}

static const char *sig_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV (invalid memory access)";
        case SIGABRT: return "SIGABRT (abort)";
        case SIGBUS: return "SIGBUS (bus error)";
        case SIGFPE: return "SIGFPE (arithmetic error)";
        case SIGILL: return "SIGILL (illegal instruction)";
        case SIGSYS: return "SIGSYS (bad system call)";
        default: return "unknown signal";
    }
}

struct bt_state {
    uintptr_t *cur;
    uintptr_t *end;
};

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    struct bt_state *st = arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc) {
        if (st->cur == st->end) return _URC_END_OF_STACK;
        *st->cur++ = pc;
    }
    return _URC_NO_REASON;
}

static void handler(int sig, siginfo_t *info, void *uc) {
    if (!g_in_handler) {
        g_in_handler = 1;
        int fd = open(g_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd >= 0) {
            char tname[17] = {0};
            prctl(PR_GET_NAME, (unsigned long) tname, 0, 0, 0);
            w(fd, "=== Native crash ===\n");
            w(fd, "Signal: ");
            w(fd, sig_name(sig));
            w(fd, "  code ");
            wdec(fd, info ? info->si_code : 0);
            w(fd, "\nFault address: ");
            whex(fd, info ? (uintptr_t) info->si_addr : 0);
            w(fd, "\nThread: ");
            w(fd, tname);
            w(fd, " (tid ");
            wdec(fd, (long long) syscall(SYS_gettid));
            w(fd, ")\nTime (unix seconds): ");
            wdec(fd, (long long) time(NULL));
            w(fd, "\nBacktrace:\n");
            uintptr_t frames[48];
            struct bt_state st = {frames, frames + 48};
            _Unwind_Backtrace(unwind_cb, &st);
            for (uintptr_t *p = frames; p < st.cur; p++) {
                w(fd, "  #");
                wdec(fd, (long long) (p - frames));
                w(fd, " pc ");
                Dl_info dli;
                if (dladdr((void *) *p, &dli) && dli.dli_fname) {
                    whex(fd, *p - (uintptr_t) dli.dli_fbase);
                    w(fd, "  ");
                    w(fd, dli.dli_fname);
                    if (dli.dli_sname) {
                        w(fd, " (");
                        w(fd, dli.dli_sname);
                        w(fd, "+");
                        wdec(fd, (long long) (*p - (uintptr_t) dli.dli_saddr));
                        w(fd, ")");
                    }
                } else {
                    whex(fd, *p);
                }
                w(fd, "\n");
            }
            w(fd, "=== end ===\n\n");
            close(fd);
        }
    }
    /* Give the signal back to whoever handled it before us (debuggerd). */
    sigaction(sig, &g_old[sig], NULL);
    if (info == NULL || info->si_code <= 0) {
        /* Sent with kill/raise/abort: send it again now that the old handler is back. */
        syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), sig);
    }
    /* Hardware faults re-trigger when the faulting instruction runs again. */
}

void crash_handler_install(const char *path) {
    size_t n = strlen(path);
    if (n >= sizeof g_path) n = sizeof g_path - 1;
    memcpy(g_path, path, n);
    g_path[n] = 0;
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (size_t i = 0; i < sizeof kSignals / sizeof kSignals[0]; i++) {
        sigaction(kSignals[i], &sa, &g_old[kSignals[i]]);
    }
}
