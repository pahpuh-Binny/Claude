// Family Frame: a photo frame with news briefings and a voice assistant for the
// ESP32-2432S028R "Cheap Yellow Display".
//
//   photos.cpp     slideshow + clock overlay      news.cpp       feeds + weather (background task)
//   screens.cpp    every screen layout             webui.cpp      the phone/computer web page
//   speaker.cpp    chimes and spoken text          assistant.cpp  microphone, speech-to-text, AI
//
// This file runs the show: what's on screen, touch, the daily schedule.

#include <Arduino.h>
#include <SD.h>
#include <SPI.h>
#include <WiFi.h>
#include <time.h>

#include "app.h"
#include "assistant.h"
#include "display.h"
#include "gfx.h"
#include "netconn.h"
#include "news.h"
#include "photos.h"
#include "pins.h"
#include "screens.h"
#include "settings.h"
#include "speaker.h"
#include "webui.h"

// HTTPS and JSON parsing run on the loop task; give it room.
SET_LOOP_TASK_STACK_SIZE(16 * 1024);

using touch::Event;

enum class Mode : uint8_t { Slides, Menu, News, AddPhotos, Hotspot };
enum class Shown : uint8_t { None, Photo, Card, Weather };

static Mode mode = Mode::Slides;
static Shown shown = Shown::None;
static int shownFeed = -1, shownPage = 0;  // the card on screen
static uint32_t shownAt = 0, modeAt = 0;
static int photosSinceCard = 0, cardCursor = -1, welcomeCountdown = 0;
static int cardPage[MAX_FEEDS] = {};
static int newsFeed = 0, newsPage = 0;
static uint32_t wakeUntil = 0;  // a tap at night lights the screen for a while
static int lastMinute = -1;
static int briefDoneDay[BRIEFINGS] = {-1, -1, -1};
static uint32_t lastRefreshAsk = 0, lastWeatherAsk = 0;
static bool sdOk = false;

// ---------------- requests from the web page ----------------

static struct {
    volatile bool briefing = false, micTest = false, speakerTest = false, touchSetup = false, reboot = false;
    volatile bool photosChanged = false, settingsChanged = false, wifiChanged = false;
    String showPhoto, say, uploaded;
    uint32_t uploadedAt = 0;
} pending;

namespace app {
void requestBriefing() { pending.briefing = true; }
void requestShowPhoto(const String& name) { pending.showPhoto = name; }
void photoUploaded(const String& name) {
    photos::added(name);
    pending.uploaded = name;
    pending.uploadedAt = millis();
}
void photosChanged() { pending.photosChanged = true; }
void requestSay(const String& text) { pending.say = text; }
void requestMicTest() { pending.micTest = true; }
void requestSpeakerTest() { pending.speakerTest = true; }
void requestTouchSetup() { pending.touchSetup = true; }
void requestReboot() { pending.reboot = true; }
void settingsChanged(bool wifiChanged) {
    pending.settingsChanged = true;
    if (wifiChanged) pending.wifiChanged = true;
}
}  // namespace app

// ---------------- time and brightness ----------------

static int minuteOfDay(struct tm* out = nullptr) {
    if (!netconn::timeValid()) return -1;
    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    if (out) *out = t;
    return t.tm_hour * 60 + t.tm_min;
}

static bool isNight() {
    int m = minuteOfDay();
    if (!cfg.nightOn || m < 0) return false;
    int s = cfg.nightStart, e = cfg.nightEnd;
    return s < e ? (m >= s && m < e) : (m >= s || m < e);
}

static uint8_t targetBrightness() {
    if (isNight() && millis() > wakeUntil) return cfg.nightBright;
    return cfg.brightness;
}

static bool screenAsleep() { return targetBrightness() == 0; }

static void fadeOut() {
    if (cfg.fade && disp::backlightLevel() > 0) disp::fadeTo(0, 160);
}

