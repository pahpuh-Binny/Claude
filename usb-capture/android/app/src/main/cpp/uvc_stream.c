#include "uvc_stream.h"

#include <android/log.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#define TAG "UvcStream"

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

enum { BUF_FREE = 0, BUF_FILLING = 1, BUF_READY = 2, BUF_LOCKED = 3 };

#define STAT_ADD(s, k, v) __atomic_fetch_add(&(s)->stats[(k)], (int64_t)(v), __ATOMIC_RELAXED)
#define STAT_SET(s, k, v) __atomic_store_n(&(s)->stats[(k)], (int64_t)(v), __ATOMIC_RELAXED)
#define STAT_GET(s, k) __atomic_load_n(&(s)->stats[(k)], __ATOMIC_RELAXED)

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void uvc_stream_logf(uvc_stream_t *s, const char *fmt, ...) {
    char msg[200];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    /* Keep it plain ASCII so Java's NewStringUTF can never choke on it. */
    for (char *c = msg; *c; c++) {
        if ((unsigned char) *c < 0x20 || (unsigned char) *c > 0x7e) *c = '?';
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg);
    if (!s) return;
    pthread_mutex_lock(&s->log_lock);
    int idx;
    if (s->log_count < 32) {
        idx = (s->log_head + s->log_count) % 32;
        s->log_count++;
    } else {
        idx = s->log_head;
        s->log_head = (s->log_head + 1) % 32;
    }
    memcpy(s->log_ring[idx], msg, sizeof msg);
    pthread_mutex_unlock(&s->log_lock);
}

int uvc_stream_drain_log(uvc_stream_t *s, char out[][200], int max) {
    int n = 0;
    pthread_mutex_lock(&s->log_lock);
    while (s->log_count > 0 && n < max) {
        memcpy(out[n++], s->log_ring[s->log_head], 200);
        s->log_head = (s->log_head + 1) % 32;
        s->log_count--;
    }
    pthread_mutex_unlock(&s->log_lock);
    return n;
}

/* Log the first few occurrences of a recurring problem, then only at powers of two. */
static int should_log(int64_t count) {
    return count <= 3 || (count & (count - 1)) == 0;
}

uvc_stream_t *uvc_stream_create(int fd, const uvc_config_t *cfg) {
    uvc_stream_t *s = calloc(1, sizeof *s);
    if (!s) return NULL;
    s->fd = fd;
    s->cfg = *cfg;
    uvc_config_t *c = &s->cfg;
    if (c->num_urbs < 2) c->num_urbs = 2;
    if (c->num_urbs > UVC_MAX_URBS) c->num_urbs = UVC_MAX_URBS;
    if (c->iso_packets_per_urb < 1) c->iso_packets_per_urb = 1;
    if (c->iso_packets_per_urb > 128) c->iso_packets_per_urb = 128;
    if (c->num_bufs < 3) c->num_bufs = 3;
    if (c->num_bufs > UVC_MAX_BUFS) c->num_bufs = UVC_MAX_BUFS;
    if (c->initial_cap < 65536) c->initial_cap = 65536;
    if (c->max_cap < c->initial_cap) c->max_cap = c->initial_cap;
    s->tolerate_errors = c->tolerate_errors;
    s->filling = -1;
    s->last_fid = -1;

    pthread_mutex_init(&s->lock, NULL);
    pthread_mutex_init(&s->log_lock, NULL);
    pthread_condattr_t ca;
    pthread_condattr_init(&ca);
    pthread_condattr_setclock(&ca, CLOCK_MONOTONIC);
    pthread_cond_init(&s->cond, &ca);
    pthread_condattr_destroy(&ca);

    int allocated = 0;
    for (int i = 0; i < c->num_bufs; i++) {
        uint8_t *d = malloc((size_t) c->initial_cap);
        if (!d) break;
        s->bufs[i].data = d;
        s->bufs[i].cap = (size_t) c->initial_cap;
        s->bufs[i].state = BUF_FREE;
        allocated++;
    }
    if (allocated < 3) {
        uvc_stream_logf(NULL, "Out of memory allocating %d frame buffers of %lld bytes",
                        c->num_bufs, (long long) c->initial_cap);
        for (int i = 0; i < allocated; i++) free(s->bufs[i].data);
        pthread_mutex_destroy(&s->lock);
        pthread_mutex_destroy(&s->log_lock);
        pthread_cond_destroy(&s->cond);
        free(s);
        return NULL;
    }
    if (allocated < c->num_bufs) {
        uvc_stream_logf(s, "Only %d of %d frame buffers could be allocated", allocated, c->num_bufs);
        c->num_bufs = allocated;
    }
    return s;
}

