// display.h — TFT_eSPI rendering for the CYD (320x240 landscape).
#pragma once

#include <stdint.h>
#include "modes.h"

namespace display {
  void begin();

  // Redraw the static chrome for a mode (title, labels, clears plot area).
  void setMode(Mode m);

  // Refresh the status strip (call a few times per second). `extra` is a short
  // free-text line (SSID, LoRa config, last message, …) or nullptr.
  void status(Mode m, const Stats& s, uint8_t channel, uint16_t rate, const char* extra);

  // Radar mode: push one amplitude column (n subcarriers) into the waterfall
  // and update the motion bar from `s`.
  void radarColumn(const uint8_t* amp, int n, const Stats& s);

  // Simple one-line log at the bottom (any mode).
  void note(const char* line);
}