static void fadeIn() {
    uint8_t t = targetBrightness();
    if (cfg.fade) disp::fadeTo(t, 220);
    else disp::backlight(t);
}

// ---------------- waiting while staying responsive ----------------

// Keeps the web page and Wi-Fi alive. Returns the touch event that ended the
// wait, or NONE after ms.
static Event waitForTouch(uint32_t ms) {
    uint32_t start = millis();
    while (millis() - start < ms) {
        webui::loop();
        netconn::loop();
        Event e = touch::poll();
        if (e.type != Event::NONE) return e;
        delay(5);
    }
    return Event();
}

static Event stopEvent;
static bool stopOnTouch() {
    Event e = touch::poll();
    if (e.type == Event::NONE) return false;
    stopEvent = e;
    return true;
}

// ---------------- the slideshow ----------------

static void showWelcome() {
    mode = Mode::AddPhotos;
    modeAt = millis();
    shown = Shown::None;
    fadeOut();
    screens::addPhotos(true);
    fadeIn();
}

// Next weather or news card in rotation. False if there's nothing to show.
static bool showNextCard() {
    int slots = cfg.feedCount + 1;  // slot 0 is the weather
    for (int tries = 0; tries < slots; tries++) {
        cardCursor = (cardCursor + 1) % slots;
        if (cardCursor == 0) {
            if (!news::weather().valid) continue;
            fadeOut();
            screens::weatherCard();
            fadeIn();
            shown = Shown::Weather;
            return true;
        }
        int f = cardCursor - 1;
        if (!cfg.feeds[f].enabled) continue;
        int pages = screens::newsPages(f);
        if (!pages) continue;
        int page = cardPage[f] % pages;
        cardPage[f] = (page + 1) % pages;  // next time, the next two headlines
        fadeOut();
        screens::newsCard(f, page, false);
        fadeIn();
        shown = Shown::Card;
        shownFeed = f;
        shownPage = page;
        return true;
    }
    return false;
}

static void showNextItem(int dir = 1) {
    mode = Mode::Slides;
    shownAt = millis();
    bool havePhotos = photos::count() > 0;
    if (!havePhotos) {
        // No photos yet: alternate the "how to add photos" screen with news cards.
        if (welcomeCountdown-- <= 0 || !showNextCard()) {
            welcomeCountdown = 3;
            showWelcome();
        }
        return;
    }
    bool cardTime = dir > 0 && cfg.newsEvery > 0 && photosSinceCard >= cfg.newsEvery;
    if (cardTime && showNextCard()) {
        photosSinceCard = 0;
        return;
    }
    fadeOut();
    bool ok = dir > 0 ? photos::showNext() : photos::showPrev();
    fadeIn();
    if (ok) {
        shown = Shown::Photo;
        photosSinceCard++;
        return;
    }
    if (!showNextCard()) {
        screens::message("Photos can't be shown", "None of the photos on the SD card could be opened. Add photos "
                         "from your phone and they'll be converted automatically.", gfx::theme.warn);
        shown = Shown::None;
    }
}

// Redraws whatever the slideshow had on screen (after the menu was over it).
static void redrawShown() {
    mode = Mode::Slides;
    switch (shown) {
        case Shown::Photo:
            if (photos::show(photos::currentName())) return;
            break;
        case Shown::Card:
            if (screens::newsCard(shownFeed, shownPage, false)) return;
            break;
        case Shown::Weather:
            if (screens::weatherCard()) return;
            break;
        default:
            break;
    }
    showNextItem();
}

static void backToSlides() {
    touch::flush();
    shown = Shown::None;
    showNextItem();
}

// ---------------- interactive news ----------------

static bool drawNews() { return screens::newsCard(newsFeed, newsPage, true); }

