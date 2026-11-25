/*
 * Host unit tests for the native UVC frame assembler (uvc_stream.c).
 * Build & run:  ./run_tests.sh
 * They feed synthetic UVC payloads straight into the parser, so they need
 * no USB hardware. Run under AddressSanitizer and ThreadSanitizer.
 */
#define _GNU_SOURCE
#include <stdlib.h>
#include "../../main/cpp/uvc_stream.c"

#include <assert.h>
#include <stdatomic.h>

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static uvc_config_t base_cfg(int format, int64_t expected) {
    uvc_config_t c;
    memset(&c, 0, sizeof c);
    c.endpoint = 0x81;
    c.xfer_type = UVC_XFER_ISO;
    c.iso_packet_size = 3072;
    c.iso_packets_per_urb = 8;
    c.num_urbs = 4;
    c.format = format;
    c.expected_size = expected;
    c.initial_cap = 65536;
    c.max_cap = 4 * 1024 * 1024;
    c.num_bufs = 4;
    c.strict_mjpeg = 1;
    return c;
}

static uvc_stream_t *make(uvc_config_t c) {
    uvc_stream_t *s = uvc_stream_create(-1, &c);
    assert(s);
    /* pretend the stream is running without a reaper thread */
    s->running = 1;
    s->filling = -1;
    return s;
}

/* Sends `len` bytes of frame data split into payloads of `chunk` bytes. */
static void send_frame(uvc_stream_t *s, int fid, const uint8_t *data, int len, int chunk, int eof, int err_bit) {
    uint8_t pkt[4096];
    int off = 0;
    while (off < len || (len == 0 && off == 0)) {
        int n = len - off < chunk ? len - off : chunk;
        int last = off + n >= len;
        pkt[0] = 12;
        pkt[1] = 0x80 | (fid & 1) | (last && eof ? 0x02 : 0) | (err_bit ? 0x40 : 0) | 0x04;
        memset(pkt + 2, 0, 10);
        memcpy(pkt + 12, data + off, n);
        handle_payload(s, pkt, 12 + n);
        off += n;
        if (len == 0) break;
    }
}

static int acquire_now(uvc_stream_t *s, int64_t *info) {
    return uvc_stream_acquire(s, 0, info);
}

static void test_uncompressed_basic(void) {
    uint8_t frame[4 * 2 * 2];
    for (int i = 0; i < (int) sizeof frame; i++) frame[i] = (uint8_t) i;
    uvc_stream_t *s = make(base_cfg(UVC_FMT_UNCOMPRESSED, sizeof frame));
    send_frame(s, 0, frame, sizeof frame, 5, 1, 0);
    int64_t info[6];
    int idx = acquire_now(s, info);
    CHECK(idx >= 0);
    CHECK(info[0] == (int64_t) sizeof frame);
    CHECK(memcmp(s->bufs[idx].data, frame, sizeof frame) == 0);
    uvc_stream_release(s, idx);
    CHECK(acquire_now(s, info) == -1); /* nothing new */
    s->running = 0;
    CHECK(uvc_stream_destroy(s) == 0);
}

static void test_incomplete_and_padding(void) {
    uint8_t frame[64];
    memset(frame, 7, sizeof frame);
    uvc_stream_t *s = make(base_cfg(UVC_FMT_UNCOMPRESSED, 64));
    send_frame(s, 0, frame, 40, 16, 1, 0); /* too short */
    int64_t info[6];
    CHECK(acquire_now(s, info) == -1);
    CHECK(s->stats[ST_DROP_INCOMPLETE] == 1);
    uint8_t padded[80];
    memset(padded, 9, sizeof padded);
    send_frame(s, 1, padded, 80, 16, 1, 0); /* device padding: trimmed to 64 */
    int idx = acquire_now(s, info);
    CHECK(idx >= 0 && info[0] == 64);
    uvc_stream_release(s, idx);
    s->running = 0;
    uvc_stream_destroy(s);
}