static int is_iso(uvc_stream_t *s) {
    return s->cfg.xfer_type == UVC_XFER_ISO;
}

static int submit_urb(uvc_stream_t *s, int i) {
    struct usbdevfs_urb *u = s->urbs[i];
    memset(u, 0, sizeof(struct usbdevfs_urb));
    u->endpoint = (unsigned char) s->cfg.endpoint;
    u->buffer = s->urb_bufs[i];
    u->buffer_length = s->urb_len[i];
    u->usercontext = (void *) (intptr_t) i;
    if (is_iso(s)) {
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->number_of_packets = s->cfg.iso_packets_per_urb;
        for (int j = 0; j < s->cfg.iso_packets_per_urb; j++) {
            u->iso_frame_desc[j].length = (unsigned int) s->cfg.iso_packet_size;
            u->iso_frame_desc[j].actual_length = 0;
            u->iso_frame_desc[j].status = 0;
        }
    } else {
        u->type = USBDEVFS_URB_TYPE_BULK;
    }
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) {
        return -errno;
    }
    s->urb_submitted[i] = 1;
    return 0;
}

static int count_in_flight(uvc_stream_t *s) {
    int n = 0;
    for (int i = 0; i < s->urb_count; i++) n += s->urb_submitted[i];
    return n;
}

/* Called with s->lock held. */
static int take_free_locked(uvc_stream_t *s) {
    for (int k = 0; k < s->cfg.num_bufs; k++) {
        if (s->bufs[k].state == BUF_FREE) {
            s->bufs[k].state = BUF_FILLING;
            s->bufs[k].size = 0;
            s->bufs[k].flags = 0;
            return k;
        }
    }
    return -1;
}

static void try_get_fill_buffer(uvc_stream_t *s) {
    pthread_mutex_lock(&s->lock);
    if (s->filling < 0) s->filling = take_free_locked(s);
    pthread_mutex_unlock(&s->lock);
}

static void reset_frame_state(uvc_stream_t *s) {
    s->cur_error = 0;
    s->cur_overflow = 0;
    s->cur_err_tolerated = 0;
    s->cur_pts = 0;
    s->cur_bytes = 0;
    s->cur_discard = 0;
    if (s->filling >= 0) s->bufs[s->filling].size = 0;
}

static int has_eoi(const uint8_t *d, size_t n) {
    if (n < 4) return 0;
    size_t lim = n > 16384 ? n - 16384 : 1;
    for (size_t i = n - 1; i >= lim; i--) {
        if (d[i] == 0xD9 && d[i - 1] == 0xFF) return 1;
    }
    return 0;
}

