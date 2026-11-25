#include "speaker.h"

#include <AudioFileSourceSD.h>
#include <AudioGeneratorMP3.h>
#include <AudioOutput.h>
#include <HTTPClient.h>
#include <SD.h>
#include <WiFiClientSecure.h>
#include <driver/dac.h>
#include <driver/i2s.h>

#include <vector>

#include "display.h"
#include "netconn.h"
#include "settings.h"

namespace speaker {

static const i2s_port_t PORT = I2S_NUM_0;  // only I2S0 can drive the built-in DAC
static const int DMA_BUFS = 8, DMA_LEN = 256;
static bool dacOpen = false;
static int dacRate = 0;
static uint16_t frameBuf[256];  // pairs of 16-bit slots; the DAC uses the high byte
static int frameFill = 0;
static int volScale = 256;      // 0..256
static bool stoppedFlag = false;

bool enabled() { return cfg.speakerOn; }
bool wasStopped() { return stoppedFlag; }

static void flushFrames() {
    if (!frameFill) return;
    size_t written = 0;
    i2s_write(PORT, frameBuf, frameFill * sizeof(uint16_t), &written, portMAX_DELAY);
    frameFill = 0;
}

static inline void pushLevel(uint8_t level) {
    uint16_t v = (uint16_t)level << 8;
    frameBuf[frameFill++] = v;
    frameBuf[frameFill++] = v;
    if (frameFill >= (int)(sizeof frameBuf / sizeof frameBuf[0])) flushFrames();
}

static inline void pushSample(int32_t s) {
    s = (s * volScale) >> 8;
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    pushLevel((uint8_t)((s + 32768) >> 8));
}

// Moving the DAC slowly between 0 V and mid-scale avoids a loud pop.
static void ramp(int from, int to) {
    int steps = dacRate / 50;  // 20 ms
    for (int i = 0; i <= steps; i++) pushLevel((uint8_t)(from + (to - from) * i / steps));
}

static void silence(int ms) {
    int frames = dacRate * ms / 1000;
    for (int i = 0; i < frames; i++) pushLevel(128);
}

static bool openDac(int rate) {
    if (dacOpen) {
        if (rate != dacRate) {
            flushFrames();
            i2s_set_sample_rates(PORT, rate);
            dacRate = rate;
        }
        return true;
    }
    int v = cfg.volume;
    volScale = v * v * 256 / 10000;  // closer to how loudness is heard
    i2s_config_t c = {};
    c.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN);
    c.sample_rate = rate;
    c.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
    c.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
    c.communication_format = I2S_COMM_FORMAT_STAND_MSB;
    c.intr_alloc_flags = 0;
    c.dma_buf_count = DMA_BUFS;
    c.dma_buf_len = DMA_LEN;
    c.use_apll = false;
    c.tx_desc_auto_clear = true;
    if (i2s_driver_install(PORT, &c, 0, nullptr) != ESP_OK) return false;
    // GPIO 26 only. Enabling both DAC channels would take over GPIO 25, the touch clock.
    i2s_set_dac_mode(I2S_DAC_CHANNEL_LEFT_EN);
    dacOpen = true;
    dacRate = rate;
    frameFill = 0;
    ramp(0, 128);
    return true;
}

static void closeDac() {
    if (!dacOpen) return;
    ramp(128, 0);
    silence(0);
    for (int i = 0; i < DMA_BUFS * DMA_LEN / 128 + 1; i++) {  // push zeros through the DMA queue
        for (int k = 0; k < 128; k++) pushLevel(0);
    }
    flushFrames();
    delay(20);
    i2s_driver_uninstall(PORT);
    dac_output_disable(DAC_CHANNEL_2);
    dac_i2s_disable();
    dacOpen = false;
    touch::begin();  // make sure GPIO 25 is still a plain output for the touch chip
}

// ---------------- tones ----------------

static void tone(float freq, int ms, float amp) {
    int n = dacRate * ms / 1000;
    int fade = dacRate / 100;  // 10 ms in and out
    for (int i = 0; i < n; i++) {
        float env = 1.0f;
        if (i < fade) env = (float)i / fade;
        else if (i > n - fade * 4) env = (float)(n - i) / (fade * 4);
        float s = sinf(2 * PI * freq * i / dacRate) * env * amp;
        pushSample((int32_t)(s * 32767));
    }
}

void chime() {
    if (!enabled() || !openDac(16000)) return;
    tone(784, 180, 0.6f);  // G5
    tone(1047, 380, 0.6f); // C6
    closeDac();
}

void listenBeep() {
    if (!enabled() || !openDac(16000)) return;
    tone(880, 120, 0.5f);
    closeDac();
}

void doneBeep() {
    if (!enabled() || !openDac(16000)) return;
    tone(660, 90, 0.5f);
    closeDac();
}

// ---------------- WAV (microphone test) ----------------

