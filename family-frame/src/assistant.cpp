#include "assistant.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <SD.h>
#include <WiFiClientSecure.h>
#include <driver/i2s.h>
#include <time.h>

#include "netconn.h"
#include "news.h"
#include "pins.h"
#include "settings.h"
#include "textutil.h"

namespace assistant {

static const i2s_port_t MIC_PORT = I2S_NUM_1;
static const int RATE = 16000;
static const int BLOCK = 256;  // frames per read (16 ms)

// ---------------- recording ----------------

static void writeWavHeader(File& f, uint32_t dataBytes) {
    uint8_t h[44];
    auto put32 = [&](int at, uint32_t v) {
        for (int i = 0; i < 4; i++) h[at + i] = (uint8_t)(v >> (8 * i));
    };
    auto put16 = [&](int at, uint16_t v) {
        h[at] = (uint8_t)v;
        h[at + 1] = (uint8_t)(v >> 8);
    };
    memcpy(h, "RIFF", 4);
    put32(4, 36 + dataBytes);
    memcpy(h + 8, "WAVEfmt ", 8);
    put32(16, 16);
    put16(20, 1);  // PCM
    put16(22, 1);  // mono
    put32(24, RATE);
    put32(28, RATE * 2);
    put16(32, 2);
    put16(34, 16);
    memcpy(h + 36, "data", 4);
    put32(40, dataBytes);
    f.seek(0);
    f.write(h, 44);
}

Recording record(const char* path, uint32_t maxMs, bool untilSilence, const std::function<void(int)>& level,
                 const std::function<bool()>& stop) {
    Recording r;
    i2s_config_t c = {};
    c.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
    c.sample_rate = RATE;
    c.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
    // Read both slots and keep whichever one the mic talks on, so it works
    // whether its L/R pin is tied to ground or 3.3 V.
    c.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
    c.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    c.intr_alloc_flags = 0;
    c.dma_buf_count = 6;
    c.dma_buf_len = BLOCK;
    c.use_apll = false;
    if (i2s_driver_install(MIC_PORT, &c, 0, nullptr) != ESP_OK) return r;
    i2s_pin_config_t p = {};
    p.mck_io_num = I2S_PIN_NO_CHANGE;
    p.bck_io_num = pins::MIC_SCK;
    p.ws_io_num = pins::MIC_WS;
    p.data_out_num = I2S_PIN_NO_CHANGE;
    p.data_in_num = pins::MIC_SD;
    if (i2s_set_pin(MIC_PORT, &p) != ESP_OK) {
        i2s_driver_uninstall(MIC_PORT);
        return r;
    }
    i2s_zero_dma_buffer(MIC_PORT);

    File f = SD.open(path, FILE_WRITE);
    if (!f) {
        i2s_driver_uninstall(MIC_PORT);
        return r;
    }
    writeWavHeader(f, 0);
    r.ok = true;

    static int32_t raw[BLOCK * 2];
    static int16_t pcm[BLOCK];
    int channel = -1;
    double energy[2] = {0, 0};
    float dc = 0;
    bool dcInit = false;
    int noise = 1 << 30, threshold = 0, loudBlocks = 0;
    uint32_t elapsed = 0, quietMs = 0, dataBytes = 0;
    const uint32_t settleMs = 120, probeMs = 200, noiseMs = 420;
    const uint32_t giveUpMs = 5500;  // nobody spoke

    while (elapsed < maxMs) {
        size_t got = 0;
        if (i2s_read(MIC_PORT, raw, sizeof raw, &got, pdMS_TO_TICKS(200)) != ESP_OK || got == 0) break;
        int frames = got / 8;
        elapsed += frames * 1000 / RATE;
        if (elapsed <= settleMs) continue;  // the mic needs a moment after power-up

        if (channel < 0) {
            for (int i = 0; i < frames; i++) {
                energy[0] += fabs((double)(raw[2 * i] >> 8));
                energy[1] += fabs((double)(raw[2 * i + 1] >> 8));
            }
            if (elapsed >= probeMs) channel = energy[1] > energy[0] ? 1 : 0;
            continue;
        }

        long sum = 0;
        for (int i = 0; i < frames; i++) {
            float s = (float)(raw[2 * i + channel] >> 8);  // 24-bit sample
            if (!dcInit) {
                dc = s;
                dcInit = true;
            }
            dc += (s - dc) * 0.002f;  // remove the mic's DC offset
            int32_t v = (int32_t)((s - dc) * cfg.micGain / 256.0f);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            pcm[i] = (int16_t)v;
            int a = abs(v);
            sum += a;
            if (a > r.peak) r.peak = a;
        }
        f.write((const uint8_t*)pcm, frames * 2);
        dataBytes += frames * 2;
        int lvl = (int)(sum / (frames ? frames : 1));
        if (level) level(min(100, lvl * 100 / 3000));

        if (elapsed <= noiseMs) {
            noise = min(noise, lvl);
            continue;
        }
        if (!threshold) threshold = max(noise * 3 + 40, 120);
        if (lvl > threshold) {
            if (++loudBlocks >= 3) r.heardSpeech = true;
            quietMs = 0;
        } else {
            loudBlocks = 0;
            quietMs += frames * 1000 / RATE;
        }
        if (stop && stop()) break;
        if (untilSilence) {
            if (r.heardSpeech && quietMs > 1100) break;
            if (!r.heardSpeech && elapsed > giveUpMs) break;
        }
    }
    r.noise = noise == (1 << 30) ? 0 : noise;
    r.ms = dataBytes / 2 * 1000 / RATE;
    writeWavHeader(f, dataBytes);
    f.close();
    i2s_driver_uninstall(MIC_PORT);
    return r;
}

// ---------------- web services ----------------

bool ready(String& why) {
    if (!cfg.micOn) {
        why = "The microphone is turned off. Turn it on in the frame's settings page once it's wired.";
        return false;
    }
    if (cfg.aiKey.isEmpty()) {
        why = "Add a free AI key on the settings page (Assistant tab) to ask questions.";
        return false;
    }
    if (!netconn::online()) {
        why = "The frame isn't connected to Wi-Fi.";
        return false;
    }
    return true;
}

// Sends a WAV file as multipart/form-data without loading it into memory.
class UploadStream : public Stream {
public:
    UploadStream(const String& head, File& file, const String& tail) : head_(head), file_(file), tail_(tail) {}
    size_t total() { return head_.length() + file_.size() + tail_.length(); }
    int available() override {
        size_t left = (head_.length() - headPos_) + file_.available() + (tail_.length() - tailPos_);
        return left > 0x7FFF ? 0x7FFF : (int)left;
    }
    size_t readBytes(char* buf, size_t len) override {
        size_t n = 0;
        while (n < len) {
            if (headPos_ < head_.length()) {
                size_t k = min(len - n, head_.length() - headPos_);
                memcpy(buf + n, head_.c_str() + headPos_, k);
                headPos_ += k;
                n += k;
            } else if (file_.available()) {
                int k = file_.read((uint8_t*)buf + n, len - n);
                if (k <= 0) break;
                n += k;
            } else if (tailPos_ < tail_.length()) {
                size_t k = min(len - n, tail_.length() - tailPos_);
                memcpy(buf + n, tail_.c_str() + tailPos_, k);
                tailPos_ += k;
                n += k;
            } else {
                break;
            }
        }
        return n;
    }
    int read() override {
        char c;
        return readBytes(&c, 1) == 1 ? (uint8_t)c : -1;
    }
    int peek() override { return -1; }
    size_t write(uint8_t) override { return 0; }
    void flush() override {}

private:
    String head_;
    File& file_;
    String tail_;
    size_t headPos_ = 0, tailPos_ = 0;
};

static String apiError(int code, const String& body) {
    JsonDocument doc;
    if (!deserializeJson(doc, body)) {
        const char* msg = doc["error"]["message"] | (const char*)nullptr;
        if (msg) return String("The AI service said: ") + msg;
    }
    if (code == 401) return "The AI key was refused. Check it on the settings page.";
    if (code == 429) return "The free AI plan's limit was reached. Try again in a minute.";
    if (code < 0) return "Couldn't reach the AI service (" + HTTPClient::errorToString(code) + ").";
    return "The AI service answered with error " + String(code) + ".";
}

bool transcribe(const char* wavPath, String& text, String& error) {
    File f = SD.open(wavPath, FILE_READ);
    if (!f) {
        error = "The recording couldn't be read from the SD card.";
        return false;
    }
    const String boundary = "----FamilyFrameBoundary7d3a";
    auto field = [&](const char* name, const String& value) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    };
    String head = field("model", cfg.sttModel) + field("response_format", "json") +
                  field("language", cfg.lang.substring(0, 2)) + "--" + boundary +
                  "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"question.wav\"\r\n"
                  "Content-Type: audio/wav\r\n\r\n";
    String tail = "\r\n--" + boundary + "--\r\n";
    UploadStream body(head, f, tail);