static void finish_frame(uvc_stream_t *s) {
    STAT_ADD(s, ST_FRAMES_COMPLETE, 1);
    int idx = s->filling;
    int drop = -1;
    int flags = 0;
    uvc_frame_buf_t *b = idx >= 0 ? &s->bufs[idx] : NULL;

    if (s->cur_discard || !b) {
        drop = ST_DROP_NO_BUFFER;
    } else if (s->cur_overflow) {
        drop = ST_DROP_OVERFLOW;
    } else if (s->cur_error) {
        drop = ST_DROP_ERROR;
    } else if (b->size == 0) {
        drop = ST_DROP_INCOMPLETE;
    } else if (s->cfg.format == UVC_FMT_UNCOMPRESSED && s->cfg.expected_size > 0) {
        if ((int64_t) b->size < s->cfg.expected_size) {
            drop = ST_DROP_INCOMPLETE;
        } else {
            b->size = (size_t) s->cfg.expected_size; /* trim device padding */
        }
    } else if (s->cfg.format == UVC_FMT_MJPEG) {
        if (b->size < 4 || b->data[0] != 0xFF || b->data[1] != 0xD8) {
            drop = ST_DROP_INVALID;
        } else if (!has_eoi(b->data, b->size)) {
            if (s->cfg.strict_mjpeg) drop = ST_DROP_INCOMPLETE;
            else flags |= UVC_FRAME_FLAG_TRUNCATED;
        }
    }
    if (drop < 0 && s->cur_err_tolerated) flags |= UVC_FRAME_FLAG_ERROR_TOLERATED;

    if (drop >= 0) {
        int64_t n = STAT_ADD(s, drop, 1) + 1;
        if (should_log(n)) {
            static const char *names[ST_COUNT] = {
                [ST_DROP_ERROR] = "transfer error",
                [ST_DROP_INCOMPLETE] = "incomplete",
                [ST_DROP_OVERFLOW] = "too large",
                [ST_DROP_INVALID] = "not a JPEG",
                [ST_DROP_NO_BUFFER] = "no free buffer",
            };
            uvc_stream_logf(s, "Dropped frame (%s): %lld bytes received, total %lld",
                            names[drop] ? names[drop] : "?", (long long) s->cur_bytes, (long long) n);
        }
        reset_frame_state(s);
        if (s->filling < 0) try_get_fill_buffer(s);
        return;
    }

    STAT_SET(s, ST_LAST_FRAME_SIZE, b->size);
    if ((int64_t) b->size > STAT_GET(s, ST_MAX_FRAME_SIZE_SEEN)) STAT_SET(s, ST_MAX_FRAME_SIZE_SEEN, b->size);

    pthread_mutex_lock(&s->lock);
    b->state = BUF_READY;
    b->seq = s->next_seq++;
    b->ts_ns = now_ns();
    b->pts = s->cur_pts;
    b->flags = flags;
    for (int k = 0; k < s->cfg.num_bufs; k++) {
        if (k != idx && s->bufs[k].state == BUF_READY) {
            s->bufs[k].state = BUF_FREE;
            s->bufs[k].size = 0;
            STAT_ADD(s, ST_SKIPPED_BY_CONSUMER, 1);
        }
    }
    s->filling = take_free_locked(s);
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    STAT_ADD(s, ST_FRAMES_PUBLISHED, 1);
    reset_frame_state(s);
}

static void append_data(uvc_stream_t *s, const uint8_t *d, int n) {
    if (s->filling < 0) {
        if (s->cur_bytes == 0) try_get_fill_buffer(s);
        if (s->filling < 0) {
            s->cur_discard = 1;
            s->cur_bytes += n;
            return;
        }
    }
    s->cur_bytes += n;
    if (s->cur_discard || s->cur_overflow) return;
    uvc_frame_buf_t *b = &s->bufs[s->filling];
    size_t need = b->size + (size_t) n;
    if (need > b->cap) {
        size_t ncap = b->cap * 2;
        if (ncap < need) ncap = need + need / 4;
        if (ncap > (size_t) s->cfg.max_cap) ncap = (size_t) s->cfg.max_cap;
        if (need > ncap) {
            s->cur_overflow = 1;
            return;
        }
        /* Safe: a FILLING buffer is never visible to Java. */
        uint8_t *nd = realloc(b->data, ncap);
        if (!nd) {
            uvc_stream_logf(s, "Out of memory growing frame buffer to %zu bytes", ncap);
            s->cur_overflow = 1;
            return;
        }
        b->data = nd;
        b->cap = ncap;
        uvc_stream_logf(s, "Frame buffer %d grown to %zu bytes", s->filling, ncap);
    }
    memcpy(b->data + b->size, d, (size_t) n);
    b->size = need;
}