// Moves to the next (dir 1) or previous (dir -1) page, crossing into other feeds.
static bool stepNews(int dir) {
    int pages = screens::newsPages(newsFeed);
    if (dir > 0 && newsPage + 1 < pages) {
        newsPage++;
        return drawNews();
    }
    if (dir < 0 && newsPage > 0) {
        newsPage--;
        return drawNews();
    }
    for (int i = 1; i <= cfg.feedCount; i++) {
        int f = ((newsFeed + dir * i) % cfg.feedCount + cfg.feedCount) % cfg.feedCount;
        int p = screens::newsPages(f);
        if (!cfg.feeds[f].enabled || !p) continue;
        newsFeed = f;
        newsPage = dir > 0 ? 0 : p - 1;
        return drawNews();
    }
    return false;
}

static void openNews() {
    if (news::feedsWithItems() == 0) {
        screens::message("No news yet", news::busy() ? "The frame is fetching headlines right now. Try again in a "
                                                       "minute."
                                                     : "No headlines have arrived. Check the feeds on the settings "
                                                       "page (News tab).",
                         gfx::theme.warn);
        waitForTouch(8000);
        backToSlides();
        return;
    }
    mode = Mode::News;
    modeAt = millis();
    if (newsFeed >= cfg.feedCount || !cfg.feeds[newsFeed].enabled || !screens::newsCard(newsFeed, newsPage, true)) {
        newsFeed = cfg.feedCount - 1;
        newsPage = 0;
        stepNews(1);
    }
}

static String headlinesToSay(int feed, int first, int count) {
    news::Feed f = news::feed(feed);
    String s = cfg.feeds[feed].name + ". ";
    for (int i = first; i < first + count && i < f.count; i++) {
        s += f.items[i].title;
        s += ". ";
    }
    return s;
}

// ---------------- briefings ----------------

static void runBriefing() {
    bool speak = cfg.briefSpeak && speaker::enabled() && !isNight();
    wakeUntil = millis() + 10 * 60000UL;
    news::requestRefresh();
    lastRefreshAsk = millis();
    fadeOut();
    screens::greeting(true);
    fadeIn();
    if (speak) speaker::chime();

    // Wait for fresh headlines (up to a minute). Holding the screen cancels.
    uint32_t start = millis();
    while (netconn::online() && news::busy() && millis() - start < 60000) {
        Event e = waitForTouch(200);
        if (e.type == Event::HOLD) return backToSlides();
    }
    if (news::takeFinished()) news::saveCache();
    screens::greeting(false);

    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    char date[40];
    strftime(date, sizeof date, "%A, %B ", &t);
    String part = t.tm_hour < 12 ? "Good morning" : t.tm_hour < 17 ? "Good afternoon" : "Good evening";
    String intro = part + (cfg.names.length() ? ", " + cfg.names : String("")) + ". It's " + date +
                   String(t.tm_mday) + ". " + news::weatherSentence(true) + " Here's your news.";
    Event e;
    if (speak && speaker::say(intro, stopOnTouch)) {
        e = speaker::wasStopped() ? stopEvent : waitForTouch(1500);
    } else {
        e = waitForTouch(9000);
    }
    if (e.type == Event::HOLD) return backToSlides();

    for (int f = 0; f < cfg.feedCount; f++) {
        if (!cfg.feeds[f].enabled || !screens::newsCard(f, 0, false)) continue;
        e = Event();
        if (speak && speaker::say(headlinesToSay(f, 0, 2), stopOnTouch)) {
            e = speaker::wasStopped() ? stopEvent : waitForTouch(3000);
        } else {
            e = waitForTouch(14000);
        }
        if (e.type == Event::HOLD) break;
        touch::waitRelease(800);
    }
    backToSlides();
}

// ---------------- voice ----------------

static void showProblem(const String& title, const String& body) {
    screens::message(title, body, gfx::theme.warn);
    waitForTouch(15000);
    backToSlides();
}

