// CSI Beacon & WiFi-sensing radar firmware for the ESP32 "Cheap Yellow Display".
//
// Three modes, switch with the BOOT button or over USB serial (115200 baud):
//   BEACON  be a CSI beacon: run a SoftAP and inject recognizable frames at a
//           fixed rate so a separate receiver can measure channel state (CSI).
//   RADAR   capture CSI yourself and draw an amplitude waterfall + motion bar.
//   LORA    optional SX127x text link (only in the cyd-lora build).
//
// This firmware transmits beacon frames and reads radio channel state. It does
// not deauthenticate clients, capture credentials, or attack other networks.
// Use it on radios and spectrum you are allowed to operate.

#include <Arduino.h>
#include "config.h"
#include "modes.h"
#include "beacon.h"
#include "radar.h"
#include "lora_link.h"
#include "display.h"

static Stats    g_stats;
static Mode     g_mode = (Mode)CFG_START_MODE;
static uint8_t  g_channel = CFG_CHANNEL;
static uint16_t g_rate = CFG_TX_RATE_HZ;

// --- CYD onboard RGB LED (common-anode, active LOW) ------------------------
static const int LED_R = 4, LED_G = 16, LED_B = 17;
static void led(bool r, bool g, bool b) {
  digitalWrite(LED_R, r ? LOW : HIGH);
  digitalWrite(LED_G, g ? LOW : HIGH);
  digitalWrite(LED_B, b ? LOW : HIGH);
}

static const int BOOT_BTN = 0;   // GPIO0, pressed = LOW

static char g_extra[64] = "";

static void buildExtra() {
  switch (g_mode) {
    case MODE_BEACON:
      snprintf(g_extra, sizeof(g_extra), "SSID %s", CFG_AP_SSID);
      break;
    case MODE_RADAR:
      snprintf(g_extra, sizeof(g_extra), "listening, promiscuous CSI");
      break;
    case MODE_LORA:
      if (lora::available())
        snprintf(g_extra, sizeof(g_extra), "%.1fMHz SF%d", CFG_LORA_FREQ_MHZ, CFG_LORA_SF);
      else
        snprintf(g_extra, sizeof(g_extra), "no module (build -DENABLE_LORA=1)");
      break;
    default: g_extra[0] = 0;
  }
}

static void stopAll() {
  beacon::stop();
  radar::stop();
  lora::stop();
  delay(50);
}

static void enterMode(Mode m) {
  stopAll();
  g_mode = m;
  led(false, false, false);

  switch (m) {
    case MODE_BEACON:
      beacon::begin(g_channel, g_rate, CFG_AP_SSID, CFG_AP_PASSWORD);
      led(false, false, true);                 // blue = transmitting
      break;
    case MODE_RADAR:
      radar::begin(g_channel, &g_stats);
      led(false, true, false);                 // green = listening
      break;
    case MODE_LORA:
      if (lora::begin(&g_stats)) led(true, false, true);   // magenta = lora up
      else                       led(true, false, false);  // red = no module
      break;
    default: break;
  }

  buildExtra();
  display::setMode(m);
  Serial.printf("[mode] %d\n", (int)m);
}

// ---------------------------------------------------------------------------
// Serial command console
// ---------------------------------------------------------------------------
static void printHelp() {
  Serial.println(F(
    "commands:\n"
    "  mode beacon|radar|lora   switch mode\n"
    "  ch <1-13>                set WiFi channel (beacon & radar)\n"
    "  rate <hz>                set beacon injection rate (0=beacons only)\n"
    "  lora <text>              send a LoRa message (lora mode)\n"
    "  stats                    print counters\n"
    "  help"));
}