static void handle_payload(uvc_stream_t *s, const uint8_t *p, int len) {
    STAT_ADD(s, ST_PAYLOADS, 1);
    if (len < 2) {
        STAT_ADD(s, ST_EMPTY_PAYLOADS, 1);
        return;
    }
    int hlen = p[0];
    int info = p[1];
    if (hlen < 2 || hlen > len) {
        int64_t n = STAT_ADD(s, ST_BAD_HEADERS, 1) + 1;
        if (should_log(n)) uvc_stream_logf(s, "Bad payload header (len %d of %d), total %lld", hlen, len, (long long) n);
        return;
    }
    int fid = info & 0x01;
    int eof = info & 0x02;

    if (s->last_fid >= 0 && fid != s->last_fid) {
        /* A new frame started without an end-of-frame marker on the previous one. */
        if (s->cur_bytes > 0) finish_frame(s);
        else reset_frame_state(s);
    }
    s->last_fid = fid;

    if (info & 0x40) {
        int64_t n = STAT_ADD(s, ST_PAYLOAD_ERR_BIT, 1) + 1;
        if (should_log(n)) uvc_stream_logf(s, "Device flagged a payload error, total %lld", (long long) n);
        if (s->tolerate_errors) s->cur_err_tolerated = 1;
        else s->cur_error = 1;
    }
    if ((info & 0x04) && hlen >= 6) {
        s->cur_pts = (uint32_t) p[2] | ((uint32_t) p[3] << 8) | ((uint32_t) p[4] << 16) | ((uint32_t) p[5] << 24);
    }
    int dlen = len - hlen;
    if (dlen > 0) {
        append_data(s, p + hlen, dlen);
    } else {
        STAT_ADD(s, ST_EMPTY_PAYLOADS, 1);
    }
    if (eof) {
        if (s->cur_bytes > 0) finish_frame(s);
        else reset_frame_state(s);
    }
}

/* Returns -1 if the device is gone. */
static int handle_urb(uvc_stream_t *s, struct usbdevfs_urb *u, int i) {
    uint8_t *buf = s->urb_bufs[i];
    int st = u->status;
    if (st == -ENODEV || st == -ESHUTDOWN) return -1;
    if (st == -ENOENT || st == -ECONNRESET) return 0; /* cancelled */

    if (is_iso(s)) {
        if (st != 0 && st != -EXDEV) {
            int64_t n = STAT_ADD(s, ST_URB_ERRORS, 1) + 1;
            if (should_log(n)) uvc_stream_logf(s, "Isochronous transfer error %d (%s), total %lld", st, strerror(-st), (long long) n);
            if (s->tolerate_errors) s->cur_err_tolerated = 1;
            else s->cur_error = 1;
        }
        int64_t bytes = 0;
        int packets = u->number_of_packets;
        if (packets > s->cfg.iso_packets_per_urb) packets = s->cfg.iso_packets_per_urb;
        for (int j = 0; j < packets; j++) {
            struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[j];
            int pst = (int) d->status;
            int alen = (int) d->actual_length;
            if (pst != 0) {
                int64_t n = STAT_ADD(s, ST_ISO_PACKET_ERRORS, 1) + 1;
                if (should_log(n)) uvc_stream_logf(s, "Isochronous packet error %d (%s), total %lld", pst, strerror(pst < 0 ? -pst : pst), (long long) n);
                if (s->tolerate_errors) s->cur_err_tolerated = 1;
                else s->cur_error = 1;
                if (alen <= 0) continue;
            }
            if (alen <= 0) continue;
            if (alen > s->cfg.iso_packet_size) alen = s->cfg.iso_packet_size;
            bytes += alen;
            handle_payload(s, buf + (size_t) j * (size_t) s->cfg.iso_packet_size, alen);
        }
        STAT_ADD(s, ST_BYTES, bytes);
    } else {
        if (st == -EPIPE) {
            int64_t n = STAT_ADD(s, ST_STALLS_CLEARED, 1) + 1;
            if (should_log(n)) uvc_stream_logf(s, "Bulk endpoint stalled, clearing (total %lld)", (long long) n);
            uvc_clear_halt(s->fd, s->cfg.endpoint);
            s->cur_error = 1;
            return 0;
        }
        if (st != 0 && st != -EREMOTEIO) {
            int64_t n = STAT_ADD(s, ST_URB_ERRORS, 1) + 1;
            if (should_log(n)) uvc_stream_logf(s, "Bulk transfer error %d (%s), total %lld", st, strerror(-st), (long long) n);
            if (s->tolerate_errors) s->cur_err_tolerated = 1;
            else s->cur_error = 1;
            return 0;
        }
        int alen = u->actual_length;
        if (alen > s->urb_len[i]) alen = s->urb_len[i];
        if (alen > 0) {
            STAT_ADD(s, ST_BYTES, alen);
            handle_payload(s, buf, alen);
        }
    }
    return 0;
}