static void runAsk() {
    String why;
    if (!assistant::ready(why)) return showProblem("Assistant", why);
    wakeUntil = millis() + 5 * 60000UL;
    disp::backlight(cfg.brightness);
    screens::listening();
    touch::waitRelease(1500);
    speaker::listenBeep();
    disp::led(false, false, true);
    assistant::Recording rec = assistant::record(
        assistant::RECORDING_PATH, 10000, true, [](int lvl) { screens::listeningLevel(lvl); }, stopOnTouch);
    disp::led(false, false, false);
    if (!rec.ok) return showProblem("Microphone", "The microphone couldn't be started. Check the wiring guide.");
    if (!rec.heardSpeech && rec.ms < 1500)
        return showProblem("I didn't hear anything", "Hold the Ask button or the screen, wait for the beep, then speak "
                                                     "toward the frame.");
    speaker::doneBeep();
    screens::thinking("");
    String question, answer, err;
    if (!assistant::transcribe(assistant::RECORDING_PATH, question, err)) return showProblem("Sorry", err);
    if (question.length() < 2) return showProblem("I didn't catch that", "Try again, a little closer to the frame.");
    screens::thinking(question);

    String q = question;
    q.toLowerCase();
    if (q.indexOf("briefing") >= 0) return runBriefing();

    if (!assistant::ask(question, answer, err)) {
        screens::message("Sorry", err, gfx::theme.bad);
        waitForTouch(15000);
        return backToSlides();
    }
    screens::answer(question, answer);
    if (cfg.speakAnswers) speaker::say(answer, stopOnTouch);
    waitForTouch(30000);
    backToSlides();
}

static void runMicTest() {
    if (!cfg.micOn) {
        return showProblem("Microphone is off", "Wire the microphone (see the wiring guide), then turn on "
                                                "\"Microphone\" in the settings page's Assistant tab.");
    }
    disp::backlight(cfg.brightness);
    screens::listening();
    gfx::text(tft, "Mic test: say something for 4 seconds", SCREEN_W / 2, 150, gfx::SMALLB, gfx::theme.accent,
              TC_DATUM);
    touch::waitRelease(1500);
    assistant::Recording rec = assistant::record(
        assistant::RECORDING_PATH, 4500, false, [](int lvl) { screens::listeningLevel(lvl); }, nullptr);
    if (!rec.ok) return showProblem("Microphone", "The microphone couldn't be started.");
    int pct = rec.peak * 100 / 32767;
    String body = "Loudest: " + String(pct) + "%   Background: " + String(rec.noise) + "\n";
    if (rec.peak < 600) body += "That's very quiet. Check the wiring (SD to IO35, SCK to IO22, WS to IO27).";
    else if (pct > 95) body += "Too loud: lower Mic gain on the settings page.";
    else if (pct < 15) body += "A bit quiet: raise Mic gain on the settings page.";
    else body += "Sounds good!";
    if (speaker::enabled()) body += " Playing it back...";
    screens::message("Microphone test", body, rec.peak < 600 ? gfx::theme.bad : gfx::theme.good);
    speaker::playWav(assistant::RECORDING_PATH, stopOnTouch);
    waitForTouch(12000);
    backToSlides();
}

static void runSpeakerTest() {
    screens::message("Speaker test", "You should hear a chime and then a voice.", gfx::theme.accent);
    speaker::chime();
    if (!speaker::say("Hello! The speaker is working. This is your family frame.", stopOnTouch)) {
        gfx::text(tft, speaker::enabled() ? "The voice needs internet." : "The speaker is off in settings.", 36, 170,
                  gfx::SMALL, gfx::theme.warn);
    }
    waitForTouch(4000);
    backToSlides();
}

// ---------------- first start: touch and colors ----------------

static void readTouchPoint(int sx, int sy, int step, int16_t& rx, int16_t& ry) {
    for (;;) {
        screens::calibrationTarget(sx, sy, step);
        touch::waitRelease(5000);
        int16_t x, y;
        while (!touch::raw(x, y)) delay(10);
        long ax = 0, ay = 0;
        int n = 0;
        for (int i = 0; i < 20 && n < 8; i++) {
            if (touch::raw(x, y)) {
                ax += x;
                ay += y;
                n++;
            }
            delay(10);
        }
        if (n >= 4) {
            rx = (int16_t)(ax / n);
            ry = (int16_t)(ay / n);
            touch::waitRelease(5000);
            return;
        }
    }
}

