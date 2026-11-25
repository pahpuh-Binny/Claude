#include "display.h"

#include "pins.h"
#include "settings.h"

TFT_eSPI tft;

namespace disp {

static const int BL_CHANNEL = 0;
static uint8_t blLevel = 0;

void begin() {
    for (int p : {pins::LED_R, pins::LED_G, pins::LED_B}) {
        pinMode(p, OUTPUT);
        digitalWrite(p, HIGH);
    }
    ledcSetup(BL_CHANNEL, 5000, 8);
    ledcAttachPin(pins::BACKLIGHT, BL_CHANNEL);
    ledcWrite(BL_CHANNEL, 0);
    tft.init();
    tft.setRotation(1);
    applyColorSettings();
    tft.fillScreen(TFT_BLACK);
}

void applyColorSettings() {
#ifdef TFT_INVERSION_ON
    tft.invertDisplay(!cfg.invert);  // this panel is inverted by default
#else
    tft.invertDisplay(cfg.invert);
#endif
}

void backlight(uint8_t percent) {
    if (percent > 100) percent = 100;
    blLevel = percent;
    // Perceived brightness is roughly quadratic in PWM duty.
    uint32_t duty = (uint32_t)percent * percent * 255 / 10000;
    if (percent > 0 && duty == 0) duty = 1;
    ledcWrite(BL_CHANNEL, duty);
}

uint8_t backlightLevel() { return blLevel; }

void fadeTo(uint8_t percent, uint16_t ms) {
    int from = blLevel;
    const int steps = 12;
    for (int i = 1; i <= steps; i++) {
        backlight((uint8_t)(from + (percent - from) * i / steps));
        delay(ms / steps);
    }
}

void led(bool r, bool g, bool b) {
    digitalWrite(pins::LED_R, r ? LOW : HIGH);
    digitalWrite(pins::LED_G, g ? LOW : HIGH);
    digitalWrite(pins::LED_B, b ? LOW : HIGH);
}

bool swapRB() { return cfg.swapRB; }

uint16_t fix(uint16_t c) {
    if (!cfg.swapRB) return c;
    return (uint16_t)((c << 11) | (c & 0x07E0) | (c >> 11));
}

uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) { return fix(tft.color565(r, g, b)); }

}  // namespace disp

// ---------------- XPT2046 touch, bit-banged ----------------

namespace touch {

static uint16_t xptCommand(uint8_t command) {
    for (int i = 7; i >= 0; i--) {
        digitalWrite(pins::TOUCH_MOSI, (command >> i) & 1);
        digitalWrite(pins::TOUCH_CLK, HIGH);
        delayMicroseconds(1);
        digitalWrite(pins::TOUCH_CLK, LOW);
        delayMicroseconds(1);
    }
    uint16_t result = 0;
    for (int i = 11; i >= 0; i--) {
        digitalWrite(pins::TOUCH_CLK, HIGH);
        delayMicroseconds(1);
        digitalWrite(pins::TOUCH_CLK, LOW);
        delayMicroseconds(1);
        result |= (uint16_t)digitalRead(pins::TOUCH_MISO) << i;
    }
    return result;
}

void begin() {
    pinMode(pins::TOUCH_CLK, OUTPUT);
    pinMode(pins::TOUCH_MOSI, OUTPUT);
    pinMode(pins::TOUCH_CS, OUTPUT);
    pinMode(pins::TOUCH_MISO, INPUT);
    digitalWrite(pins::TOUCH_CS, HIGH);
    digitalWrite(pins::TOUCH_CLK, LOW);
}

bool raw(int16_t& x, int16_t& y) {
    digitalWrite(pins::TOUCH_CS, LOW);
    int z1 = xptCommand(0xB0);
    int z2 = xptCommand(0xC0);
    int z = z1 + 4095 - z2;
    bool pressed = z >= 400;
    if (pressed) {
        xptCommand(0x90);  // the first reading after a switch is noisy
        int y1 = xptCommand(0x90);
        int x1 = xptCommand(0xD0);
        int y2 = xptCommand(0x90);
        int x2 = xptCommand(0xD0);
        x = (int16_t)((x1 + x2) / 2);
        y = (int16_t)((y1 + y2) / 2);
        pressed = abs(x1 - x2) < 150 && abs(y1 - y2) < 150;  // drop unstable samples
    }
    xptCommand(0x90);  // power down, keep the pen interrupt enabled
    digitalWrite(pins::TOUCH_CS, HIGH);
    return pressed;
}

static int16_t mapAxis(int32_t raw, int32_t lo, int32_t hi, int32_t size) {
    if (hi == lo) return 0;
    int32_t v = (raw - lo) * size / (hi - lo);
    return (int16_t)(v < 0 ? 0 : v >= size ? size - 1 : v);
}

bool read(int16_t& x, int16_t& y) {
    int16_t rx, ry;
    if (!raw(rx, ry)) return false;
    x = mapAxis(rx, cfg.cal[0], cfg.cal[1], SCREEN_W);
    y = mapAxis(ry, cfg.cal[2], cfg.cal[3], SCREEN_H);
    return true;
}

bool down() {
    int16_t x, y;
    return raw(x, y);
}

static bool isDown = false, holdSent = false;
static uint32_t downAt = 0, lastSeen = 0, lastPoll = 0;
static int16_t startX = 0, startY = 0;

Event poll() {
    Event e;
    uint32_t now = millis();
    if (now - lastPoll < 15) return e;
    lastPoll = now;
    int16_t x, y;
    if (read(x, y)) {
        lastSeen = now;
        if (!isDown) {
            isDown = true;
            holdSent = false;
            downAt = now;
            startX = x;
            startY = y;
        } else if (!holdSent && now - downAt > 1200) {
            holdSent = true;
            e.type = Event::HOLD;
            e.x = startX;
            e.y = startY;
        }
        return e;
    }
    // Resistive panels drop out for a moment mid-press, so wait before calling it a release.
    if (isDown && now - lastSeen > 60) {
        isDown = false;
        if (!holdSent) {
            e.type = Event::TAP;
            e.x = startX;
            e.y = startY;
        }
    }
    return e;
}

void waitRelease(uint32_t maxMs) {
    uint32_t start = millis(), lastDown = millis();
    while (millis() - start < maxMs) {
        if (down()) lastDown = millis();
        else if (millis() - lastDown > 80) break;
        delay(10);
    }
    flush();
}

void flush() {
    isDown = false;
    holdSent = true;
}

}  // namespace touch