bool playWav(const char* path, const StopFn& stop) {
    if (!enabled()) return false;
    File f = SD.open(path, FILE_READ);
    if (!f) return false;
    uint8_t header[44];
    if (f.read(header, 44) != 44) return false;
    uint32_t rate = header[24] | header[25] << 8 | header[26] << 16 | (uint32_t)header[27] << 24;
    if (!openDac(rate ? rate : 16000)) return false;
    int16_t buf[256];
    stoppedFlag = false;
    while (f.available()) {
        int n = f.read((uint8_t*)buf, sizeof buf) / 2;
        for (int i = 0; i < n; i++) pushSample(buf[i]);
        if (stop && stop()) {
            stoppedFlag = true;
            break;
        }
    }
    f.close();
    closeDac();
    return true;
}

// ---------------- MP3 + text to speech ----------------

// Hands decoded MP3 samples to the DAC.
class DacOutput : public AudioOutput {
public:
    DacOutput() {
        hertz = 0;  // the decoder calls begin() before it knows the rate
        bps = 16;
        channels = 2;
        gainF2P6 = 1 << 6;
    }
    bool begin() override { return openDac(hertz ? hertz : 24000); }
    bool SetRate(int hz) override {
        hertz = hz;
        if (dacOpen && hz > 0) openDac(hz);
        return true;
    }
    bool ConsumeSample(int16_t sample[2]) override {
        MakeSampleStereo16(sample);
        pushSample(((int32_t)sample[0] + sample[1]) / 2);
        return true;
    }
    bool stop() override {
        flushFrames();
        return true;
    }
};

static bool playMp3(const char* path, const StopFn& stop) {
    AudioFileSourceSD src(path);
    if (!src.isOpen()) return false;
    AudioGeneratorMP3 mp3;
    DacOutput out;
    if (!mp3.begin(&src, &out)) return false;
    while (mp3.isRunning()) {
        if (!mp3.loop()) break;
        if (stop && stop()) {
            stoppedFlag = true;
            break;
        }
    }
    mp3.stop();
    // Keep the DMA queue fed while the next clip loads, so there's no click.
    silence(DMA_BUFS * DMA_LEN * 1000 / (dacRate ? dacRate : 24000));
    return true;
}

static String urlEncode(const String& s) {
    static const char* hex = "0123456789ABCDEF";
    String out;
    out.reserve(s.length() * 3);
    for (size_t i = 0; i < s.length(); i++) {
        uint8_t c = (uint8_t)s[i];
        if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
            out += (char)c;
        } else {
            out += '%';
            out += hex[c >> 4];
            out += hex[c & 15];
        }
    }
    return out;
}

// The free voice takes up to ~200 characters per request, so split at
// sentence ends, then commas, then spaces.
static std::vector<String> splitForSpeech(const String& text, size_t maxLen) {
    std::vector<String> parts;
    String rest = text;
    rest.trim();
    while (rest.length()) {
        if (rest.length() <= maxLen) {
            parts.push_back(rest);
            break;
        }
        int cut = -1;
        for (const char* marks : {".!?", ",;:", " "}) {
            for (int i = (int)maxLen; i > (int)maxLen / 3; i--) {
                if (strchr(marks, rest[i])) {
                    cut = i + 1;
                    break;
                }
            }
            if (cut > 0) break;
        }
        if (cut <= 0) cut = (int)maxLen;
        String part = rest.substring(0, cut);
        part.trim();
        if (part.length()) parts.push_back(part);
        rest = rest.substring(cut);
        rest.trim();
    }
    return parts;
}

static bool download(const String& url, const char* path) {
    WiFiClientSecure client;
    client.setInsecure();
    HTTPClient http;
    http.setConnectTimeout(10000);
    http.setTimeout(15000);
    http.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                      "Chrome/124.0 Safari/537.36");
    if (!http.begin(client, url)) return false;
    int code = http.GET();
    if (code != 200) {
        Serial.printf("Voice: HTTP %d\n", code);
        http.end();
        return false;
    }
    File f = SD.open(path, FILE_WRITE);
    if (!f) {
        http.end();
        return false;
    }
    int n = http.writeToStream(&f);
    f.close();
    http.end();
    return n > 0;
}

bool say(const String& text, const StopFn& stop) {
    stoppedFlag = false;
    if (!enabled() || text.isEmpty() || !netconn::online()) return false;
    std::vector<String> parts = splitForSpeech(text, 180);
    if (parts.size() > 12) parts.resize(12);

    if (!netconn::lockHttps(30000)) return false;
    int got = 0;
    for (size_t i = 0; i < parts.size(); i++) {
        String url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=" + cfg.lang +
                     "&q=" + urlEncode(parts[i]);
        String path = "/frame/tts" + String(i) + ".mp3";
        if (!download(url, path.c_str())) break;
        got++;
        if (stop && stop()) {
            stoppedFlag = true;
            break;
        }
    }
    netconn::unlockHttps();
    if (!got || stoppedFlag) return got > 0;

    bool opened = false;
    for (int i = 0; i < got && !stoppedFlag; i++) {
        String path = "/frame/tts" + String(i) + ".mp3";
        if (!opened) opened = openDac(24000);
        if (!opened) break;
        playMp3(path.c_str(), stop);
    }
    closeDac();
    return true;
}

}  // namespace speaker