static void runTouchSetup() {
    for (int attempt = 0; attempt < 3; attempt++) {
        int16_t x1, y1, x2, y2;
        readTouchPoint(20, 20, 1, x1, y1);
        readTouchPoint(300, 220, 2, x2, y2);
        if (abs(x2 - x1) < 500 || abs(y2 - y1) < 500) continue;  // a slip: try again
        // Extend the two measured points out to the screen edges.
        cfg.cal[0] = x1 - (x2 - x1) * 20 / 280;
        cfg.cal[1] = x2 + (x2 - x1) * 20 / 280;
        cfg.cal[2] = y1 - (y2 - y1) * 20 / 200;
        cfg.cal[3] = y2 + (y2 - y1) * 20 / 200;
        break;
    }
    screens::colorCheck();
    for (;;) {
        Event e = waitForTouch(60000);
        if (e.type != Event::TAP) continue;
        screens::ColorButton b = screens::colorHit(e.x, e.y);
        if (b == screens::COLOR_INVERT) {
            cfg.invert = !cfg.invert;
            disp::applyColorSettings();
        } else if (b == screens::COLOR_SWAP) {
            cfg.swapRB = !cfg.swapRB;
            gfx::loadTheme();
            screens::colorCheck();
        } else if (b == screens::COLOR_OK) {
            break;
        }
    }
    cfg.setupDone = true;
    settings::save();
    touch::waitRelease();
}

// ---------------- touch per mode ----------------

static void onTouch(const Event& e) {
    // At night with the screen off, the first tap just wakes it.
    if (screenAsleep() || disp::backlightLevel() == 0) {
        wakeUntil = millis() + 2 * 60000UL;
        disp::fadeTo(cfg.brightness, 300);
        return;
    }
    if (isNight()) wakeUntil = millis() + 2 * 60000UL;

    switch (mode) {
        case Mode::Slides:
            if (e.type == Event::HOLD) return runAsk();
            screens::menuBar(cfg.micOn && cfg.aiKey.length());
            mode = Mode::Menu;
            modeAt = millis();
            return;

        case Mode::Menu:
            if (e.type != Event::TAP) return;
            switch (screens::menuHit(e.x, e.y)) {
                case screens::MENU_OUTSIDE: return redrawShown();
                case screens::MENU_BACK: return showNextItem(-1);
                case screens::MENU_NEXT: return showNextItem(1);
                case screens::MENU_NEWS: return openNews();
                case screens::MENU_ASK: return runAsk();
                case screens::MENU_ADD:
                    mode = Mode::AddPhotos;
                    modeAt = millis();
                    screens::addPhotos(photos::count() == 0);
                    return;
            }
            return;

        case Mode::News:
            if (e.type != Event::TAP) return;
            modeAt = millis();
            switch (screens::newsHit(e.x, e.y)) {
                case screens::NEWS_PREV: stepNews(-1); return;
                case screens::NEWS_NEXT: stepNews(1); return;
                case screens::NEWS_CLOSE: return backToSlides();
                case screens::NEWS_READ:
                    if (!speaker::say(headlinesToSay(newsFeed, newsPage * 2, 2), stopOnTouch)) {
                        gfx::text(tft, speaker::enabled() ? "Needs internet" : "Speaker is off", 160, 180, gfx::SMALL,
                                  gfx::theme.warn, TC_DATUM);
                    }
                    modeAt = millis();
                    return;
                default:
                    stepNews(e.x < SCREEN_W / 2 ? -1 : 1);  // tapping a headline side also pages
                    return;
            }

        case Mode::AddPhotos:
            if (e.type != Event::TAP) return;
            switch (screens::addPhotosHit(e.x, e.y)) {
                case screens::ADD_SPEAKER_TEST: return runSpeakerTest();
                case screens::ADD_MIC_TEST: return runMicTest();
                case screens::ADD_CLOSE: return backToSlides();
                default: return;
            }

        default:
            return;
    }
}