static void test_fid_toggle_without_eof(void) {
    uint8_t a[32], b[32];
    memset(a, 1, sizeof a);
    memset(b, 2, sizeof b);
    uvc_stream_t *s = make(base_cfg(UVC_FMT_UNCOMPRESSED, 32));
    send_frame(s, 0, a, 32, 8, 0, 0); /* no EOF */
    send_frame(s, 1, b, 32, 8, 0, 0); /* FID toggles: frame a completes */
    int64_t info[6];
    int idx = acquire_now(s, info);
    CHECK(idx >= 0);
    CHECK(s->bufs[idx].data[0] == 1);
    uvc_stream_release(s, idx);
    send_frame(s, 0, a, 0, 8, 1, 0); /* header-only EOF payload? FID toggles again -> b completes */
    idx = acquire_now(s, info);
    CHECK(idx >= 0 && s->bufs[idx].data[0] == 2);
    uvc_stream_release(s, idx);
    s->running = 0;
    uvc_stream_destroy(s);
}

static void test_error_bit_and_tolerance(void) {
    uint8_t f[32];
    memset(f, 3, sizeof f);
    uvc_stream_t *s = make(base_cfg(UVC_FMT_UNCOMPRESSED, 32));
    send_frame(s, 0, f, 32, 8, 1, 1);
    int64_t info[6];
    CHECK(acquire_now(s, info) == -1);
    CHECK(s->stats[ST_DROP_ERROR] == 1);
    s->tolerate_errors = 1;
    send_frame(s, 1, f, 32, 8, 1, 1);
    int idx = acquire_now(s, info);
    CHECK(idx >= 0);
    CHECK(info[4] & UVC_FRAME_FLAG_ERROR_TOLERATED);
    uvc_stream_release(s, idx);
    s->running = 0;
    uvc_stream_destroy(s);
}

static void test_mjpeg_validation_and_growth(void) {
    uvc_config_t c = base_cfg(UVC_FMT_MJPEG, 0);
    uvc_stream_t *s = make(c);
    int n = 300000; /* bigger than the 64 KiB initial buffer */
    uint8_t *j = malloc(n);
    memset(j, 0x55, n);
    j[0] = 0xFF; j[1] = 0xD8; j[n - 2] = 0xFF; j[n - 1] = 0xD9;
    send_frame(s, 0, j, n, 3000, 1, 0);
    int64_t info[6];
    int idx = acquire_now(s, info);
    CHECK(idx >= 0 && info[0] == n);
    CHECK(idx >= 0 && memcmp(s->bufs[idx].data, j, n) == 0);
    uvc_stream_release(s, idx);

    j[n - 1] = 0x00; /* no EOI: strict mode drops it */
    send_frame(s, 1, j, n, 3000, 1, 0);
    CHECK(acquire_now(s, info) == -1);
    CHECK(s->stats[ST_DROP_INCOMPLETE] == 1);

    s->cfg.strict_mjpeg = 0; /* lenient: delivered but flagged */
    send_frame(s, 0, j, n, 3000, 1, 0);
    idx = acquire_now(s, info);
    CHECK(idx >= 0 && (info[4] & UVC_FRAME_FLAG_TRUNCATED));
    uvc_stream_release(s, idx);

    j[0] = 0x00; /* not a JPEG */
    send_frame(s, 1, j, 1000, 500, 1, 0);
    CHECK(acquire_now(s, info) == -1);
    CHECK(s->stats[ST_DROP_INVALID] == 1);
    free(j);
    s->running = 0;
    uvc_stream_destroy(s);
}

static void test_overflow(void) {
    uvc_config_t c = base_cfg(UVC_FMT_MJPEG, 0);
    c.max_cap = 100000;
    uvc_stream_t *s = make(c);
    int n = 200000;
    uint8_t *j = calloc(1, n);
    j[0] = 0xFF; j[1] = 0xD8; j[n - 2] = 0xFF; j[n - 1] = 0xD9;
    send_frame(s, 0, j, n, 3000, 1, 0);
    int64_t info[6];
    CHECK(acquire_now(s, info) == -1);
    CHECK(s->stats[ST_DROP_OVERFLOW] == 1);
    /* and the next normal frame still works */
    send_frame(s, 1, j, 1000, 500, 0, 0);
    uint8_t eoi[2] = {0xFF, 0xD9};
    send_frame(s, 1, eoi, 2, 2, 1, 0);
    int idx = acquire_now(s, info);
    CHECK(idx >= 0 && info[0] == 1002);
    uvc_stream_release(s, idx);
    free(j);
    s->running = 0;
    uvc_stream_destroy(s);
}

