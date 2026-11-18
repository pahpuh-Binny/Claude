// Pokopia Companion firmware for the ESP32-2432S028R "Cheap Yellow Display".
//
// Hardware glue: display (TFT_eSPI), resistive touch (XPT2046, bit-banged so
// the SD card can keep the VSPI bus), backlight, RGB LED, LittleFS for saves
// and the microSD card for the optional picture pack. The UI itself lives in
// src/app and also runs in the PC simulator (sim/).

#include <Arduino.h>
#include <LittleFS.h>
#include <SD.h>
#include <SPI.h>
#include <TFT_eSPI.h>
#include <lvgl.h>

#include "app/platform.h"
#include "app/save.h"
#include "app/ui.h"

// ---------------- CYD pins ----------------
static const int PIN_BACKLIGHT = 21;
static const int PIN_BOOT_BUTTON = 0;  // the BOOT button, low when pressed
static const int PIN_LED_R = 4, PIN_LED_G = 16, PIN_LED_B = 17;  // active low
static const int XPT_CLK = 25, XPT_MOSI = 32, XPT_MISO = 39, XPT_CS = 33;
static const int SD_SCK = 18, SD_MISO = 19, SD_MOSI = 23, SD_CS = 5;

// LVGL 9's renderer is deep; give the Arduino loop task more than the default 8 KB.
SET_LOOP_TASK_STACK_SIZE(16 * 1024);

static const int SCREEN_W = 320, SCREEN_H = 240;
static const int BACKLIGHT_CHANNEL = 0;

static TFT_eSPI tft;
static bool sd_ok = false;
static bool sd_sprites = false;
static int16_t touch_cal[4] = {200, 3700, 240, 3800};
static uint32_t led_off_at = 0;
static bool swap_red_blue = false;

// ---------------- XPT2046 touch (bit-banged SPI) ----------------

static uint16_t xpt_command(uint8_t command) {
    for (int i = 7; i >= 0; i--) {
        digitalWrite(XPT_MOSI, (command >> i) & 1);
        digitalWrite(XPT_CLK, HIGH);
        delayMicroseconds(1);
        digitalWrite(XPT_CLK, LOW);
        delayMicroseconds(1);
    }
    uint16_t result = 0;
    for (int i = 11; i >= 0; i--) {
        digitalWrite(XPT_CLK, HIGH);
        delayMicroseconds(1);
        digitalWrite(XPT_CLK, LOW);
        delayMicroseconds(1);
        result |= (uint16_t)digitalRead(XPT_MISO) << i;
    }
    return result;
}

// Reads one raw touch point. Returns false when the screen is not pressed.
static bool xpt_read(int16_t* x, int16_t* y) {
    digitalWrite(XPT_CS, LOW);
    int z1 = xpt_command(0xB0);
    int z2 = xpt_command(0xC0);
    int z = z1 + 4095 - z2;
    bool pressed = z >= 400;
    if (pressed) {
        xpt_command(0x90);  // the first reading after a switch is noisy
        int y1 = xpt_command(0x90);
        int x1 = xpt_command(0xD0);
        int y2 = xpt_command(0x90);
        int x2 = xpt_command(0xD0);
        *x = (int16_t)((x1 + x2) / 2);
        *y = (int16_t)((y1 + y2) / 2);
        pressed = abs(x1 - x2) < 150 && abs(y1 - y2) < 150;  // drop unstable samples
    }
    xpt_command(0x90);  // power down, keep the pen interrupt enabled
    digitalWrite(XPT_CS, HIGH);
    return pressed;
}

static int32_t map_axis(int32_t raw, int32_t raw_lo, int32_t raw_hi, int32_t size) {
    if (raw_hi == raw_lo) return 0;
    int32_t v = (raw - raw_lo) * size / (raw_hi - raw_lo);
    return v < 0 ? 0 : v >= size ? size - 1 : v;
}

// ---------------- platform interface for the UI ----------------

namespace platform {

bool read_file(const char* path, std::string& out) {
    File f = LittleFS.open(path, "r");
    if (!f) return false;
    out.resize(f.size());
    size_t n = f.read((uint8_t*)&out[0], out.size());
    f.close();
    return n == out.size();
}

bool write_file(const char* path, const std::string& data) {
    // Write to a temporary file first so a power cut never leaves half a save.
    std::string tmp = std::string(path) + ".tmp";
    File f = LittleFS.open(tmp.c_str(), "w");
    if (!f) return false;
    size_t n = f.write((const uint8_t*)data.data(), data.size());
    f.close();
    if (n != data.size()) return false;
    LittleFS.remove(path);
    return LittleFS.rename(tmp.c_str(), path);
}

bool sd_available() { return sd_ok; }
bool sd_has_sprites() { return sd_sprites; }

bool sd_read(const char* path, std::string& out) {
    if (!sd_ok) return false;
    File f = SD.open(path, FILE_READ);
    if (!f) return false;
    out.resize(f.size());
    size_t n = f.read((uint8_t*)&out[0], out.size());
    f.close();
    return n == out.size();
}

bool sd_write(const char* path, const std::string& data) {
    if (!sd_ok) return false;
    SD.mkdir("/pokopia");
    File f = SD.open(path, FILE_WRITE);
    if (!f) return false;
    size_t n = f.write((const uint8_t*)data.data(), data.size());
    f.close();
    return n == data.size();
}

void set_backlight(uint8_t percent) {
    if (percent < 5) percent = 5;
    if (percent > 100) percent = 100;
    ledcWrite(BACKLIGHT_CHANNEL, (uint32_t)percent * 255 / 100);
}

void set_color_inversion(bool on) {
#ifdef TFT_INVERSION_ON
    tft.invertDisplay(!on);  // this panel is inverted by default
#else
    tft.invertDisplay(on);
#endif
}

void set_color_swap(bool on) {
    swap_red_blue = on;
    lv_obj_t* scr = lv_screen_active();
    if (scr) lv_obj_invalidate(scr);  // redraw everything with the new order
}

bool boot_button() { return digitalRead(PIN_BOOT_BUTTON) == LOW; }

void led_flash(uint8_t r, uint8_t g, uint8_t b, uint16_t ms) {
    digitalWrite(PIN_LED_R, r ? LOW : HIGH);
    digitalWrite(PIN_LED_G, g ? LOW : HIGH);
    digitalWrite(PIN_LED_B, b ? LOW : HIGH);
    led_off_at = millis() + ms;
}

bool touch_raw(int16_t* x, int16_t* y) { return xpt_read(x, y); }

void set_touch_calibration(const int16_t cal[4]) { memcpy(touch_cal, cal, sizeof touch_cal); }
void get_touch_calibration(int16_t cal[4]) { memcpy(cal, touch_cal, sizeof touch_cal); }

const char* firmware_version() { return FW_VERSION; }
uint32_t free_heap() { return ESP.getFreeHeap(); }

}  // namespace platform

