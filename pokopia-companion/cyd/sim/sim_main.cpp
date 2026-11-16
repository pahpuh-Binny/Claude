// PC simulator for the CYD firmware's UI.
//
// Runs the same LVGL UI in a headless 320x240 framebuffer and follows a
// script of taps, drags and screenshots read from stdin:
//
//   tap X Y            press and release at a point
//   drag X1 Y1 X2 Y2   press, move and release (scrolls lists)
//   wait MS            let time pass
//   shot FILE.ppm      save the screen
//
// Usage: sim DATA_DIR < script.txt
//   DATA_DIR stands in for the device's flash (save.json, settings.json).
//   The SD card is the folder given by SIM_SD_PATH at build time.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include <fstream>
#include <sstream>
#include <string>

#include "lvgl.h"
#include "src/core/lv_obj_private.h"
#include "src/core/lv_obj_class_private.h"
#include "../src/app/platform.h"
#include "../src/app/save.h"
#include "../src/app/ui.h"

static const int W = 320, H = 240;
static uint16_t framebuffer[W * H];
static uint32_t now_ms = 0;
static int32_t touch_x = 0, touch_y = 0;
static bool touch_down = false;
static std::string data_dir = ".";

// ---------------- platform ----------------

namespace platform {

static bool read_path(const std::string& path, std::string& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::stringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return true;
}

static bool write_path(const std::string& path, const std::string& data) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f << data;
    return (bool)f;
}

bool read_file(const char* path, std::string& out) { return read_path(data_dir + path, out); }
bool write_file(const char* path, const std::string& data) { return write_path(data_dir + path, data); }

bool sd_available() {
    struct stat st;
    return stat(SIM_SD_PATH, &st) == 0;
}
bool sd_has_sprites() {
    struct stat st;
    return stat(SIM_SD_PATH "/pokopia/sprites/0001.bin", &st) == 0;
}
bool sd_read(const char* path, std::string& out) { return read_path(std::string(SIM_SD_PATH) + path, out); }
bool sd_write(const char* path, const std::string& data) {
    mkdir(SIM_SD_PATH "/pokopia", 0755);
    return write_path(std::string(SIM_SD_PATH) + path, data);
}

void set_backlight(uint8_t percent) { printf("backlight %u%%\n", percent); }
void set_color_inversion(bool on) { printf("invert %d\n", on); }
void led_flash(uint8_t r, uint8_t g, uint8_t b, uint16_t ms) { printf("led %u,%u,%u for %ums\n", r, g, b, ms); }

bool touch_raw(int16_t*, int16_t*) { return false; }
static int16_t cal_values[4] = {200, 3700, 240, 3800};
void set_touch_calibration(const int16_t cal[4]) { memcpy(cal_values, cal, sizeof cal_values); }
void get_touch_calibration(int16_t cal[4]) { memcpy(cal, cal_values, sizeof cal_values); }

const char* firmware_version() { return "sim"; }
uint32_t free_heap() { return 180 * 1024; }

}  // namespace platform

// ---------------- LVGL glue ----------------

static void flush_cb(lv_display_t* disp, const lv_area_t* area, uint8_t* px_map) {
    const uint16_t* src = (const uint16_t*)px_map;
    for (int32_t y = area->y1; y <= area->y2; y++)
        for (int32_t x = area->x1; x <= area->x2; x++) framebuffer[y * W + x] = *src++;
    lv_display_flush_ready(disp);
}

static void read_cb(lv_indev_t*, lv_indev_data_t* data) {
    data->point.x = touch_x;
    data->point.y = touch_y;
    data->state = touch_down ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
}

static uint32_t tick_cb() { return now_ms; }

// "dump": print the widget tree of the active screen (debugging aid).
static void dump(lv_obj_t* o, int depth) {
    lv_area_t a;
    lv_obj_get_coords(o, &a);
    const char* txt = lv_obj_check_type(o, &lv_label_class) ? lv_label_get_text(o) : "";
    printf("%*s%s (%d,%d)-(%d,%d)%s%s%s\n", depth * 2, "", o->class_p->name ? o->class_p->name : "obj",
           (int)a.x1, (int)a.y1, (int)a.x2, (int)a.y2, *txt ? " \"" : "", txt, *txt ? "\"" : "");
    for (uint32_t i = 0; i < lv_obj_get_child_count(o); i++) dump(lv_obj_get_child(o, i), depth + 1);
}

static void run(uint32_t ms) {
    uint32_t end = now_ms + ms;
    while (now_ms < end) {
        now_ms += 5;
        lv_timer_handler();
    }
}

static void screenshot(const char* path) {
    lv_refr_now(nullptr);
    FILE* f = fopen(path, "wb");
    if (!f) { perror(path); return; }
    fprintf(f, "P6\n%d %d\n255\n", W, H);
    for (int i = 0; i < W * H; i++) {
        uint16_t c = framebuffer[i];
        uint8_t rgb[3] = {(uint8_t)(((c >> 11) & 0x1F) * 255 / 31), (uint8_t)(((c >> 5) & 0x3F) * 255 / 63),
                          (uint8_t)((c & 0x1F) * 255 / 31)};
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
    printf("shot %s\n", path);
}

int main(int argc, char** argv) {
    if (argc > 1) data_dir = argv[1];

    lv_init();
    lv_tick_set_cb(tick_cb);
    lv_display_t* disp = lv_display_create(W, H);
    static uint16_t buf[W * 40];
    lv_display_set_buffers(disp, buf, nullptr, sizeof buf, LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(disp, flush_cb);
    lv_indev_t* indev = lv_indev_create();
    lv_indev_set_type(indev, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(indev, read_cb);

    save::load_settings();
    save::load();
    ui::start();
    run(200);

    char line[256];
    while (fgets(line, sizeof line, stdin)) {
        char cmd[16], arg[200];
        int a, b, c, d;
        if (sscanf(line, "tap %d %d", &a, &b) == 2) {
            touch_x = a; touch_y = b; touch_down = true;
            run(60);
            touch_down = false;
            run(250);
        } else if (sscanf(line, "drag %d %d %d %d", &a, &b, &c, &d) == 4) {
            touch_x = a; touch_y = b; touch_down = true;
            run(40);
            for (int s = 1; s <= 10; s++) {
                touch_x = a + (c - a) * s / 10;
                touch_y = b + (d - b) * s / 10;
                run(20);
            }
            touch_down = false;
            run(600);
        } else if (sscanf(line, "wait %d", &a) == 1) {
            run(a);
        } else if (!strncmp(line, "dump", 4)) {
            dump(lv_screen_active(), 0);
        } else if (sscanf(line, "%15s %199s", cmd, arg) == 2 && !strcmp(cmd, "shot")) {
            screenshot(arg);
        }
    }
    save::write_now();
    return 0;
}