static void test_latest_wins_and_starvation(void) {
    uint8_t f[16];
    uvc_stream_t *s = make(base_cfg(UVC_FMT_UNCOMPRESSED, 16));
    for (int i = 0; i < 5; i++) {
        memset(f, i, sizeof f);
        send_frame(s, i & 1, f, 16, 16, 1, 0);
    }
    int64_t info[6];
    int idx = acquire_now(s, info);
    CHECK(idx >= 0 && s->bufs[idx].data[0] == 4); /* newest */
    CHECK(s->stats[ST_SKIPPED_BY_CONSUMER] == 4);
    /* lock every buffer the consumer can get, keep producing: must not crash */
    int held[UVC_MAX_BUFS], nh = 0;
    held[nh++] = idx;
    for (int k = 0; k < 10; k++) {
        memset(f, 10 + k, sizeof f);
        send_frame(s, k & 1, f, 16, 16, 1, 0);
        int j = acquire_now(s, info);
        if (j >= 0) held[nh++] = j;
    }
    CHECK(nh <= s->cfg.num_bufs);
    CHECK(s->stats[ST_DROP_NO_BUFFER] > 0);
    /* destroying while frames are held must not free them */
    for (int k = 0; k < nh; k++) uvc_stream_release(s, held[k]);
    memset(f, 99, sizeof f);
    send_frame(s, 0, f, 16, 16, 1, 0);
    send_frame(s, 1, f, 16, 16, 1, 0);
    idx = acquire_now(s, info);
    CHECK(idx >= 0 && s->bufs[idx].data[0] == 99);
    s->running = 0;
    CHECK(uvc_stream_destroy(s) == 1); /* one frame still locked: memory kept */
    uvc_stream_release(s, idx);        /* a late release is still safe */
    CHECK(uvc_stream_destroy(s) == 0); /* and now everything is freed */
}

static void test_bad_headers(void) {
    uvc_stream_t *s = make(base_cfg(UVC_FMT_UNCOMPRESSED, 4));
    uint8_t p1[] = {50, 0x82, 1, 2};      /* header longer than packet */
    uint8_t p2[] = {1, 0x82};             /* header too short */
    uint8_t p3[] = {2};                   /* runt */
    handle_payload(s, p1, sizeof p1);
    handle_payload(s, p2, sizeof p2);
    handle_payload(s, p3, sizeof p3);
    CHECK(s->stats[ST_BAD_HEADERS] == 2);
    int64_t info[6];
    CHECK(acquire_now(s, info) == -1);
    s->running = 0;
    uvc_stream_destroy(s);
}

/* Builds a fake reaped isochronous URB and runs it through handle_urb. */
static void test_iso_urb_parsing(void) {
    uvc_config_t c = base_cfg(UVC_FMT_UNCOMPRESSED, 24);
    c.iso_packet_size = 64;
    c.iso_packets_per_urb = 4;
    uvc_stream_t *s = make(c);
    size_t sz = sizeof(struct usbdevfs_urb) + 4 * sizeof(struct usbdevfs_iso_packet_desc);
    struct usbdevfs_urb *u = calloc(1, sz);
    uint8_t *buf = calloc(1, 4 * 64);
    s->urbs[0] = u;
    s->urb_bufs[0] = buf;
    s->urb_len[0] = 256;
    s->urb_count = 1;
    u->number_of_packets = 4;
    /* packet 0: 12 data bytes; packet 1: empty; packet 2: 12 bytes + EOF; packet 3: error */
    for (int p = 0; p < 4; p++) u->iso_frame_desc[p].length = 64;
    buf[0] = 2; buf[1] = 0x80; memset(buf + 2, 0xAA, 12);
    u->iso_frame_desc[0].actual_length = 14;
    u->iso_frame_desc[1].actual_length = 0;
    buf[128] = 2; buf[129] = 0x82; memset(buf + 130, 0xBB, 12);
    u->iso_frame_desc[2].actual_length = 14;
    u->iso_frame_desc[3].actual_length = 0;
    u->iso_frame_desc[3].status = (unsigned) -EXDEV;
    CHECK(handle_urb(s, u, 0) == 0);
    int64_t info[6];
    int idx = acquire_now(s, info);
    CHECK(idx >= 0 && info[0] == 24);
    CHECK(idx >= 0 && s->bufs[idx].data[0] == 0xAA && s->bufs[idx].data[23] == 0xBB);
    uvc_stream_release(s, idx);
    CHECK(s->stats[ST_ISO_PACKET_ERRORS] == 1);
    u->status = -ENODEV;
    CHECK(handle_urb(s, u, 0) == -1);
    s->urb_count = 0;
    free(u);
    free(buf);
    s->running = 0;
    uvc_stream_destroy(s);
}

