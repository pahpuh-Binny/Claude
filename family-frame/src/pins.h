#pragma once

// ESP32-2432S028R "Cheap Yellow Display" pins.
namespace pins {

constexpr int BACKLIGHT = 21;
constexpr int BOOT_BUTTON = 0;                   // low while pressed
constexpr int LED_R = 4, LED_G = 16, LED_B = 17;  // RGB LED on the back, active low

// XPT2046 resistive touch. Read in software so the SD card keeps the VSPI bus.
constexpr int TOUCH_CLK = 25, TOUCH_MOSI = 32, TOUCH_MISO = 39, TOUCH_CS = 33;

// microSD card slot (VSPI)
constexpr int SD_SCK = 18, SD_MISO = 19, SD_MOSI = 23, SD_CS = 5;

// The 2-pin "SPEAK" connector is driven by an on-board amplifier fed from
// GPIO 26, which is the ESP32's second built-in DAC channel.
constexpr int SPEAKER_DAC = 26;

// Optional INMP441 I2S microphone, wired to the CN1 and P3 expansion connectors
// (see docs/WIRING.md). GPIO 35 is input-only, which suits the mic's data line.
constexpr int MIC_SCK = 22;  // INMP441 SCK (bit clock)
constexpr int MIC_WS = 27;   // INMP441 WS (word select / L-R clock)
constexpr int MIC_SD = 35;   // INMP441 SD (data out of the mic)

}  // namespace pins
