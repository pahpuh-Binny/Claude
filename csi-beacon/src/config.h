// config.h — compile-time defaults for the CSI Beacon firmware.
//
// Everything here can be overridden at runtime over the USB serial console
// (115200 baud). These are just the power-on defaults.
#pragma once

#include <stdint.h>

// ---------------------------------------------------------------------------
// Beacon identity
// ---------------------------------------------------------------------------
// SoftAP SSID the beacon advertises. A CSI receiver can lock onto the beacon
// frames this AP emits automatically, or associate to it. Keep it recognizable.
#define CFG_AP_SSID        "CSI-BEACON"

// WPA2 passphrase for the AP. Leave empty ("") for an open AP, which makes it
// trivial for a sensing receiver to associate. Must be 8+ chars if set.
#define CFG_AP_PASSWORD    ""

// Source/BSSID MAC the high-rate frames are sent from. The receiver filters on
// this so it only measures CSI from *our* beacon and ignores other traffic.
// 02:xx => locally-administered, unicast. Change the last bytes to run several
// beacons in one room without them colliding.
#define CFG_BEACON_MAC     { 0x02, 0x43, 0x53, 0x49, 0x00, 0x01 }

// A 4-byte magic prefix stamped into every high-rate frame payload. The
// receiver can use it as a second check that a frame really is ours.
#define CFG_MAGIC          { 'C', 'S', 'I', 'B' }

// ---------------------------------------------------------------------------
// WiFi / CSI
// ---------------------------------------------------------------------------
// 2.4 GHz channel (1..13). The beacon and the receiver MUST be on the same one.
#define CFG_CHANNEL        6

// High-rate injection: extra action frames per second, on top of the ~10/s
// management beacons the SoftAP emits by itself. 0 disables injection and
// relies only on the automatic beacons. 100 Hz is a good sensing rate.
#define CFG_TX_RATE_HZ     100

// ---------------------------------------------------------------------------
// Start-up behaviour
// ---------------------------------------------------------------------------
// MODE_BEACON  transmit (be a CSI beacon)   <- default
// MODE_RADAR   receive + visualise CSI on the screen
// MODE_LORA    LoRa link (only if built with -DENABLE_LORA=1)
#define CFG_START_MODE     MODE_BEACON

// ---------------------------------------------------------------------------
// LoRa (optional, requires an attached SX127x module — see README "Wiring").
// These default pins share the SD card's VSPI bus (SCK/MISO/MOSI) and use the
// CYD's broken-out GPIOs for the control lines. Verify against your board!
// ---------------------------------------------------------------------------
#define CFG_LORA_FREQ_MHZ  915.0f   // 915 (US/AU), 868 (EU), 433 — match your module & local rules
#define CFG_LORA_BW_KHZ    125.0f
#define CFG_LORA_SF        9        // spreading factor 7..12
#define CFG_LORA_CR        5        // coding rate denominator 5..8 (=> 4/5)
#define CFG_LORA_SYNC      0x2B     // sync word (0x2B is the Meshtastic LongFast word; 0x12 is the generic default)
#define CFG_LORA_TX_DBM    17       // transmit power in dBm (respect your region's limit)

#define CFG_LORA_SCK   18   // VSPI SCK  (shared with SD)
#define CFG_LORA_MISO  19   // VSPI MISO (shared with SD)
#define CFG_LORA_MOSI  23   // VSPI MOSI (shared with SD)
#define CFG_LORA_NSS   22   // CYD CN1 header
#define CFG_LORA_RST   27   // CYD P3/extend header
#define CFG_LORA_DIO0  35   // CYD CN1 header (input-only GPIO — fine for DIO0/IRQ)
#define CFG_LORA_BUSY  -1   // SX126x only; -1 for SX127x

// How often (ms) to broadcast a LoRa hello beacon in MODE_LORA. 0 = listen only.
#define CFG_LORA_BEACON_MS 15000
