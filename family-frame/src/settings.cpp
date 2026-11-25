#include "settings.h"

#include <SD.h>

#include "textutil.h"

Settings cfg;

namespace settings {

static const char* CONFIG_PATH = "/frame/config.json";

#define GN_SEARCH(q) "https://news.google.com/rss/search?q=" q "&hl=en-US&gl=US&ceid=US:en"

// Starting feeds. Any RSS or Atom link works, and the web page has more presets.
static const char* const DEFAULT_FEEDS[][2] = {
    {"Top Stories", "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en"},
    {"Washington Post", "https://feeds.washingtonpost.com/rss/national"},
    {"Tech News", "https://feeds.arstechnica.com/arstechnica/index"},
    {"Amazon Deals", "https://slickdeals.net/newsearch.php?q=amazon&searcharea=deals&searchin=first&rss=1"},
    {"Contest of Champions", GN_SEARCH("%22Contest+of+Champions%22+when:14d")},
    {"Marvel Movies", GN_SEARCH("Marvel+Studios+movie+when:7d")},
};

void setDefaultFeeds() {
    cfg.feedCount = 0;
    for (auto& f : DEFAULT_FEEDS) {
        FeedConfig& fc = cfg.feeds[cfg.feedCount++];
        fc.name = f[0];
        fc.url = f[1];
        fc.enabled = true;
    }
}

static String hhmm(uint16_t minutes) {
    char buf[6];
    text::formatHHMM(minutes, buf);
    return buf;
}

static void readTime(JsonVariantConst v, uint16_t& dst) {
    uint16_t m;
    if (v.is<const char*>() && text::parseHHMM(v.as<const char*>(), &m)) dst = m;
}

template <typename T>
static void readNum(JsonVariantConst v, T& dst, long lo, long hi) {
    if (!v.is<float>() && !v.is<long>()) return;
    long x = v.as<long>();
    if (x < lo) x = lo;
    if (x > hi) x = hi;
    dst = (T)x;
}

static void readBool(JsonVariantConst v, bool& dst) {
    if (v.is<bool>()) dst = v.as<bool>();
}

static void readStr(JsonVariantConst v, String& dst) {
    if (v.is<const char*>()) dst = v.as<const char*>();
}

void toJson(JsonObject o, bool includeSecrets) {
    o["wifiSsid"] = cfg.wifiSsid;
    o["hostname"] = cfg.hostname;
    o["names"] = cfg.names;
    o["tz"] = cfg.tz;
    if (!isnan(cfg.lat)) {
        o["lat"] = cfg.lat;
        o["lon"] = cfg.lon;
    }
    o["place"] = cfg.place;
    o["fahrenheit"] = cfg.fahrenheit;

    o["slideSec"] = cfg.slideSec;
    o["shuffle"] = cfg.shuffle;
    o["fade"] = cfg.fade;
    o["clock"] = cfg.clock;
    o["clock24"] = cfg.clock24;
    o["clockCorner"] = cfg.clockCorner;
    o["newsEvery"] = cfg.newsEvery;
    o["brightness"] = cfg.brightness;

    o["nightOn"] = cfg.nightOn;
    o["nightStart"] = hhmm(cfg.nightStart);
    o["nightEnd"] = hhmm(cfg.nightEnd);
    o["nightBright"] = cfg.nightBright;

    JsonArray briefs = o["briefings"].to<JsonArray>();
    for (int i = 0; i < BRIEFINGS; i++) {
        JsonObject b = briefs.add<JsonObject>();
        b["at"] = hhmm(cfg.briefAt[i]);
        b["on"] = cfg.briefOn[i];
    }
    o["briefSpeak"] = cfg.briefSpeak;
    o["newsRefreshMin"] = cfg.newsRefreshMin;
    JsonArray feeds = o["feeds"].to<JsonArray>();
    for (int i = 0; i < cfg.feedCount; i++) {
        JsonObject f = feeds.add<JsonObject>();
        f["name"] = cfg.feeds[i].name;
        f["url"] = cfg.feeds[i].url;
        f["on"] = cfg.feeds[i].enabled;
    }

    o["micOn"] = cfg.micOn;
    o["speakerOn"] = cfg.speakerOn;
    o["volume"] = cfg.volume;
    o["micGain"] = cfg.micGain;
    o["speakAnswers"] = cfg.speakAnswers;
    o["lang"] = cfg.lang;
    o["aiUrl"] = cfg.aiUrl;
    o["aiModel"] = cfg.aiModel;
    o["sttModel"] = cfg.sttModel;

    o["invert"] = cfg.invert;
    o["swapRB"] = cfg.swapRB;
    JsonArray cal = o["touchCal"].to<JsonArray>();
    for (int16_t v : cfg.cal) cal.add(v);
    o["setupDone"] = cfg.setupDone;

    if (includeSecrets) {
        o["wifiPass"] = cfg.wifiPass;
        o["webPass"] = cfg.webPass;
        o["aiKey"] = cfg.aiKey;
    } else {
        o["wifiPassSet"] = cfg.wifiPass.length() > 0;
        o["webPassSet"] = cfg.webPass.length() > 0;
        o["aiKeySet"] = cfg.aiKey.length() > 0;
    }
}

void fromJson(JsonObjectConst o, bool fromWeb, bool* wifiChanged) {
    String oldSsid = cfg.wifiSsid, oldPass = cfg.wifiPass;

    readStr(o["wifiSsid"], cfg.wifiSsid);
    readStr(o["hostname"], cfg.hostname);
    readStr(o["names"], cfg.names);
    readStr(o["tz"], cfg.tz);
    if (o["lat"].is<float>() && o["lon"].is<float>()) {
        cfg.lat = o["lat"].as<float>();
        cfg.lon = o["lon"].as<float>();
    }
    if (o["clearLocation"] | false) cfg.lat = cfg.lon = NAN;
    readStr(o["place"], cfg.place);
    readBool(o["fahrenheit"], cfg.fahrenheit);

    readNum(o["slideSec"], cfg.slideSec, 5, 3600);
    readBool(o["shuffle"], cfg.shuffle);
    readBool(o["fade"], cfg.fade);
    readBool(o["clock"], cfg.clock);
    readBool(o["clock24"], cfg.clock24);
    readNum(o["clockCorner"], cfg.clockCorner, 0, 3);
    readNum(o["newsEvery"], cfg.newsEvery, 0, 50);
    readNum(o["brightness"], cfg.brightness, 5, 100);

    readBool(o["nightOn"], cfg.nightOn);
    readTime(o["nightStart"], cfg.nightStart);
    readTime(o["nightEnd"], cfg.nightEnd);
    readNum(o["nightBright"], cfg.nightBright, 0, 100);

    JsonArrayConst briefs = o["briefings"];
    for (int i = 0; i < BRIEFINGS && i < (int)briefs.size(); i++) {
        readTime(briefs[i]["at"], cfg.briefAt[i]);
        readBool(briefs[i]["on"], cfg.briefOn[i]);
    }
    readBool(o["briefSpeak"], cfg.briefSpeak);
    readNum(o["newsRefreshMin"], cfg.newsRefreshMin, 15, 24 * 60);
    if (o["feeds"].is<JsonArrayConst>()) {
        JsonArrayConst feeds = o["feeds"];
        cfg.feedCount = 0;
        for (JsonObjectConst f : feeds) {
            if (cfg.feedCount >= MAX_FEEDS) break;
            String url = f["url"] | "";
            url.trim();
            if (!url.startsWith("http")) continue;
            FeedConfig& fc = cfg.feeds[cfg.feedCount++];
            fc.url = url;
            fc.name = f["name"] | "News";
            fc.name.trim();
            if (fc.name.isEmpty()) fc.name = "News";
            fc.enabled = f["on"] | true;
        }
    }

    readBool(o["micOn"], cfg.micOn);
    readBool(o["speakerOn"], cfg.speakerOn);
    readNum(o["volume"], cfg.volume, 0, 100);
    readNum(o["micGain"], cfg.micGain, 1, 32);
    readBool(o["speakAnswers"], cfg.speakAnswers);
    readStr(o["lang"], cfg.lang);
    readStr(o["aiUrl"], cfg.aiUrl);
    while (cfg.aiUrl.endsWith("/")) cfg.aiUrl.remove(cfg.aiUrl.length() - 1);
    readStr(o["aiModel"], cfg.aiModel);
    readStr(o["sttModel"], cfg.sttModel);

    readBool(o["invert"], cfg.invert);
    readBool(o["swapRB"], cfg.swapRB);
    JsonArrayConst cal = o["touchCal"];
    if (cal.size() == 4) {
        for (int i = 0; i < 4; i++) cfg.cal[i] = cal[i] | cfg.cal[i];
    }
    readBool(o["setupDone"], cfg.setupDone);

    // Secrets: from the web page an empty value means "unchanged".
    auto secret = [&](const char* key, String& dst) {
        JsonVariantConst v = o[key];
        if (!v.is<const char*>()) return;
        String s = v.as<const char*>();
        if (fromWeb && s.isEmpty()) return;
        dst = s;
    };
    secret("wifiPass", cfg.wifiPass);
    secret("webPass", cfg.webPass);
    secret("aiKey", cfg.aiKey);
    cfg.aiKey.trim();
    if (fromWeb && (o["clearWebPass"] | false)) cfg.webPass = "";
    if (fromWeb && (o["clearAiKey"] | false)) cfg.aiKey = "";

    if (wifiChanged) *wifiChanged = (cfg.wifiSsid != oldSsid) || (cfg.wifiPass != oldPass);
}

bool load() {
    setDefaultFeeds();
    File f = SD.open(CONFIG_PATH, FILE_READ);
    if (!f) return false;
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, f);
    f.close();
    if (err) {
        Serial.printf("config.json: %s (using defaults)\n", err.c_str());
        return false;
    }
    fromJson(doc.as<JsonObjectConst>(), false);
    return true;
}

bool save() {
    JsonDocument doc;
    toJson(doc.to<JsonObject>(), true);
    SD.mkdir("/frame");
    // Write a temporary file first so a power cut never leaves a broken config.
    const char* tmp = "/frame/config.tmp";
    File f = SD.open(tmp, FILE_WRITE);
    if (!f) return false;
    size_t n = serializeJsonPretty(doc, f);
    f.close();
    if (n == 0) return false;
    SD.remove(CONFIG_PATH);
    return SD.rename(tmp, CONFIG_PATH);
}

}  // namespace settings