// ---------------- LVGL glue ----------------

static void flush_cb(lv_display_t* disp, const lv_area_t* area, uint8_t* px_map) {
    uint32_t w = lv_area_get_width(area);
    uint32_t h = lv_area_get_height(area);
    if (swap_red_blue) {
        // RGB565: move the 5 red bits and 5 blue bits past each other.
        uint16_t* px = (uint16_t*)px_map;
        for (uint32_t i = 0, n = w * h; i < n; i++) {
            uint16_t c = px[i];
            px[i] = (uint16_t)((c << 11) | (c & 0x07E0) | (c >> 11));
        }
    }
    tft.startWrite();
    tft.setAddrWindow(area->x1, area->y1, w, h);
    tft.pushColors((uint16_t*)px_map, w * h, true);
    tft.endWrite();
    lv_display_flush_ready(disp);
}

static void touch_cb(lv_indev_t*, lv_indev_data_t* data) {
    int16_t x, y;
    if (xpt_read(&x, &y)) {
        data->point.x = map_axis(x, touch_cal[0], touch_cal[1], SCREEN_W);
        data->point.y = map_axis(y, touch_cal[2], touch_cal[3], SCREEN_H);
        data->state = LV_INDEV_STATE_PRESSED;
    } else {
        data->state = LV_INDEV_STATE_RELEASED;
    }
}

static uint32_t tick_cb() { return millis(); }

void setup() {
    Serial.begin(115200);

    for (int pin : {PIN_LED_R, PIN_LED_G, PIN_LED_B}) {
        pinMode(pin, OUTPUT);
        digitalWrite(pin, HIGH);
    }
    pinMode(PIN_BOOT_BUTTON, INPUT_PULLUP);
    pinMode(XPT_CLK, OUTPUT);
    pinMode(XPT_MOSI, OUTPUT);
    pinMode(XPT_CS, OUTPUT);
    pinMode(XPT_MISO, INPUT);
    digitalWrite(XPT_CS, HIGH);
    digitalWrite(XPT_CLK, LOW);

    if (!LittleFS.begin(true)) Serial.println("LittleFS failed; progress will not be saved");
    save::load_settings();
    save::Settings& s = save::settings();
    platform::set_touch_calibration(s.cal);

    tft.init();
    tft.setRotation(1);
    tft.fillScreen(TFT_BLACK);
    platform::set_color_inversion(s.invert);
    swap_red_blue = s.swap_rb;
    ledcSetup(BACKLIGHT_CHANNEL, 5000, 8);
    ledcAttachPin(PIN_BACKLIGHT, BACKLIGHT_CHANNEL);
    platform::set_backlight(s.brightness);

    SPI.begin(SD_SCK, SD_MISO, SD_MOSI, SD_CS);
    sd_ok = SD.begin(SD_CS, SPI, 16000000) || SD.begin(SD_CS, SPI, 4000000);  // slower retry for picky cards
    sd_sprites = sd_ok && SD.exists("/pokopia/sprites/0001.bin");
    Serial.printf("SD card: %s\n", !sd_ok ? "none" : sd_sprites ? "picture pack found" : "no picture pack");

    lv_init();
    lv_tick_set_cb(tick_cb);
    lv_display_t* disp = lv_display_create(SCREEN_W, SCREEN_H);
    static const size_t BUF_PIXELS = SCREEN_W * 24;
    void* buf1 = heap_caps_malloc(BUF_PIXELS * 2, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    void* buf2 = heap_caps_malloc(BUF_PIXELS * 2, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    lv_display_set_buffers(disp, buf1, buf2, BUF_PIXELS * 2, LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(disp, flush_cb);
    lv_indev_t* touch = lv_indev_create();
    lv_indev_set_type(touch, LV_INDEV_TYPE_POINTER);
    lv_indev_set_read_cb(touch, touch_cb);

    save::load();
    ui::start();
    Serial.printf("Pokopia Companion %s ready, %u KB free\n", FW_VERSION, ESP.getFreeHeap() / 1024);
}

void loop() {
    lv_timer_handler();
    if (led_off_at && (int32_t)(millis() - led_off_at) >= 0) {
        led_off_at = 0;
        for (int pin : {PIN_LED_R, PIN_LED_G, PIN_LED_B}) digitalWrite(pin, HIGH);
    }
    delay(5);
}