    if (!netconn::lockHttps(30000)) {
        error = "The frame is busy. Try again in a moment.";
        f.close();
        return false;
    }
    WiFiClientSecure client;
    client.setInsecure();
    HTTPClient http;
    http.setConnectTimeout(10000);
    http.setTimeout(30000);
    bool ok = false;
    if (http.begin(client, cfg.aiUrl + "/audio/transcriptions")) {
        http.addHeader("Authorization", "Bearer " + cfg.aiKey);
        http.addHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
        int code = http.sendRequest("POST", &body, body.total());
        String resp = http.getString();
        if (code == 200) {
            JsonDocument doc;
            if (!deserializeJson(doc, resp)) {
                char clean[400];
                text::clean(doc["text"] | "", clean, sizeof clean);
                text = clean;
                ok = true;
            } else {
                error = "The speech service sent something unexpected.";
            }
        } else {
            error = apiError(code, resp);
        }
        http.end();
    } else {
        error = "The AI address in settings doesn't look right.";
    }
    netconn::unlockHttps();
    f.close();
    return ok;
}

static String systemPrompt() {
    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    char when[64];
    strftime(when, sizeof when, "%A, %B %d, %Y, %I:%M %p", &t);
    String s =
        "You are the friendly voice assistant inside a digital picture frame";
    if (cfg.names.length()) s += " in the home of " + cfg.names;
    s += ". It is " + String(when) + ".";
    if (cfg.place.length()) s += " The frame is in " + cfg.place + ".";
    String wx = news::weatherSentence(false);
    if (wx.length()) s += " " + wx;
    s += "\nYour answer is read aloud and shown on a small screen, so reply in plain spoken English: at most three "
         "short sentences, under 60 words, with no lists, markdown, emoji or web addresses.\n"
         "These are the latest headlines from the frame's news feeds. Use them for questions about the news, deals, "
         "Marvel, games or other topics they cover. You only see headlines, so say so if asked for details:\n";
    s += news::contextForAssistant(5, 2600);
    s += "If you don't know something current, say so briefly.";
    return s;
}

