/*
 * Low-level UVC video streaming over Linux usbfs.
 *
 * Java opens the USB device through UsbManager, claims the interfaces,
 * negotiates the video mode with control transfers and then hands the
 * file descriptor to this engine, which keeps a queue of isochronous or
 * bulk transfers in flight on a dedicated high-priority thread and
 * assembles UVC payloads into complete frames.
 *
 * Frames are published into a small ring of buffers with
 * "latest frame wins" semantics: if the consumer (renderer / decoder)
 * is slower than the device, older frames are dropped instead of
 * queueing up, so the USB side never stalls and latency never grows.
 */
#ifndef UVC_STREAM_H
#define UVC_STREAM_H

#include <stdint.h>
#include <stddef.h>
#include <pthread.h>

#define UVC_XFER_ISO 0
#define UVC_XFER_BULK 1

#define UVC_FMT_MJPEG 1
#define UVC_FMT_UNCOMPRESSED 2
#define UVC_FMT_FRAME_BASED 3

#define UVC_MAX_URBS 64
#define UVC_MAX_BUFS 10

/* Frame flags reported to Java. */
#define UVC_FRAME_FLAG_TRUNCATED 1   /* MJPEG frame without an end-of-image marker */
#define UVC_FRAME_FLAG_ERROR_TOLERATED 2

/* Stop reasons. */
#define UVC_STOP_NONE 0
#define UVC_STOP_REQUESTED 1
#define UVC_STOP_DEVICE_GONE 2
#define UVC_STOP_FATAL 3

enum {
    ST_URBS_REAPED = 0,
    ST_BYTES,
    ST_PAYLOADS,
    ST_EMPTY_PAYLOADS,
    ST_BAD_HEADERS,
    ST_ISO_PACKET_ERRORS,
    ST_URB_ERRORS,
    ST_FRAMES_COMPLETE,
    ST_FRAMES_PUBLISHED,
    ST_DROP_ERROR,
    ST_DROP_INCOMPLETE,
    ST_DROP_OVERFLOW,
    ST_DROP_INVALID,
    ST_DROP_NO_BUFFER,
    ST_SKIPPED_BY_CONSUMER,
    ST_DELIVERED,
    ST_RESUBMIT_FAILURES,
    ST_STALLS_CLEARED,
    ST_PAYLOAD_ERR_BIT,
    ST_URBS_IN_FLIGHT,
    ST_LAST_FRAME_SIZE,
    ST_MAX_FRAME_SIZE_SEEN,
    ST_STOP_REASON,
    ST_LAST_ERRNO,
    ST_COUNT
};

typedef struct {
    int endpoint;            /* bEndpointAddress, e.g. 0x81 */
    int xfer_type;           /* UVC_XFER_ISO / UVC_XFER_BULK */
    int iso_packet_size;     /* bytes per (micro)frame service interval */
    int iso_packets_per_urb; /* 1..128 */
    int bulk_urb_size;       /* bytes per bulk URB (= dwMaxPayloadTransferSize) */
    int num_urbs;            /* requested number of URBs in flight */
    int format;              /* UVC_FMT_* */
    int64_t expected_size;   /* exact size of an uncompressed frame, 0 if unknown */
    int64_t initial_cap;     /* initial capacity of each frame buffer */
    int64_t max_cap;         /* buffers may grow up to this size */
    int num_bufs;            /* number of frame buffers (3..UVC_MAX_BUFS) */
    int strict_mjpeg;        /* drop MJPEG frames with no EOI marker */
    int tolerate_errors;     /* keep frames that had packet errors */
} uvc_config_t;

typedef struct {
    uint8_t *data;
    size_t cap;
    size_t size;
    int state;
    int flags;
    int64_t seq;
    int64_t ts_ns;
    uint32_t pts;
} uvc_frame_buf_t;

typedef struct uvc_stream {
    int fd;
    uvc_config_t cfg;

    /* URBs */
    void *urbs[UVC_MAX_URBS];
    uint8_t *urb_bufs[UVC_MAX_URBS];
    int urb_len[UVC_MAX_URBS];
    int urb_submitted[UVC_MAX_URBS];
    int urb_count;

    /* frame ring */
    uvc_frame_buf_t bufs[UVC_MAX_BUFS];
    int filling;               /* index of buffer being assembled, -1 = discarding */
    int last_fid;              /* -1 unknown */
    int cur_error;
    int cur_overflow;
    int cur_err_tolerated;
    int cur_discard;           /* part of the current frame had nowhere to go */
    int64_t cur_bytes;         /* bytes received for the current frame */
    uint32_t cur_pts;
    int64_t next_seq;

    pthread_t thread;
    int thread_started;
    volatile int running;
    volatile int tolerate_errors;
    int stop_reason;
    int last_errno;
    int locked_count;

    pthread_mutex_t lock;
    pthread_cond_t cond;

    _Alignas(8) int64_t stats[ST_COUNT];

    /* small ring of log messages, drained by Java */
    pthread_mutex_t log_lock;
    char log_ring[32][200];
    int log_head;
    int log_count;
} uvc_stream_t;

uvc_stream_t *uvc_stream_create(int fd, const uvc_config_t *cfg);
/* Returns 0 on success or a negative errno. */
int uvc_stream_start(uvc_stream_t *s);
void uvc_stream_stop(uvc_stream_t *s);
/* Frees everything. Returns 0, or 1 if buffers were still locked (memory is leaked on purpose). */
int uvc_stream_destroy(uvc_stream_t *s);

/* Wait for the newest complete frame. Returns buffer index, -1 on timeout, -2 when stopped. */
int uvc_stream_acquire(uvc_stream_t *s, int timeout_ms, int64_t *info /* 6 values */);
void uvc_stream_release(uvc_stream_t *s, int idx);
void uvc_stream_get_stats(uvc_stream_t *s, int64_t *out, int n);
int uvc_stream_drain_log(uvc_stream_t *s, char out[][200], int max);
void uvc_stream_logf(uvc_stream_t *s, const char *fmt, ...);

int uvc_set_interface(int fd, int iface, int alt);
int uvc_clear_halt(int fd, int endpoint);
int uvc_get_speed(int fd);

#endif
