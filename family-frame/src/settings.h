#pragma once
// Everything the family can change, saved as /frame/config.json on the SD card.
// The web page edits it; you can also edit the file on a computer.

#include <Arduino.h>
#include <ArduinoJson.h>

constexpr int MAX_FEEDS = 12;
constexpr int BRIEFINGS = 3;

struct FeedConfig {
    String name;
    String url;
    bool enabled = true;
};

struct Settings {
    // Wi-Fi and the web page
    String wifiSsid, wifiPass;
    String hostname = "familyframe";
    String webPass;  // optional password for the web page (user name "family")

    // People and place
    String names;  // used in greetings: "Good morning, Linda and Bob!"
    String tz = "EST5EDT,M3.2.0,M11.1.0";
    float lat = NAN, lon = NAN;
    String place;
    bool fahrenheit = true;

    // Slideshow
    uint16_t slideSec = 20;
    bool shuffle = true;
    bool fade = true;
    bool clock = true;
    bool clock24 = false;
    uint8_t clockCorner = 3;  // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right
    uint8_t newsEvery = 5;    // show a news or weather card after every N photos (0 = never)
    uint8_t brightness = 80;

    // Night: the screen dims or turns off
    bool nightOn = true;
    uint16_t nightStart = 22 * 60;
    uint16_t nightEnd = 6 * 60 + 30;
    uint8_t nightBright = 0;

    // Briefings (minutes after midnight)
    uint16_t briefAt[BRIEFINGS] = {7 * 60 + 30, 12 * 60, 18 * 60};
    bool briefOn[BRIEFINGS] = {true, true, true};
    bool briefSpeak = true;
    uint16_t newsRefreshMin = 60;
    FeedConfig feeds[MAX_FEEDS];
    uint8_t feedCount = 0;

    // Voice
    bool micOn = false;  // turn on once the microphone is wired
    bool speakerOn = true;
    uint8_t volume = 70;
    uint8_t micGain = 20;  // multiplier on the raw INMP441 level
    bool speakAnswers = true;
    String lang = "en";
    String aiUrl = "https://api.groq.com/openai/v1";
    String aiModel = "llama-3.3-70b-versatile";
    String sttModel = "whisper-large-v3-turbo";
    String aiKey;

    // Display
    bool invert = false;
    bool swapRB = false;
    int16_t cal[4] = {200, 3700, 240, 3800};
    bool setupDone = false;  // touch and color check finished
};

extern Settings cfg;

namespace settings {

void setDefaultFeeds();
bool load();  // false when there's no config file yet (defaults are kept)
bool save();

// Secrets (Wi-Fi password, AI key, web password) are only written when
// includeSecrets is set; the web page gets "...Set" flags instead.
void toJson(JsonObject o, bool includeSecrets);

// Applies a (possibly partial) JSON object. From the web page, empty secrets
// mean "keep the current one". Sets *wifiChanged when SSID/password changed.
void fromJson(JsonObjectConst o, bool fromWeb, bool* wifiChanged = nullptr);

}  // namespace settings
