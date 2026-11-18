// beacon.h — CSI beacon transmitter.
//
// Brings up a SoftAP (which emits ~10 management beacons/second by itself) and,
// on top of that, injects recognizable 802.11 action frames at a configurable
// rate. A separate CSI receiver locks onto these frames and measures how the
// 2.4 GHz channel changes — the basis of WiFi motion/presence sensing.
//
// This module only *transmits* frames. It does not deauthenticate anyone,
// capture credentials, or probe other networks.
#pragma once

#include <stdint.h>

namespace beacon {
  // Start the SoftAP on `channel` and begin injecting at `rateHz` (0 = beacons
  // only, no injection). `ssid`/`pass` configure the AP; pass empty `pass` for
  // an open AP.
  void begin(uint8_t channel, uint16_t rateHz, const char* ssid, const char* pass);

  // Stop injecting and tear the AP down.
  void stop();

  // Call often from loop(); paces the injected frames. Returns the number of
  // frames sent since the previous call.
  uint32_t loop();

  // Change the injection rate on the fly.
  void setRate(uint16_t rateHz);

  // Change channel on the fly (1..13).
  void setChannel(uint8_t channel);

  uint8_t  channel();
  uint16_t rate();
}