static void handleLine(char* s) {
  while (*s == ' ') ++s;
  if (!*s) return;

  if (!strncmp(s, "help", 4)) { printHelp(); return; }

  if (!strncmp(s, "stats", 5)) {
    Serial.printf("mode=%d ch=%u rate=%u tx=%lu csi=%lu loraRx=%lu loraTx=%lu motion=%.2f rssi=%d\n",
      (int)g_mode, g_channel, g_rate,
      (unsigned long)g_stats.txFrames, (unsigned long)g_stats.rxCsi,
      (unsigned long)g_stats.loraRx, (unsigned long)g_stats.loraTx,
      g_stats.motion, g_stats.lastRssi);
    return;
  }

  if (!strncmp(s, "mode ", 5)) {
    const char* a = s + 5;
    if (!strncmp(a, "beacon", 6))      enterMode(MODE_BEACON);
    else if (!strncmp(a, "radar", 5))  enterMode(MODE_RADAR);
    else if (!strncmp(a, "lora", 4))   enterMode(MODE_LORA);
    else Serial.println(F("? mode beacon|radar|lora"));
    return;
  }

  if (!strncmp(s, "ch ", 3)) {
    int c = atoi(s + 3);
    if (c >= 1 && c <= 13) {
      g_channel = c;
      beacon::setChannel(c);
      radar::setChannel(c);
      buildExtra();
      Serial.printf("[ch] %d\n", c);
    } else Serial.println(F("? ch 1-13"));
    return;
  }

  if (!strncmp(s, "rate ", 5)) {
    int r = atoi(s + 5);
    if (r >= 0 && r <= 2000) {
      g_rate = r;
      beacon::setRate(r);
      Serial.printf("[rate] %d Hz\n", r);
    } else Serial.println(F("? rate 0-2000"));
    return;
  }

  if (!strncmp(s, "lora ", 5)) {
    if (!lora::send(s + 5)) Serial.println(F("lora not available / not in lora mode"));
    return;
  }

  Serial.println(F("? unknown; type help"));
}

static void pollSerial() {
  static char buf[96];
  static size_t len = 0;
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') continue;
    if (c == '\n') { buf[len] = 0; handleLine(buf); len = 0; }
    else if (len < sizeof(buf) - 1) buf[len++] = c;
  }
}

static void pollButton() {
  static uint32_t lastChange = 0;
  static int lastState = HIGH;
  int st = digitalRead(BOOT_BTN);
  if (st != lastState && (millis() - lastChange) > 200) {
    lastChange = millis();
    lastState = st;
    if (st == LOW) {                         // pressed
      Mode next = (Mode)((g_mode + 1) % MODE_COUNT);
      enterMode(next);
    }
  }
}

// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println(F("\nCSI Beacon firmware " FW_VERSION));
  printHelp();

  pinMode(LED_R, OUTPUT); pinMode(LED_G, OUTPUT); pinMode(LED_B, OUTPUT);
  led(false, false, false);
  pinMode(BOOT_BTN, INPUT_PULLUP);

  display::begin();
  enterMode(g_mode);
}

void loop() {
  pollSerial();
  pollButton();

  switch (g_mode) {
    case MODE_BEACON: g_stats.txFrames += beacon::loop(); break;
    case MODE_RADAR:  radar::loop(); break;
    case MODE_LORA:   lora::loop(); break;
    default: break;
  }

  // --- display refresh ---
  static uint32_t lastStatus = 0;
  if (millis() - lastStatus > 300) {
    lastStatus = millis();
    display::status(g_mode, g_stats, g_channel, g_rate, g_extra);

    // blink the LED in beacon mode as a heartbeat
    if (g_mode == MODE_BEACON) {
      static bool on = false; on = !on;
      led(false, false, on);
    }
  }

  // Radar: drain up to a few new CSI columns into the waterfall per loop.
  if (g_mode == MODE_RADAR) {
    uint8_t col[radar::MAX_SUB];
    for (int k = 0; k < 6; ++k) {
      int n = radar::takeColumn(col, radar::MAX_SUB);
      if (n <= 0) break;
      display::radarColumn(col, n, g_stats);
    }
    // Red when movement detected, green when the room is still.
    if (g_stats.motion >= 0.5f) led(true, false, false);
    else                        led(false, true, false);
  }
}
