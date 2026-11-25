#include "netconn.h"

#include <DNSServer.h>
#include <ESPmDNS.h>
#include <WiFi.h>
#include <time.h>

#include "settings.h"

namespace netconn {

static DNSServer dns;
static bool hotspot = false;
static SemaphoreHandle_t httpsMutex = nullptr;
static uint32_t lastReconnect = 0;

static void ensureMutex() {
    if (!httpsMutex) httpsMutex = xSemaphoreCreateMutex();
}

static bool wasConnected = false;

// Runs each time Wi-Fi comes up (at start, or after the router was down).
static void onConnected() {
    wasConnected = true;
    WiFi.setSleep(false);  // keeps the web page snappy
    MDNS.end();
    if (MDNS.begin(cfg.hostname.c_str())) MDNS.addService("http", "tcp", 80);
    Serial.printf("Wi-Fi connected: %s\n", WiFi.localIP().toString().c_str());
}

void applyTimezone() {
    configTzTime(cfg.tz.c_str(), "pool.ntp.org", "time.google.com", "time.nist.gov");
}

bool connect(uint32_t timeoutMs) {
    ensureMutex();
    if (cfg.wifiSsid.isEmpty()) return false;
    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(cfg.hostname.c_str());
    WiFi.setAutoReconnect(true);
    WiFi.begin(cfg.wifiSsid.c_str(), cfg.wifiPass.c_str());
    applyTimezone();  // the clock syncs whenever Wi-Fi comes up, even if that's later
    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < timeoutMs) delay(100);
    if (WiFi.status() != WL_CONNECTED) return false;
    onConnected();
    return true;
}

void startSetupHotspot() {
    ensureMutex();
    WiFi.disconnect(true);
    WiFi.mode(WIFI_AP_STA);  // STA too, so the setup page can scan for networks
    WiFi.softAP(SETUP_SSID);
    delay(200);
    dns.start(53, "*", WiFi.softAPIP());
    hotspot = true;
    Serial.printf("Setup hotspot \"%s\" at %s\n", SETUP_SSID, WiFi.softAPIP().toString().c_str());
}

bool setupMode() { return hotspot; }

bool online() { return !hotspot && WiFi.status() == WL_CONNECTED; }

void loop() {
    if (hotspot) {
        dns.processNextRequest();
        return;
    }
    bool up = WiFi.status() == WL_CONNECTED;
    if (up && !wasConnected) onConnected();
    if (!up) wasConnected = false;
    // The ESP32 reconnects by itself; nudge it if it has been down a while.
    if (!up && !cfg.wifiSsid.isEmpty() && millis() - lastReconnect > 60000) {
        lastReconnect = millis();
        WiFi.reconnect();
    }
}

String ip() { return hotspot ? WiFi.softAPIP().toString() : WiFi.localIP().toString(); }

String url() { return "http://" + ip() + "/"; }

bool timeValid() { return time(nullptr) > 1700000000; }

bool lockHttps(uint32_t waitMs) {
    ensureMutex();
    return xSemaphoreTake(httpsMutex, pdMS_TO_TICKS(waitMs)) == pdTRUE;
}

void unlockHttps() { xSemaphoreGive(httpsMutex); }

}  // namespace netconn
