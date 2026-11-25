#pragma once
// Wi-Fi, the first-time setup hotspot, clock sync and a lock that keeps
// two HTTPS connections from running at once (each needs ~45 KB of RAM).

#include <Arduino.h>

namespace netconn {

constexpr const char* SETUP_SSID = "FamilyFrame-Setup";

bool connect(uint32_t timeoutMs);  // join cfg.wifiSsid
void startSetupHotspot();          // open hotspot + captive portal
bool setupMode();
bool online();
void loop();                       // captive DNS, reconnects
String ip();
String url();                      // "http://192.168.1.23/"
bool timeValid();
void applyTimezone();

// Serializes HTTPS work between the background news task and the main loop.
bool lockHttps(uint32_t waitMs);
void unlockHttps();

}  // namespace netconn
