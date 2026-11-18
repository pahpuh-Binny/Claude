// lora_link.h — optional LoRa text link for an attached SX127x module.
//
// The CYD has NO onboard LoRa radio, so this whole module is compiled only when
// the firmware is built with -DENABLE_LORA=1 and you have wired an SX1276/78
// module to the pins in config.h (see README "Wiring an SX127x").
//
// It is a small broadcast text link: it periodically sends a plaintext "hello"
// beacon and prints/counts anything it receives. It is deliberately simple and
// is NOT the Meshtastic protocol — see the README for how it relates.
#pragma once

#include <stdint.h>
#include "modes.h"

namespace lora {
  // Returns true if a module was found and initialised.
  bool begin(Stats* stats);
  void stop();
  void loop();

  // Queue a text message for broadcast. Returns false if LoRa isn't up.
  bool send(const char* text);

  bool available();   // compiled in and radio present
}