/* ---- concurrency stress: one producer, several consumers ---- */

#define STRESS_SIZE 4096
static atomic_int stop_flag;
static atomic_long consumed;

static void *producer(void *arg) {
    uvc_stream_t *s = arg;
    uint8_t f[STRESS_SIZE];
    int fid = 0;
    unsigned seq = 0;
    while (!atomic_load(&stop_flag)) {
        memset(f, (uint8_t) seq, sizeof f);
        f[0] = (uint8_t) seq;
        send_frame(s, fid, f, STRESS_SIZE, 1000, 1, 0);
        fid ^= 1;
        seq++;
    }
    return NULL;
}

static void *consumer(void *arg) {
    uvc_stream_t *s = arg;
    int64_t info[6];
    int64_t last = -1;
    while (!atomic_load(&stop_flag)) {
        int idx = uvc_stream_acquire(s, 20, info);
        if (idx < 0) continue;
        uint8_t v = s->bufs[idx].data[0];
        for (int i = 1; i < STRESS_SIZE; i++) {
            if (s->bufs[idx].data[i] != v) {
                fprintf(stderr, "FAIL: torn frame (byte %d)\n", i);
                failures++;
                break;
            }
        }
        if (info[1] <= last) {
            fprintf(stderr, "FAIL: sequence went backwards\n");
            failures++;
        }
        last = info[1];
        if (rand() % 7 == 0) usleep(1000);
        uvc_stream_release(s, idx);
        atomic_fetch_add(&consumed, 1);
    }
    return NULL;
}

static void test_stress(void) {
    uvc_config_t c = base_cfg(UVC_FMT_UNCOMPRESSED, STRESS_SIZE);
    c.num_bufs = 6;
    uvc_stream_t *s = make(c);
    pthread_t p, cs[3];
    atomic_store(&stop_flag, 0);
    pthread_create(&p, NULL, producer, s);
    for (int i = 0; i < 3; i++) pthread_create(&cs[i], NULL, consumer, s);
    usleep(1500 * 1000);
    atomic_store(&stop_flag, 1);
    pthread_join(p, NULL);
    for (int i = 0; i < 3; i++) pthread_join(cs[i], NULL);
    fprintf(stderr, "  stress: %lld frames published, %ld consumed, %lld skipped, %lld dropped (no buffer)\n",
            (long long) s->stats[ST_FRAMES_PUBLISHED], atomic_load(&consumed),
            (long long) s->stats[ST_SKIPPED_BY_CONSUMER], (long long) s->stats[ST_DROP_NO_BUFFER]);
    CHECK(atomic_load(&consumed) > 100);
    CHECK(s->locked_count == 0);
    s->running = 0;
    CHECK(uvc_stream_destroy(s) == 0);
}

int main(void) {
    test_uncompressed_basic();
    test_incomplete_and_padding();
    test_fid_toggle_without_eof();
    test_error_bit_and_tolerance();
    test_mjpeg_validation_and_growth();
    test_overflow();
    test_latest_wins_and_starvation();
    test_bad_headers();
    test_iso_urb_parsing();
    test_stress();
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    fprintf(stderr, "All native tests passed\n");
    return 0;
}