static void cancel_all(uvc_stream_t *s) {
    for (int i = 0; i < s->urb_count; i++) {
        if (s->urb_submitted[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    }
    int64_t deadline = now_ns() + 1500000000LL;
    while (count_in_flight(s) > 0 && now_ns() < deadline) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) == 0) {
            int i = (int) (intptr_t) u->usercontext;
            if (i >= 0 && i < s->urb_count && s->urbs[i] == u) s->urb_submitted[i] = 0;
            continue;
        }
        if (errno == EAGAIN || errno == EINTR) {
            struct pollfd p = {.fd = s->fd, .events = POLLOUT | POLLWRNORM};
            poll(&p, 1, 20);
            continue;
        }
        break; /* ENODEV etc.: nothing more will come back */
    }
    int left = count_in_flight(s);
    if (left > 0) uvc_stream_logf(s, "%d transfers were not returned by the kernel; keeping their memory", left);
}

static void *reaper_main(void *arg) {
    uvc_stream_t *s = arg;
    pthread_setname_np(pthread_self(), "uvc-reaper");
    static const int prios[] = {-19, -16, -10, -8, -4};
    int prio_set = 0;
    for (size_t k = 0; k < sizeof prios / sizeof prios[0]; k++) {
        if (setpriority(PRIO_PROCESS, (id_t) gettid(), prios[k]) == 0) {
            prio_set = prios[k];
            break;
        }
    }
    uvc_stream_logf(s, "Streaming thread started (priority %d, %d transfers of %d bytes)", prio_set,
                    s->urb_count, s->urb_len[0]);

    while (s->running) {
        struct pollfd p = {.fd = s->fd, .events = POLLOUT | POLLWRNORM};
        int r = poll(&p, 1, 100);
        if (r < 0) {
            if (errno == EINTR) continue;
            s->last_errno = errno;
            uvc_stream_logf(s, "poll failed: %s", strerror(errno));
            s->stop_reason = UVC_STOP_FATAL;
            break;
        }
        if (r == 0) continue;
        if (p.revents & (POLLHUP | POLLERR | POLLNVAL)) {
            uvc_stream_logf(s, "Device disconnected (poll events 0x%x)", p.revents);
            s->stop_reason = UVC_STOP_DEVICE_GONE;
            break;
        }
        for (;;) {
            struct usbdevfs_urb *u = NULL;
            if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
                int e = errno;
                if (e == EAGAIN) break;
                if (e == EINTR) continue;
                s->last_errno = e;
                if (e == ENODEV) {
                    uvc_stream_logf(s, "Device disconnected while streaming");
                    s->stop_reason = UVC_STOP_DEVICE_GONE;
                } else {
                    uvc_stream_logf(s, "Reaping transfers failed: %s", strerror(e));
                    s->stop_reason = UVC_STOP_FATAL;
                }
                goto out;
            }
            int i = (int) (intptr_t) u->usercontext;
            if (i < 0 || i >= s->urb_count || s->urbs[i] != u) {
                uvc_stream_logf(s, "Kernel returned an unknown transfer, ignoring");
                continue;
            }
            s->urb_submitted[i] = 0;
            STAT_ADD(s, ST_URBS_REAPED, 1);
            if (handle_urb(s, u, i) < 0) {
                uvc_stream_logf(s, "Device disconnected (transfer status %d)", u->status);
                s->stop_reason = UVC_STOP_DEVICE_GONE;
                goto out;
            }
            if (s->running) {
                int rr = submit_urb(s, i);
                if (rr < 0) {
                    s->last_errno = -rr;
                    int64_t n = STAT_ADD(s, ST_RESUBMIT_FAILURES, 1) + 1;
                    if (rr == -ENODEV || rr == -ESHUTDOWN) {
                        uvc_stream_logf(s, "Device disconnected (resubmit)");
                        s->stop_reason = UVC_STOP_DEVICE_GONE;
                        goto out;
                    }
                    if (should_log(n)) uvc_stream_logf(s, "Re-queueing a transfer failed: %s (total %lld)", strerror(-rr), (long long) n);
                }
            }
        }
        int inflight = count_in_flight(s);
        STAT_SET(s, ST_URBS_IN_FLIGHT, inflight);
        if (inflight == 0 && s->running) {
            /* Every transfer failed to re-queue; try once more before giving up. */
            int ok = 0;
            for (int i = 0; i < s->urb_count; i++) {
                if (submit_urb(s, i) == 0) ok++;
            }
            if (ok == 0) {
                uvc_stream_logf(s, "No transfers could be queued; stopping stream");
                s->stop_reason = UVC_STOP_FATAL;
                break;
            }
            uvc_stream_logf(s, "Recovered %d transfers after all had failed", ok);
        }
    }