// ---------------- once a second ----------------

static void everySecond() {
    // Brightness follows the night schedule.
    uint8_t want = targetBrightness();
    if (disp::backlightLevel() != want) disp::fadeTo(want, 400);

    // Headlines and weather.
    if (netconn::online()) {
        uint32_t refreshMs = (uint32_t)cfg.newsRefreshMin * 60000UL;
        if (!lastRefreshAsk || millis() - lastRefreshAsk > refreshMs) {
            lastRefreshAsk = millis();
            lastWeatherAsk = millis();
            news::requestRefresh();
        } else if (!isnan(cfg.lat) && millis() - lastWeatherAsk > 30 * 60000UL) {
            lastWeatherAsk = millis();
            news::requestWeather();
        }
    }
    if (news::takeFinished()) news::saveCache();

    struct tm t;
    int m = minuteOfDay(&t);
    if (m < 0 || m == lastMinute) return;
    lastMinute = m;
    if (mode == Mode::Slides && shown == Shown::Photo && !screenAsleep()) photos::drawOverlay();
    for (int i = 0; i < BRIEFINGS; i++) {
        if (cfg.briefOn[i] && cfg.briefAt[i] == m && briefDoneDay[i] != t.tm_yday) {
            briefDoneDay[i] = t.tm_yday;
            pending.briefing = true;
        }
    }
}

static void handlePending() {
    if (pending.reboot) {
        delay(300);
        ESP.restart();
    }
    if (pending.settingsChanged) {
        pending.settingsChanged = false;
        disp::applyColorSettings();
        gfx::loadTheme();
        netconn::applyTimezone();
        lastMinute = -1;
        news::requestRefresh();
        lastRefreshAsk = millis();
        if (pending.wifiChanged) {
            screens::message("Wi-Fi saved", "Restarting to join " + cfg.wifiSsid + "...", gfx::theme.good);
            delay(1500);
            ESP.restart();
        }
        if (mode == Mode::Slides) redrawShown();
    }
    if (pending.touchSetup) {
        pending.touchSetup = false;
        runTouchSetup();
        backToSlides();
    }
    if (pending.photosChanged) {
        pending.photosChanged = false;
        photos::rescan();
        if (photos::count() == 0 || mode == Mode::AddPhotos) backToSlides();
    }
    // Show the newest upload once the phone has finished sending a batch. Uploads
    // are slotted in right after the current photo, so "next" is the newest.
    if (pending.uploaded.length() && millis() - pending.uploadedAt > 2500) {
        pending.uploaded = "";
        wakeUntil = millis() + 2 * 60000UL;
        photosSinceCard = 0;
        showNextItem();
    }
    if (pending.showPhoto.length()) {
        String name = pending.showPhoto;
        pending.showPhoto = "";
        wakeUntil = millis() + 2 * 60000UL;
        fadeOut();
        bool ok = photos::show(name);
        fadeIn();
        if (ok) {
            mode = Mode::Slides;
            shown = Shown::Photo;
            shownAt = millis();
        } else {
            backToSlides();
        }
    }
    if (pending.briefing) {
        pending.briefing = false;
        runBriefing();
    }
    if (pending.say.length()) {
        String s = pending.say;
        pending.say = "";
        speaker::say(s, stopOnTouch);
    }
    if (pending.micTest) {
        pending.micTest = false;
        runMicTest();
    }
    if (pending.speakerTest) {
        pending.speakerTest = false;
        runSpeakerTest();
    }
}

// ---------------- setup / loop ----------------

