#include "radar.h"
#include "config.h"

#include <WiFi.h>
#include <esp_wifi.h>
#include <math.h>
#include <string.h>

namespace {
  Stats*  g_stats = nullptr;
  uint8_t g_channel = CFG_CHANNEL;

  bool    g_filterOn = false;
  uint8_t g_filterMac[6] = {0};

  // Latest amplitude column, filled by the CSI callback, drained by takeColumn.
  volatile bool g_newCol = false;
  uint8_t  g_col[radar::MAX_SUB];
  int      g_colSub = 0;

  // Previous amplitudes (float) for the motion metric.
  float    g_prev[radar::MAX_SUB];
  bool     g_havePrev = false;

  // The CSI callback. Runs in the WiFi task — keep it short, no TFT/Serial.
  void IRAM_ATTR onCsi(void* ctx, wifi_csi_info_t* info) {
    if (!info || !info->buf || info->len < 4) return;

    if (g_filterOn && memcmp(info->mac, g_filterMac, 6) != 0) return;

    const int8_t* buf = info->buf;
    int pairs = info->len / 2;
    if (pairs > radar::MAX_SUB) pairs = radar::MAX_SUB;

    float inst = 0.0f;       // mean |amp - prev| this frame
    int   counted = 0;
    for (int i = 0; i < pairs; ++i) {
      int8_t imag = buf[i * 2];
      int8_t real = buf[i * 2 + 1];
      float amp = sqrtf((float)real * real + (float)imag * imag);

      if (g_havePrev) { inst += fabsf(amp - g_prev[i]); ++counted; }
      g_prev[i] = amp;

      // Map amplitude (~0..90 for int8 pairs) to 0..255 for the waterfall.
      int v = (int)(amp * 2.8f);
      if (v > 255) v = 255;
      g_col[i] = (uint8_t)v;
    }
    g_colSub = pairs;
    g_havePrev = true;
    g_newCol = true;

    if (g_stats) {
      g_stats->rxCsi++;
      g_stats->lastRssi = info->rx_ctrl.rssi;
      if (counted > 0) {
        float m = inst / counted;          // average per-subcarrier change
        float norm = m / 18.0f;            // ~18 units of change => "a lot"
        if (norm > 1.0f) norm = 1.0f;
        // Exponential moving average so the bar is readable, not jittery.
        g_stats->motion = g_stats->motion * 0.80f + norm * 0.20f;
      }
    }
  }
}

namespace radar {

void begin(uint8_t channel, Stats* stats) {
  g_stats = stats;
  g_channel = channel ? channel : CFG_CHANNEL;
  g_havePrev = false;
  g_newCol = false;

  // We only need to listen, so bring WiFi up in STA mode but never connect.
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(g_channel, WIFI_SECOND_CHAN_NONE);

  wifi_csi_config_t csi = {};
  csi.lltf_en           = true;   // the legacy long training field => 64 subcarriers
  csi.htltf_en          = false;
  csi.stbc_htltf2_en    = false;
  csi.ltf_merge_en      = true;
  csi.channel_filter_en = true;
  csi.manu_scale        = false;
  esp_wifi_set_csi_config(&csi);
  esp_wifi_set_csi_rx_cb(onCsi, nullptr);
  esp_wifi_set_csi(true);
}

void stop() {
  esp_wifi_set_csi(false);
  esp_wifi_set_promiscuous(false);
  WiFi.mode(WIFI_OFF);
  g_stats = nullptr;
}

void loop() { /* all work happens in the CSI callback */ }

void setChannel(uint8_t channel) {
  if (channel >= 1 && channel <= 13) {
    g_channel = channel;
    esp_wifi_set_channel(g_channel, WIFI_SECOND_CHAN_NONE);
    g_havePrev = false;
  }
}

uint8_t channel() { return g_channel; }

void setFilterMac(const uint8_t* mac6) {
  if (mac6) { memcpy(g_filterMac, mac6, 6); g_filterOn = true; }
  else      { g_filterOn = false; }
}

int takeColumn(uint8_t* out, int maxOut) {
  if (!g_newCol) return 0;
  int n = g_colSub;
  if (n > maxOut) n = maxOut;
  memcpy(out, (const void*)g_col, n);
  g_newCol = false;
  return n;
}

} // namespace radar