out:
    pthread_mutex_lock(&s->lock);
    if (s->stop_reason == UVC_STOP_NONE) s->stop_reason = UVC_STOP_REQUESTED;
    s->running = 0;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    cancel_all(s);
    STAT_SET(s, ST_URBS_IN_FLIGHT, count_in_flight(s));
    uvc_stream_logf(s, "Streaming thread stopped (reason %d)", s->stop_reason);
    return NULL;
}

int uvc_stream_start(uvc_stream_t *s) {
    if (!s) return -EINVAL;
    if (s->thread_started) return -EALREADY;
    int iso = is_iso(s);
    int len = iso ? s->cfg.iso_packet_size * s->cfg.iso_packets_per_urb : s->cfg.bulk_urb_size;
    if (len <= 0) return -EINVAL;

    for (int i = 0; i < s->cfg.num_urbs; i++) {
        size_t sz = sizeof(struct usbdevfs_urb) +
                    (iso ? (size_t) s->cfg.iso_packets_per_urb * sizeof(struct usbdevfs_iso_packet_desc) : 0);
        struct usbdevfs_urb *u = calloc(1, sz);
        void *b = NULL;
        if (!u || posix_memalign(&b, 64, (size_t) len) != 0) {
            free(u);
            break;
        }
        s->urbs[i] = u;
        s->urb_bufs[i] = b;
        s->urb_len[i] = len;
        s->urb_count++;
    }
    /* Bulk is flow-controlled, so one transfer still works (just slower); isochronous needs a queue. */
    int min_urbs = iso ? 2 : 1;
    if (s->urb_count < min_urbs) {
        uvc_stream_logf(s, "Out of memory allocating transfers");
        return -ENOMEM;
    }

    pthread_mutex_lock(&s->lock);
    s->filling = take_free_locked(s);
    s->stop_reason = UVC_STOP_NONE;
    pthread_mutex_unlock(&s->lock);
    reset_frame_state(s);
    s->running = 1;

    for (int i = 0; i < s->urb_count; i++) {
        int r = submit_urb(s, i);
        if (r == 0) continue;
        if (r == -ENOMEM && i >= min_urbs) {
            uvc_stream_logf(s, "usbfs memory limit reached; streaming with %d transfers instead of %d", i, s->urb_count);
            for (int j = i; j < s->urb_count; j++) {
                free(s->urbs[j]);
                free(s->urb_bufs[j]);
                s->urbs[j] = NULL;
                s->urb_bufs[j] = NULL;
            }
            s->urb_count = i;
            break;
        }
        uvc_stream_logf(s, "Queueing transfer %d failed: %s (%d)", i, strerror(-r), r);
        s->last_errno = -r;
        s->running = 0;
        cancel_all(s);
        return r;
    }

    STAT_SET(s, ST_URBS_IN_FLIGHT, count_in_flight(s));
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    int r = pthread_create(&s->thread, &attr, reaper_main, s);
    pthread_attr_destroy(&attr);
    if (r != 0) {
        uvc_stream_logf(s, "Could not start streaming thread: %s", strerror(r));
        s->running = 0;
        cancel_all(s);
        return -r;
    }
    s->thread_started = 1;
    return 0;
}

