// modes.h — shared enums and the small state shared between modules.
#pragma once

enum Mode {
  MODE_BEACON = 0,  // transmit frames so another device can measure CSI
  MODE_RADAR  = 1,  // receive + visualise CSI on the screen
  MODE_LORA   = 2,  // LoRa text link (needs -DENABLE_LORA=1)
  MODE_COUNT
};

// Live counters the display reads. Updated by the active mode.
struct Stats {
  volatile uint32_t txFrames  = 0;   // frames we have injected
  volatile uint32_t rxCsi     = 0;   // CSI records captured
  volatile uint32_t loraRx    = 0;   // LoRa packets received
  volatile uint32_t loraTx    = 0;   // LoRa packets sent
  volatile float    motion    = 0;   // 0..1 motion metric (radar mode)
  volatile int      lastRssi  = 0;   // last RSSI seen (radar / lora)
};
