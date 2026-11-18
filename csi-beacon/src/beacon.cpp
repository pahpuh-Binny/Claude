#include "beacon.h"
#include "config.h"

#include <WiFi.h>
#include <esp_wifi.h>
#include <esp_system.h>

namespace {
  uint8_t  g_channel = CFG_CHANNEL;
  uint16_t g_rate    = CFG_TX_RATE_HZ;
  uint32_t g_intervalUs = 0;     // microseconds between injected frames
  uint32_t g_lastTxUs   = 0;
  uint32_t g_seq        = 0;

  const uint8_t kSrcMac[6] = CFG_BEACON_MAC;
  const uint8_t kMagic[4]  = CFG_MAGIC;

  // Template for an 802.11 management "action" frame. No association needed:
  // any receiver in promiscuous mode gets a CSI record for each one.
  //   [0]   frame control: 0xD0 = management / subtype action, 0x00 flags
  //   [2]   duration
  //   [4]   addr1 = destination (broadcast)
  //   [10]  addr2 = source  (our MAC)
  //   [16]  addr3 = BSSID   (our MAC)
  //   [22]  sequence control
  //   [24]  action body: category (0x7F = vendor-specific) + our payload
  uint8_t g_frame[64] = {
    0xD0, 0x00, 0x00, 0x00,
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,          // addr1 broadcast
    0x02, 0x43, 0x53, 0x49, 0x00, 0x01,          // addr2 (overwritten in begin)
    0x02, 0x43, 0x53, 0x49, 0x00, 0x01,          // addr3 (overwritten in begin)
    0x00, 0x00,                                   // seq ctrl
    0x7F,                                         // category: vendor-specific
    'C', 'S', 'I', 'B',                           // OUI/magic (overwritten)
    0, 0, 0, 0,                                    // seq counter
    0, 0, 0, 0                                     // millis timestamp
  };
  const size_t g_frameLen = 37;

  void recomputeInterval() {
    g_intervalUs = g_rate ? (1000000UL / g_rate) : 0;
  }

  void applyChannel() {
    esp_wifi_set_channel(g_channel, WIFI_SECOND_CHAN_NONE);
  }
}

namespace beacon {

void begin(uint8_t channel, uint16_t rateHz, const char* ssid, const char* pass) {
  g_channel = channel ? channel : CFG_CHANNEL;
  g_rate    = rateHz;
  recomputeInterval();

  // Stamp our source MAC and magic into the frame template.
  memcpy(&g_frame[10], kSrcMac, 6);
  memcpy(&g_frame[16], kSrcMac, 6);
  memcpy(&g_frame[25], kMagic, 4);

  WiFi.mode(WIFI_AP);
  // Open AP if no password, else WPA2. channel, hidden=false, max 4 clients.
  if (pass && strlen(pass) >= 8) {
    WiFi.softAP(ssid, pass, g_channel, 0, 4);
  } else {
    WiFi.softAP(ssid, nullptr, g_channel, 0, 4);
  }
  // Force the exact channel (softAP may have negotiated something else).
  applyChannel();

  g_lastTxUs = micros();
  g_seq = 0;
}

void stop() {
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_OFF);
}

uint32_t loop() {
  if (g_intervalUs == 0) return 0;          // injection disabled
  uint32_t now = micros();
  uint32_t sent = 0;
  // Catch up if we fell behind, but cap the burst so we never spin forever.
  for (int guard = 0; guard < 8 && (uint32_t)(now - g_lastTxUs) >= g_intervalUs; ++guard) {
    g_lastTxUs += g_intervalUs;

    // Sequence number (12-bit field, shifted into the seq-ctrl bytes).
    uint16_t sc = (uint16_t)((g_seq & 0x0FFF) << 4);
    g_frame[22] = sc & 0xFF;
    g_frame[23] = (sc >> 8) & 0xFF;

    uint32_t s = g_seq;
    g_frame[29] = s & 0xFF; g_frame[30] = (s >> 8) & 0xFF;
    g_frame[31] = (s >> 16) & 0xFF; g_frame[32] = (s >> 24) & 0xFF;

    uint32_t t = millis();
    g_frame[33] = t & 0xFF; g_frame[34] = (t >> 8) & 0xFF;
    g_frame[35] = (t >> 16) & 0xFF; g_frame[36] = (t >> 24) & 0xFF;

    // en_sys_seq=false: we manage the sequence field ourselves.
    esp_err_t err = esp_wifi_80211_tx(WIFI_IF_AP, g_frame, g_frameLen, false);
    if (err == ESP_OK) { ++g_seq; ++sent; }
    else break;        // back off on error (e.g. radio busy); try next loop()
    now = micros();
  }
  return sent;
}

void setRate(uint16_t rateHz) { g_rate = rateHz; recomputeInterval(); }

void setChannel(uint8_t channel) {
  if (channel >= 1 && channel <= 13) { g_channel = channel; applyChannel(); }
}

uint8_t  channel() { return g_channel; }
uint16_t rate()    { return g_rate; }

} // namespace beacon