void uvc_stream_stop(uvc_stream_t *s) {
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (s->stop_reason == UVC_STOP_NONE) s->stop_reason = UVC_STOP_REQUESTED;
    s->running = 0;
    pthread_cond_broadcast(&s->cond);
    pthread_mutex_unlock(&s->lock);
    if (s->thread_started) {
        pthread_join(s->thread, NULL);
        s->thread_started = 0;
    }
}

int uvc_stream_destroy(uvc_stream_t *s) {
    if (!s) return 0;
    uvc_stream_stop(s);
    for (int i = 0; i < s->urb_count; i++) {
        if (s->urb_submitted[i]) continue; /* the kernel may still copy into it: leak */
        free(s->urbs[i]);
        free(s->urb_bufs[i]);
        s->urbs[i] = NULL;
        s->urb_bufs[i] = NULL;
    }
    pthread_mutex_lock(&s->lock);
    int locked = s->locked_count;
    pthread_mutex_unlock(&s->lock);
    if (locked > 0) {
        uvc_stream_logf(NULL, "Stream destroyed with %d frames still in use; keeping its memory", locked);
        return 1;
    }
    for (int i = 0; i < UVC_MAX_BUFS; i++) free(s->bufs[i].data);
    pthread_mutex_destroy(&s->lock);
    pthread_mutex_destroy(&s->log_lock);
    pthread_cond_destroy(&s->cond);
    free(s);
    return 0;
}

int uvc_stream_acquire(uvc_stream_t *s, int timeout_ms, int64_t *info) {
    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (long) (timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }
    pthread_mutex_lock(&s->lock);
    for (;;) {
        int best = -1;
        for (int k = 0; k < s->cfg.num_bufs; k++) {
            if (s->bufs[k].state == BUF_READY && (best < 0 || s->bufs[k].seq > s->bufs[best].seq)) best = k;
        }
        if (best >= 0) {
            uvc_frame_buf_t *b = &s->bufs[best];
            b->state = BUF_LOCKED;
            s->locked_count++;
            info[0] = (int64_t) b->size;
            info[1] = b->seq;
            info[2] = b->ts_ns;
            info[3] = b->pts;
            info[4] = b->flags;
            info[5] = 0;
            pthread_mutex_unlock(&s->lock);
            STAT_ADD(s, ST_DELIVERED, 1);
            return best;
        }
        if (!s->running) {
            pthread_mutex_unlock(&s->lock);
            return -2;
        }
        if (timeout_ms <= 0) {
            pthread_mutex_unlock(&s->lock);
            return -1;
        }
        int r = pthread_cond_timedwait(&s->cond, &s->lock, &deadline);
        if (r == ETIMEDOUT) {
            pthread_mutex_unlock(&s->lock);
            return -1;
        }
    }
}

void uvc_stream_release(uvc_stream_t *s, int idx) {
    if (!s || idx < 0 || idx >= s->cfg.num_bufs) return;
    pthread_mutex_lock(&s->lock);
    if (s->bufs[idx].state == BUF_LOCKED) {
        s->bufs[idx].state = BUF_FREE;
        s->bufs[idx].size = 0;
        s->locked_count--;
    }
    pthread_mutex_unlock(&s->lock);
}

void uvc_stream_get_stats(uvc_stream_t *s, int64_t *out, int n) {
    for (int i = 0; i < n && i < ST_COUNT; i++) out[i] = STAT_GET(s, i);
    if (n > ST_STOP_REASON) out[ST_STOP_REASON] = s->stop_reason;
    if (n > ST_LAST_ERRNO) out[ST_LAST_ERRNO] = s->last_errno;
}

int uvc_set_interface(int fd, int iface, int alt) {
    struct usbdevfs_setinterface si;
    si.interface = (unsigned int) iface;
    si.altsetting = (unsigned int) alt;
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &si) < 0) return -errno;
    return 0;
}

int uvc_clear_halt(int fd, int endpoint) {
    unsigned int ep = (unsigned int) endpoint;
    if (ioctl(fd, USBDEVFS_CLEAR_HALT, &ep) < 0) return -errno;
    return 0;
}

int uvc_get_speed(int fd) {
    int r = ioctl(fd, USBDEVFS_GET_SPEED);
    return r < 0 ? -errno : r;
}