void setup() {
    Serial.begin(115200);
    pinMode(pins::BOOT_BUTTON, INPUT_PULLUP);
    touch::begin();

    SPI.begin(pins::SD_SCK, pins::SD_MISO, pins::SD_MOSI, pins::SD_CS);
    sdOk = SD.begin(pins::SD_CS, SPI, 20000000) || SD.begin(pins::SD_CS, SPI, 4000000);
    bool haveConfig = sdOk && settings::load();
    if (!sdOk) settings::setDefaultFeeds();

    disp::begin();
    gfx::loadTheme();
    screens::boot(sdOk ? "Starting up..." : "No SD card found");
    disp::fadeTo(cfg.brightness, 300);

    if (!sdOk) {
        screens::message("Insert a microSD card",
                         "Family Frame keeps photos and settings on a microSD card (FAT32, up to 32 GB). Switch off, "
                         "insert a card, and switch on again.",
                         gfx::theme.warn);
        for (;;) delay(1000);
    }
    if (!haveConfig) settings::save();  // writes /frame/config.json with the defaults

    // Hold BOOT while starting for touch + color setup; hold the screen for Wi-Fi setup.
    bool bootHeld = digitalRead(pins::BOOT_BUTTON) == LOW;
    bool screenHeld = touch::down();
    if (!cfg.setupDone || bootHeld) runTouchSetup();

    photos::begin();
    news::begin();

    if (cfg.wifiSsid.isEmpty() || (screenHeld && !bootHeld)) {
        netconn::startSetupHotspot();
        mode = Mode::Hotspot;
        webui::begin();
        screens::setupHotspot();
        return;
    }
    screens::boot("Connecting to " + cfg.wifiSsid + "...");
    if (!netconn::connect(20000)) {
        // Keep going: photos work offline and Wi-Fi keeps retrying in the background.
        screens::message("Can't reach Wi-Fi", "Couldn't join \"" + cfg.wifiSsid + "\". The slideshow will start and "
                         "keep trying. To change networks, hold the screen while switching on.", gfx::theme.warn);
        delay(5000);
    } else {
        // Give the clock a few seconds to set itself.
        uint32_t start = millis();
        while (!netconn::timeValid() && millis() - start < 8000) delay(100);
        news::requestRefresh();
        lastRefreshAsk = lastWeatherAsk = millis();
    }
    webui::begin();
    touch::flush();
    showNextItem();
    Serial.printf("Family Frame %s ready at %s, %u KB free\n", FW_VERSION, netconn::url().c_str(),
                  ESP.getFreeHeap() / 1024);
}

void loop() {
    webui::loop();
    netconn::loop();

    if (mode == Mode::Hotspot) {
        Event e = touch::poll();
        if (e.type == Event::TAP) screens::setupHotspot();
        if (pending.reboot || pending.wifiChanged) {
            screens::message("Wi-Fi saved", "Restarting to join " + cfg.wifiSsid + "...", gfx::theme.good);
            delay(1500);
            ESP.restart();
        }
        delay(5);
        return;
    }

    Event e = touch::poll();
    if (e.type != Event::NONE) onTouch(e);

    handlePending();

    static uint32_t lastTick = 0;
    if (millis() - lastTick >= 1000) {
        lastTick = millis();
        everySecond();
    }

    uint32_t now = millis();
    switch (mode) {
        case Mode::Slides:
            if (!screenAsleep() && now - shownAt >= (uint32_t)cfg.slideSec * 1000UL) showNextItem();
            break;
        case Mode::Menu:
            if (now - modeAt > 8000) redrawShown();
            break;
        case Mode::News:
            if (now - modeAt > 120000) backToSlides();
            break;
        case Mode::AddPhotos:
            // The welcome screen (no photos yet) rotates with news; the menu's version times out.
            if (now - modeAt > (photos::count() ? 120000UL : 30000UL)) backToSlides();
            break;
        default:
            break;
    }
    delay(5);
}
