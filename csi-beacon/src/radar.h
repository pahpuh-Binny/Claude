// radar.h — CSI receiver and motion metric.
//
// Puts the ESP32 into promiscuous mode with CSI capture enabled, computes a
// per-subcarrier amplitude column for every captured frame, and derives a 0..1
// "motion" metric from how much the channel amplitudes jitter frame-to-frame.
// A still room gives a low, steady metric; a person moving through the WiFi
// path makes it jump. The display module draws the amplitude waterfall.
#pragma once

#include <stdint.h>
#include "modes.h"

namespace radar {
  static const int MAX_SUB = 64;   // subcarriers we keep per column (HT20 LLTF)

  void begin(uint8_t channel, Stats* stats);
  void stop();
  void loop();

  void setChannel(uint8_t channel);
  uint8_t channel();

  // If set non-null to a 6-byte MAC, only frames from that source contribute.
  // Pass nullptr to accept every frame (ambient sensing).
  void setFilterMac(const uint8_t* mac6);

  // Copy the most recent amplitude column (0..255 per subcarrier) into `out`.
  // Returns the number of valid subcarriers, or 0 if no new column since the
  // last call.
  int takeColumn(uint8_t* out, int maxOut);
}
