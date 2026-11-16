// What the UI needs from the hardware. Implemented by src/main.cpp on the
// CYD and by sim/sim_main.cpp in the PC simulator.
#pragma once
#include <stdint.h>
#include <string>

namespace platform {

// Internal flash storage (LittleFS on the CYD).
bool read_file(const char* path, std::string& out);
bool write_file(const char* path, const std::string& data);

// microSD card. Sprites are read by LVGL through drive "S:".
bool sd_available();
bool sd_has_sprites();
bool sd_read(const char* path, std::string& out);
bool sd_write(const char* path, const std::string& data);

// Hardware extras.
void set_backlight(uint8_t percent);
void set_color_inversion(bool on);
void led_flash(uint8_t r, uint8_t g, uint8_t b, uint16_t ms);

// Touch calibration: raw touch reading (true while pressed) and the mapping
// from raw readings to screen pixels {raw x at left, raw x at right,
// raw y at top, raw y at bottom}.
bool touch_raw(int16_t* x, int16_t* y);
void set_touch_calibration(const int16_t cal[4]);
void get_touch_calibration(int16_t cal[4]);

const char* firmware_version();
uint32_t free_heap();

}  // namespace platform
