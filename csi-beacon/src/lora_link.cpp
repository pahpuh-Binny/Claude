#include "lora_link.h"
#include "config.h"

#if ENABLE_LORA

#include <RadioLib.h>
#include <SPI.h>
#include <Arduino.h>

namespace {
  // Dedicated SPI instance so we can drive the module on the chosen pins
  // without disturbing the display's HSPI bus.
  SPIClass g_spi(VSPI);
  SX1276   g_radio = new Module(CFG_LORA_NSS, CFG_LORA_DIO0, CFG_LORA_RST, CFG_LORA_BUSY, g_spi);

  Stats*   g_stats = nullptr;
  bool     g_up = false;
  uint32_t g_lastBeaconMs = 0;
  volatile bool g_rxFlag = false;

  void IRAM_ATTR onDio0() { g_rxFlag = true; }
}

namespace lora {

bool begin(Stats* stats) {
  g_stats = stats;
  g_spi.begin(CFG_LORA_SCK, CFG_LORA_MISO, CFG_LORA_MOSI, CFG_LORA_NSS);

  int st = g_radio.begin(CFG_LORA_FREQ_MHZ, CFG_LORA_BW_KHZ, CFG_LORA_SF,
                         CFG_LORA_CR, CFG_LORA_SYNC, CFG_LORA_TX_DBM);
  if (st != RADIOLIB_ERR_NONE) {
    Serial.printf("[lora] init failed, code %d (check wiring/module)\n", st);
    g_up = false;
    return false;
  }
  g_radio.setDio0Action(onDio0, RISING);
  g_radio.startReceive();
  g_up = true;
  g_lastBeaconMs = millis();
  Serial.printf("[lora] up: %.1f MHz SF%d BW%.0f sync 0x%02X\n",
                CFG_LORA_FREQ_MHZ, CFG_LORA_SF, CFG_LORA_BW_KHZ, CFG_LORA_SYNC);
  return true;
}

void stop() {
  if (g_up) { g_radio.sleep(); g_up = false; }
}

void loop() {
  if (!g_up) return;

  // Received packet waiting?
  if (g_rxFlag) {
    g_rxFlag = false;
    String msg;
    int st = g_radio.readData(msg);
    if (st == RADIOLIB_ERR_NONE) {
      if (g_stats) { g_stats->loraRx++; g_stats->lastRssi = (int)g_radio.getRSSI(); }
      Serial.printf("[lora] rx (%.0f dBm, SNR %.1f): %s\n",
                    g_radio.getRSSI(), g_radio.getSNR(), msg.c_str());
    }
    g_radio.startReceive();
  }

  // Periodic hello beacon.
  if (CFG_LORA_BEACON_MS > 0 && (uint32_t)(millis() - g_lastBeaconMs) >= CFG_LORA_BEACON_MS) {
    g_lastBeaconMs = millis();
    char b[48];
    snprintf(b, sizeof(b), "CSI-BEACON hello %lu", (unsigned long)(millis() / 1000));
    send(b);
  }
}

bool send(const char* text) {
  if (!g_up) return false;
  int st = g_radio.transmit((uint8_t*)text, strlen(text));
  g_radio.startReceive();
  if (st == RADIOLIB_ERR_NONE) {
    if (g_stats) g_stats->loraTx++;
    Serial.printf("[lora] tx: %s\n", text);
    return true;
  }
  Serial.printf("[lora] tx failed, code %d\n", st);
  return false;
}

bool available() { return g_up; }

} // namespace lora

#else  // ENABLE_LORA not defined — stub so the rest of the firmware builds.

namespace lora {
  bool begin(Stats*) { return false; }
  void stop() {}
  void loop() {}
  bool send(const char*) { return false; }
  bool available() { return false; }
}

#endif
