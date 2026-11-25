#pragma once
// Screen, backlight, RGB LED and touch for the CYD.

#include <Arduino.h>
#include <TFT_eSPI.h>

extern TFT_eSPI tft;

constexpr int SCREEN_W = 320;
constexpr int SCREEN_H = 240;

namespace disp {

void begin();
void applyColorSettings();             // invert / red-blue swap from cfg
void backlight(uint8_t percent);       // 0 turns the screen off
uint8_t backlightLevel();
void fadeTo(uint8_t percent, uint16_t ms);
void led(bool r, bool g, bool b);

// Colors pass through here so a board with red and blue swapped still looks right.
uint16_t rgb(uint8_t r, uint8_t g, uint8_t b);
uint16_t fix(uint16_t rgb565);
bool swapRB();

}  // namespace disp

namespace touch {

void begin();
bool raw(int16_t& x, int16_t& y);    // raw ADC values; false when not touched
bool read(int16_t& x, int16_t& y);   // screen coordinates
bool down();

struct Event {
    enum Type : uint8_t { NONE, TAP, HOLD } type = NONE;
    int16_t x = 0, y = 0;
};

// Call often. Reports a TAP when a short press ends and HOLD after ~1.2 s.
Event poll();
void waitRelease(uint32_t maxMs = 3000);
void flush();  // forget any press in progress

}  // namespace touch