bool ask(const String& question, String& answer, String& error) {
    JsonDocument req;
    req["model"] = cfg.aiModel;
    req["max_tokens"] = 220;
    req["temperature"] = 0.6;
    JsonArray msgs = req["messages"].to<JsonArray>();
    JsonObject sys = msgs.add<JsonObject>();
    sys["role"] = "system";
    sys["content"] = systemPrompt();
    JsonObject user = msgs.add<JsonObject>();
    user["role"] = "user";
    user["content"] = question;
    String body;
    serializeJson(req, body);
    req.clear();

    if (!netconn::lockHttps(30000)) {
        error = "The frame is busy. Try again in a moment.";
        return false;
    }
    WiFiClientSecure client;
    client.setInsecure();
    HTTPClient http;
    http.setConnectTimeout(10000);
    http.setTimeout(40000);
    bool ok = false;
    if (http.begin(client, cfg.aiUrl + "/chat/completions")) {
        http.addHeader("Authorization", "Bearer " + cfg.aiKey);
        http.addHeader("Content-Type", "application/json");
        int code = http.POST(body);
        body = String();
        String resp = http.getString();
        if (code == 200) {
            JsonDocument filter;
            filter["choices"][0]["message"]["content"] = true;
            JsonDocument doc;
            if (!deserializeJson(doc, resp, DeserializationOption::Filter(filter))) {
                String raw = doc["choices"][0]["message"]["content"] | "";
                // Strip markdown the model may add anyway.
                raw.replace("**", "");
                raw.replace("*", "");
                raw.replace("#", "");
                raw.replace("`", "");
                static char clean[900];
                text::clean(raw.c_str(), clean, sizeof clean);
                answer = clean;
                ok = answer.length() > 0;
                if (!ok) error = "The AI gave an empty answer.";
            } else {
                error = "The AI service sent something unexpected.";
            }
        } else {
            error = apiError(code, resp);
        }
        http.end();
    } else {
        error = "The AI address in settings doesn't look right.";
    }
    netconn::unlockHttps();
    return ok;
}

}  // namespace assistant
