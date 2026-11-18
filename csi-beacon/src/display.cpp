#include "display.h"
#include <TFT_eSPI.h>

namespace {
  TFT_eSPI tft;

  const int W = 320, H = 240;
  const int HEADER_H = 64;          // title + status strip
  const int PLOT_Y   = HEADER_H;    // waterfall / content top
  const int PLOT_H   = H - HEADER_H - 16;  // leave 16px for the bottom log
  const int LOG_Y    = H - 16;

  int  g_wfX = 0;                   // waterfall write cursor
  Mode g_mode = MODE_BEACON;

  uint16_t ampColor(uint8_t v) {
    // Dark-blue -> cyan -> green -> yellow -> red colormap.
    uint8_t r, g, b;
    if (v < 64)       { r = 0;              g = 0;            b = (uint8_t)(64 + v * 3); }
    else if (v < 128) { r = 0;              g = (v - 64) * 4; b = 255 - (v - 64) * 3; }
    else if (v < 192) { r = (v - 128) * 4;  g = 255;          b = 0; }
    else              { r = 255;            g = 255 - (v - 192) * 4; b = 0; }
    return tft.color565(r, g, b);
  }

  const char* modeName(Mode m) {
    switch (m) {
      case MODE_BEACON: return "CSI BEACON  (TX)";
      case MODE_RADAR:  return "CSI RADAR  (RX)";
      case MODE_LORA:   return "LoRa LINK";
      default:          return "?";
    }
  }
}

namespace display {

void begin() {
  tft.init();
  tft.setRotation(1);               // landscape, 320x240
  tft.fillScreen(TFT_BLACK);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
#ifdef TFT_BL
  pinMode(TFT_BL, OUTPUT);
  digitalWrite(TFT_BL, TFT_BACKLIGHT_ON);
#endif
}

void setMode(Mode m) {
  g_mode = m;
  g_wfX = 0;
  tft.fillScreen(TFT_BLACK);

  // Title bar.
  tft.fillRect(0, 0, W, 22, TFT_NAVY);
  tft.setTextColor(TFT_WHITE, TFT_NAVY);
  tft.setTextFont(2);
  tft.setTextDatum(TL_DATUM);
  tft.drawString(modeName(m), 6, 3);

  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  if (m == MODE_RADAR) {
    // Frame the waterfall plot.
    tft.drawRect(0, PLOT_Y - 1, W, PLOT_H + 2, TFT_DARKGREY);
  }
}

void status(Mode m, const Stats& s, uint8_t channel, uint16_t rate, const char* extra) {
  tft.setTextFont(2);
  tft.setTextDatum(TL_DATUM);
  tft.setTextColor(TFT_CYAN, TFT_BLACK);

  char line[64];
  // Line 1: the counters that matter for this mode.
  switch (m) {
    case MODE_BEACON:
      snprintf(line, sizeof(line), "ch %-2u  %u Hz  tx %lu   ",
               channel, rate, (unsigned long)s.txFrames);
      break;
    case MODE_RADAR:
      snprintf(line, sizeof(line), "ch %-2u  csi %lu  rssi %d   ",
               channel, (unsigned long)s.rxCsi, s.lastRssi);
      break;
    case MODE_LORA:
      snprintf(line, sizeof(line), "rx %lu  tx %lu  rssi %d   ",
               (unsigned long)s.loraRx, (unsigned long)s.loraTx, s.lastRssi);
      break;
    default: line[0] = 0;
  }
  tft.fillRect(0, 24, W, 18, TFT_BLACK);
  tft.drawString(line, 6, 24);

  // Line 2: free text (SSID / config / last message).
  tft.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
  tft.fillRect(0, 42, W, 18, TFT_BLACK);
  if (extra) tft.drawString(extra, 6, 42);

  // Radar mode gets a motion bar across the strip.
  if (m == MODE_RADAR) {
    int bw = (int)(s.motion * (W - 12));
    if (bw < 0) bw = 0; if (bw > W - 12) bw = W - 12;
    uint16_t col = s.motion > 0.6f ? TFT_RED : (s.motion > 0.3f ? TFT_ORANGE : TFT_GREEN);
    tft.fillRect(6, 60, W - 12, 3, TFT_BLACK);
    tft.fillRect(6, 60, bw, 3, col);
  }
}

void radarColumn(const uint8_t* amp, int n, const Stats& s) {
  if (n <= 0) return;
  int x = PLOT_Y >= 0 ? g_wfX : 0;

  // Draw this column: subcarrier index -> y within the plot region.
  for (int py = 0; py < PLOT_H; ++py) {
    int i = (py * n) / PLOT_H;         // nearest-subcarrier scaling
    if (i >= n) i = n - 1;
    tft.drawPixel(x, PLOT_Y + py, ampColor(amp[i]));
  }
  // Bright cursor just ahead so the sweep is visible.
  int cx = (x + 1) % W;
  tft.drawFastVLine(cx, PLOT_Y, PLOT_H, TFT_WHITE);

  g_wfX = (g_wfX + 1) % W;
  (void)s;
}

void note(const char* line) {
  tft.setTextFont(1);
  tft.setTextDatum(TL_DATUM);
  tft.setTextColor(TFT_DARKGREY, TFT_BLACK);
  tft.fillRect(0, LOG_Y, W, 16, TFT_BLACK);
  if (line) tft.drawString(line, 6, LOG_Y + 2);
}

} // namespace display
