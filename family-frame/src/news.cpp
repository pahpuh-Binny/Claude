#include "news.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <SD.h>
#include <WiFiClientSecure.h>

#include <memory>

#include "netconn.h"

namespace news {

static const char* CACHE_PATH = "/frame/news.json";
static const char* USER_AGENT = "Mozilla/5.0 (compatible; FamilyFrame/1.0; ESP32)";

static Feed feeds[MAX_FEEDS];
static Weather wx;
static SemaphoreHandle_t dataLock = nullptr;

// What to fetch, copied from cfg by the main loop (the task never touches cfg).
static String jobUrls[MAX_FEEDS];
static bool jobOn[MAX_FEEDS];
static int jobCount = 0;
static float jobLat = NAN, jobLon = NAN;
static bool jobFahrenheit = true;

static volatile bool wantFeeds = false, wantWeather = false, working = false, finished = false;
static volatile uint32_t lastDone = 0;

struct Lock {
    Lock() { xSemaphoreTake(dataLock, portMAX_DELAY); }
    ~Lock() { xSemaphoreGive(dataLock); }
};

static void snapshotConfig() {
    Lock l;
    jobCount = cfg.feedCount;
    for (int i = 0; i < jobCount; i++) {
        if (jobUrls[i] != cfg.feeds[i].url) feeds[i] = Feed();  // the feed changed: drop old headlines
        jobUrls[i] = cfg.feeds[i].url;
        jobOn[i] = cfg.feeds[i].enabled;
    }
    for (int i = jobCount; i < MAX_FEEDS; i++) {
        jobUrls[i] = "";
        feeds[i] = Feed();
    }
    jobLat = cfg.lat;
    jobLon = cfg.lon;
    jobFahrenheit = cfg.fahrenheit;
}

static std::unique_ptr<WiFiClient> makeClient(const String& url) {
    if (url.startsWith("https://")) {
        auto* c = new WiFiClientSecure;
        // Headlines and weather are public, so certificates aren't checked here.
        // That keeps the frame working as sites rotate their certificates.
        c->setInsecure();
        return std::unique_ptr<WiFiClient>(c);
    }
    return std::unique_ptr<WiFiClient>(new WiFiClient);
}

static int16_t fetchFeed(const String& url, Feed& out) {
    std::unique_ptr<WiFiClient> client = makeClient(url);
    HTTPClient http;
    http.useHTTP10(true);  // no chunked encoding, so the stream is plain XML
    http.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
    http.setRedirectLimit(4);
    http.setConnectTimeout(10000);
    http.setTimeout(15000);
    http.setUserAgent(USER_AGENT);
    if (!http.begin(*client, url)) return -1;
    http.addHeader("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8");
    int code = http.GET();
    if (code != 200) {
        http.end();
        return (int16_t)code;
    }
    WiFiClient* s = http.getStreamPtr();
    out.count = 0;
    rss::Parser parser(out.items, ITEMS_PER_FEED);
    char buf[256];
    size_t total = 0;
    uint32_t lastData = millis();
    while (!parser.full() && total < 800000) {
        int avail = s->available();
        if (avail > 0) {
            int n = s->readBytes(buf, avail < (int)sizeof buf ? avail : (int)sizeof buf);
            parser.put(buf, n);
            total += n;
            lastData = millis();
        } else if (!s->connected()) {
            break;
        } else if (millis() - lastData > 10000) {
            break;
        } else {
            delay(2);
        }
    }
    out.count = (uint8_t)parser.count();
    http.end();
    return 200;
}

static bool fetchWeather(float lat, float lon, bool fahrenheit, Weather& out) {
    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + String(lat, 4) + "&longitude=" + String(lon, 4) +
                 "&current=temperature_2m,weather_code,is_day"
                 "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                 "&timezone=auto&forecast_days=1&temperature_unit=" +
                 String(fahrenheit ? "fahrenheit" : "celsius");
    std::unique_ptr<WiFiClient> client = makeClient(url);
    HTTPClient http;
    http.setConnectTimeout(10000);
    http.setTimeout(15000);
    http.setUserAgent(USER_AGENT);
    if (!http.begin(*client, url)) return false;
    int code = http.GET();
    if (code != 200) {
        http.end();
        Serial.printf("Weather: HTTP %d\n", code);
        return false;
    }
    String body = http.getString();
    http.end();
    JsonDocument doc;
    if (deserializeJson(doc, body)) return false;
    JsonObject cur = doc["current"], daily = doc["daily"];
    if (cur.isNull()) return false;
    out.temp = cur["temperature_2m"] | 0.0f;
    out.code = cur["weather_code"] | 0;
    out.day = (cur["is_day"] | 1) == 1;
    out.hi = daily["temperature_2m_max"][0] | out.temp;
    out.lo = daily["temperature_2m_min"][0] | out.temp;
    out.rain = daily["precipitation_probability_max"][0] | 0;
    out.valid = true;
    out.updated = (uint32_t)time(nullptr);
    return true;
}

static void task(void*) {
    for (;;) {
        if (!(wantFeeds || wantWeather) || !netconn::online()) {
            delay(500);
            continue;
        }
        working = true;
        bool doFeeds = wantFeeds;
        wantFeeds = false;
        wantWeather = false;

        String urls[MAX_FEEDS];
        bool on[MAX_FEEDS];
        int n;
        float lat, lon;
        bool fahrenheit;
        {
            Lock l;
            n = jobCount;
            for (int i = 0; i < n; i++) {
                urls[i] = jobUrls[i];
                on[i] = jobOn[i];
            }
            lat = jobLat;
            lon = jobLon;
            fahrenheit = jobFahrenheit;
        }

        if (!isnan(lat) && netconn::lockHttps(60000)) {
            Weather w;
            if (fetchWeather(lat, lon, fahrenheit, w)) {
                Lock l;
                wx = w;
            }
            netconn::unlockHttps();
        }

        if (doFeeds) {
            static Feed fresh;  // ~1.3 KB, kept off the task stack
            for (int i = 0; i < n; i++) {
                if (!on[i]) continue;
                if (!netconn::lockHttps(60000)) continue;
                fresh = Feed();
                int16_t code = fetchFeed(urls[i], fresh);
                netconn::unlockHttps();
                Serial.printf("Feed %d: HTTP %d, %d headlines, %u KB free\n", i, code, fresh.count,
                              ESP.getFreeHeap() / 1024);
                Lock l;
                if (jobUrls[i] != urls[i]) continue;  // the feed list changed meanwhile
                feeds[i].status = code;
                if (code == 200 && fresh.count > 0) {
                    memcpy(feeds[i].items, fresh.items, sizeof fresh.items);
                    feeds[i].count = fresh.count;
                    feeds[i].updated = (uint32_t)time(nullptr);
                }
            }
            lastDone = millis();
            finished = true;
        }
        working = false;
    }
}

void begin() {
    dataLock = xSemaphoreCreateMutex();
    snapshotConfig();
    // Headlines from last time, so cards show up right away after a restart.
    File f = SD.open(CACHE_PATH, FILE_READ);
    if (f) {
        JsonDocument doc;
        if (!deserializeJson(doc, f)) {
            Lock l;
            JsonArray arr = doc["feeds"];
            for (JsonObject o : arr) {
                const char* url = o["url"] | "";
                for (int i = 0; i < jobCount; i++) {
                    if (jobUrls[i] != url) continue;
                    Feed& fd = feeds[i];
                    fd.count = 0;
                    fd.status = o["status"] | 0;
                    fd.updated = o["updated"] | 0;
                    for (JsonArray it : o["items"].as<JsonArray>()) {
                        if (fd.count >= ITEMS_PER_FEED) break;
                        rss::Item& item = fd.items[fd.count++];
                        strlcpy(item.title, it[0] | "", sizeof item.title);
                        strlcpy(item.source, it[1] | "", sizeof item.source);
                    }
                }
            }
        }
        f.close();
    }
    xTaskCreatePinnedToCore(task, "news", 16384, nullptr, 1, nullptr, 0);
}

void requestRefresh() {
    snapshotConfig();
    wantFeeds = true;
}

void requestWeather() {
    snapshotConfig();
    wantWeather = true;
}

bool busy() { return working || wantFeeds; }

uint32_t lastRefreshMs() { return lastDone; }

bool takeFinished() {
    if (!finished) return false;
    finished = false;
    return true;
}

void saveCache() {
    JsonDocument doc;
    {
        Lock l;
        JsonArray arr = doc["feeds"].to<JsonArray>();
        for (int i = 0; i < jobCount; i++) {
            if (!feeds[i].count) continue;
            JsonObject o = arr.add<JsonObject>();
            o["url"] = jobUrls[i];
            o["status"] = feeds[i].status;
            o["updated"] = feeds[i].updated;
            JsonArray items = o["items"].to<JsonArray>();
            for (int k = 0; k < feeds[i].count; k++) {
                JsonArray it = items.add<JsonArray>();
                it.add(feeds[i].items[k].title);
                it.add(feeds[i].items[k].source);
            }
        }
    }
    SD.mkdir("/frame");
    File f = SD.open(CACHE_PATH, FILE_WRITE);
    if (!f) return;
    serializeJson(doc, f);
    f.close();
}

Feed feed(int i) {
    if (i < 0 || i >= MAX_FEEDS) return Feed();
    Lock l;
    return feeds[i];
}

Weather weather() {
    Lock l;
    return wx;
}

int feedsWithItems() {
    Lock l;
    int n = 0;
    for (int i = 0; i < cfg.feedCount && i < MAX_FEEDS; i++)
        if (cfg.feeds[i].enabled && feeds[i].count) n++;
    return n;
}

String weatherText(int code) {
    switch (code) {
        case 0: return "Clear";
        case 1: return "Mostly clear";
        case 2: return "Partly cloudy";
        case 3: return "Cloudy";
        case 45: case 48: return "Foggy";
        case 51: case 53: case 55: return "Drizzle";
        case 56: case 57: return "Freezing drizzle";
        case 61: return "Light rain";
        case 63: return "Rain";
        case 65: return "Heavy rain";
        case 66: case 67: return "Freezing rain";
        case 71: return "Light snow";
        case 73: return "Snow";
        case 75: return "Heavy snow";
        case 77: return "Snow grains";
        case 80: case 81: return "Showers";
        case 82: return "Heavy showers";
        case 85: case 86: return "Snow showers";
        case 95: return "Thunderstorms";
        case 96: case 99: return "Storms with hail";
        default: return "Weather";
    }
}

String weatherSentence(bool spoken) {
    Weather w = weather();
    if (!w.valid) return "";
    String cond = weatherText(w.code);
    cond.toLowerCase();
    String unit = cfg.fahrenheit ? "F" : "C";
    if (spoken) {
        String s = "Right now it's " + String((int)lroundf(w.temp)) + " degrees and " + cond + ", with a high of " +
                   String((int)lroundf(w.hi)) + " and a low of " + String((int)lroundf(w.lo)) + ".";
        if (w.rain >= 30) s += " There's a " + String(w.rain) + " percent chance of rain.";
        return s;
    }
    String s = (cfg.place.length() ? "Weather in " + cfg.place + ": " : String("Weather: ")) +
               String((int)lroundf(w.temp)) + unit + ", " + cond + "; high " + String((int)lroundf(w.hi)) + unit +
               ", low " + String((int)lroundf(w.lo)) + unit + "; " + String(w.rain) + "% chance of rain or snow.";
    return s;
}

String contextForAssistant(int perFeed, size_t maxChars) {
    String out;
    for (int i = 0; i < cfg.feedCount; i++) {
        if (!cfg.feeds[i].enabled) continue;
        Feed f = feed(i);
        if (!f.count) continue;
        String block = "[" + cfg.feeds[i].name + "]\n";
        for (int k = 0; k < f.count && k < perFeed; k++) {
            block += "- ";
            block += f.items[k].title;
            if (f.items[k].source[0]) {
                block += " (";
                block += f.items[k].source;
                block += ")";
            }
            block += "\n";
        }
        if (out.length() + block.length() > maxChars) break;
        out += block;
    }
    return out;
}

}  // namespace news
