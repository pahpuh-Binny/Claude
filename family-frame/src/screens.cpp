#include "screens.h"

#include <time.h>

#include "display.h"
#include "gfx.h"
#include "netconn.h"
#include "news.h"
#include "settings.h"

using namespace gfx;

namespace screens {

static const int LINE_M = 24;  // line height for 12 pt text
static const int LINE_S = 19;  // line height for 9 pt text

String clockText() {
    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    char buf[12];
    if (cfg.clock24) {
        snprintf(buf, sizeof buf, "%d:%02d", t.tm_hour, t.tm_min);
    } else {
        snprintf(buf, sizeof buf, "%d:%02d %s", t.tm_hour % 12 ? t.tm_hour % 12 : 12, t.tm_min,
                 t.tm_hour < 12 ? "AM" : "PM");
    }
    return buf;
}

static String dateText(bool withYear = false) {
    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    char buf[40];
    strftime(buf, sizeof buf, "%A, %B ", &t);
    String s = String(buf) + String(t.tm_mday);
    if (withYear) s += ", " + String(t.tm_year + 1900);
    return s;
}

static void header(const String& title, uint16_t accent, const String& right = "") {
    tft.fillRect(0, 0, SCREEN_W, 30, theme.card);
    tft.fillRect(0, 0, 6, 30, accent);
    String r = right.length() ? right : (netconn::timeValid() ? clockText() : String(""));
    int rw = 0;
    if (r.length()) {
        tft.setFreeFont(SMALL);
        rw = tft.textWidth(r) + 12;
        text(tft, r, SCREEN_W - 10, 7, SMALL, theme.dim, TR_DATUM);
    }
    text(tft, fit(tft, title, SCREEN_W - 26 - rw, SMALLB), 14, 7, SMALLB, accent);
}

void boot(const String& line) {
    tft.fillScreen(theme.bg);
    text(tft, "Family Frame", SCREEN_W / 2, 92, BIGB, theme.accent, TC_DATUM);
    text(tft, line, SCREEN_W / 2, 146, SMALL, theme.dim, TC_DATUM);
}

void message(const String& title, const String& body, uint16_t accent) {
    tft.fillScreen(theme.bg);
    tft.fillRoundRect(14, 30, SCREEN_W - 28, SCREEN_H - 60, 12, theme.card);
    tft.fillRoundRect(14, 30, 8, SCREEN_H - 60, 4, accent);
    text(tft, fit(tft, title, SCREEN_W - 70, MEDB), 36, 46, MEDB, accent);
    wrap(tft, body, 36, 84, SCREEN_W - 70, 6, SMALL, theme.text, LINE_S + 2);
}

void setupHotspot() {
    tft.fillScreen(theme.bg);
    text(tft, "Let's get connected", SCREEN_W / 2, 10, MEDB, theme.accent, TC_DATUM);
    String join = String("WIFI:T:nopass;S:") + netconn::SETUP_SSID + ";;";
    qr(join.c_str(), 10, 46, 132);
    int x = 154, w = SCREEN_W - x - 8;
    int y = 48;
    y += wrap(tft, "1. On your phone, join the Wi-Fi network:", x, y, w, 3, SMALL, theme.text, LINE_S) * LINE_S;
    y += wrap(tft, netconn::SETUP_SSID, x, y + 2, w, 2, SMALLB, theme.accent, LINE_S) * LINE_S + 10;
    wrap(tft, "2. A setup page opens. If it doesn't, browse to 192.168.4.1", x, y, w, 4, SMALL, theme.text, LINE_S);
    text(tft, "Or scan the code to join", 76, 184, SMALL, theme.dim, TC_DATUM);
}

void addPhotos(bool noPhotosYet) {
    tft.fillScreen(theme.bg);
    header(noPhotosYet ? "Welcome! No photos yet" : "Add photos", theme.accent);
    if (!netconn::online()) {
        wrap(tft, "The frame isn't on Wi-Fi right now, so photos can't be added from a phone. Check the router, "
                  "or hold the screen while the frame starts to set up Wi-Fi again.",
             14, 44, SCREEN_W - 28, 6, SMALL, theme.text, LINE_S + 2);
    } else {
        String u = netconn::url();
        qr(u.c_str(), 8, 36, 150);
        int x = 164, w = SCREEN_W - x - 6, y = 42;
        y += wrap(tft, "Scan with your phone's camera", x, y, w, 3, SMALLB, theme.text, LINE_S) * LINE_S + 6;
        y += wrap(tft, "Your phone needs to be on the same Wi-Fi.", x, y, w, 3, SMALL, theme.dim, LINE_S) * LINE_S + 8;
        text(tft, "Or open:", x, y, SMALL, theme.dim);
        text(tft, netconn::ip(), x, y + LINE_S, SMALLB, theme.accent);
        text(tft, fit(tft, cfg.hostname + ".local", w, SMALL), x, y + 2 * LINE_S, SMALL, theme.dim);
    }
    button(8, 196, 98, 38, "Test sound", theme.cardHi);
    button(111, 196, 98, 38, "Test mic", theme.cardHi);
    button(214, 196, 98, 38, "Close", theme.accent, theme.black);
}

AddPhotosButton addPhotosHit(int16_t x, int16_t y) {
    if (y < 190) return ADD_NONE;
    if (x < 108) return ADD_SPEAKER_TEST;
    if (x < 211) return ADD_MIC_TEST;
    return ADD_CLOSE;
}

int newsPages(int feed) {
    news::Feed f = news::feed(feed);
    return (f.count + 1) / 2;
}

static int drawItem(const rss::Item& it, int y, int maxTitleLines, int bottom) {
    int lines = min(maxTitleLines, (bottom - y - LINE_S) / LINE_M);
    if (lines < 1) return y;
    int used = wrap(tft, it.title, 14, y, SCREEN_W - 26, lines, MEDB, theme.text, LINE_M);
    y += used * LINE_M + 2;
    if (it.source[0]) {
        text(tft, fit(tft, it.source, SCREEN_W - 28, SMALL), 14, y, SMALL, theme.dim);
        y += LINE_S;
    }
    return y;
}

bool newsCard(int feedIdx, int page, bool interactive) {
    if (feedIdx < 0 || feedIdx >= cfg.feedCount) return false;
    news::Feed f = news::feed(feedIdx);
    int first = page * 2;
    if (first >= f.count) return false;
    uint16_t accent = feedColor(feedIdx);
    tft.fillScreen(theme.bg);
    int pages = (f.count + 1) / 2;
    String title = cfg.feeds[feedIdx].name;
    if (interactive && pages > 1) title += "  " + String(page + 1) + "/" + String(pages);
    header(title, accent);

    int bottom = interactive ? 190 : SCREEN_H - 4;
    int y = 40;
    y = drawItem(f.items[first], y, interactive ? 2 : 3, bottom);
    if (first + 1 < f.count) {
        y += 6;
        tft.drawFastHLine(14, y, SCREEN_W - 28, theme.card);
        y += 9;
        drawItem(f.items[first + 1], y, interactive ? 2 : 3, bottom);
    }
    if (interactive) {
        button(6, 198, 70, 36, "<", theme.cardHi);
        button(82, 198, 76, 36, "Read", theme.cardHi);
        button(164, 198, 76, 36, "Close", theme.cardHi);
        button(246, 198, 68, 36, ">", theme.cardHi);
    }
    return true;
}

NewsButton newsHit(int16_t x, int16_t y) {
    if (y < 192) return NEWS_NONE;
    if (x < 79) return NEWS_PREV;
    if (x < 161) return NEWS_READ;
    if (x < 243) return NEWS_CLOSE;
    return NEWS_NEXT;
}

bool weatherCard() {
    news::Weather w = news::weather();
    if (!w.valid) return false;
    uint16_t accent = disp::rgb(90, 170, 255);
    tft.fillScreen(theme.bg);
    header(cfg.place.length() ? "Weather - " + cfg.place : String("Weather"), accent);
    weatherIcon(tft, w.code, w.day, 78, 112, 50, theme.bg);
    String temp = String((int)lroundf(w.temp));
    text(tft, temp, 156, 48, HUGEB, theme.text);
    tft.setFreeFont(HUGEB);
    degree(tft, 156 + tft.textWidth(temp) + 7, 56, 5, theme.text);
    text(tft, fit(tft, news::weatherText(w.code), SCREEN_W - 160, MEDB), 156, 106, MEDB, theme.text);
    text(tft, "High " + String((int)lroundf(w.hi)) + "   Low " + String((int)lroundf(w.lo)), 156, 140, SMALL,
         theme.dim);
    text(tft, "Rain/snow chance " + String(w.rain) + "%", 156, 162, SMALL, theme.dim);
    text(tft, dateText(), SCREEN_W / 2, 206, SMALL, theme.dim, TC_DATUM);
    return true;
}

void greeting(bool waitingForNews) {
    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    const char* part = t.tm_hour < 12 ? "Good morning" : t.tm_hour < 17 ? "Good afternoon" : "Good evening";
    tft.fillScreen(theme.bg);
    tft.fillRect(0, 0, SCREEN_W, 6, theme.accent);
    String hello = String(part) + (cfg.names.length() ? "," : "!");
    text(tft, hello, SCREEN_W / 2, 26, BIGB, theme.accent, TC_DATUM);
    int y = 72;
    if (cfg.names.length()) {
        const GFXfont* f = BIGB;
        tft.setFreeFont(f);
        if (tft.textWidth(cfg.names + "!") > SCREEN_W - 20) f = MEDB;
        text(tft, fit(tft, cfg.names + "!", SCREEN_W - 20, f), SCREEN_W / 2, y, f, theme.text, TC_DATUM);
        y += 44;
    }
    text(tft, dateText(), SCREEN_W / 2, y, MED, theme.dim, TC_DATUM);
    news::Weather w = news::weather();
    if (w.valid) {
        weatherIcon(tft, w.code, w.day, 48, 172, 20, theme.bg);
        String line = String((int)lroundf(w.temp)) + " degrees, " + news::weatherText(w.code);
        line.toLowerCase();
        line[0] = toupper(line[0]);
        text(tft, fit(tft, line, SCREEN_W - 90, MEDB), 80, 152, MEDB, theme.text);
        text(tft, "High " + String((int)lroundf(w.hi)) + ", low " + String((int)lroundf(w.lo)), 80, 180, SMALL,
             theme.dim);
    }
    text(tft, waitingForNews ? "Getting the latest news..." : "Tap for next  -  Hold to stop", SCREEN_W / 2, 216,
         SMALL, theme.faint, TC_DATUM);
}

// ---------------- menu bar over a photo ----------------

static const int MENU_Y = 176;

static void triangle(int cx, int cy, bool right, uint16_t c) {
    if (right) tft.fillTriangle(cx - 7, cy - 10, cx - 7, cy + 10, cx + 9, cy, c);
    else tft.fillTriangle(cx + 7, cy - 10, cx + 7, cy + 10, cx - 9, cy, c);
}

void menuBar(bool micReady) {
    tft.fillRect(0, MENU_Y, SCREEN_W, SCREEN_H - MENU_Y, theme.card);
    tft.drawFastHLine(0, MENU_Y, SCREEN_W, theme.accent);
    const char* labels[] = {"Back", "News", "Ask", "Add", "Next"};
    for (int i = 0; i < 5; i++) {
        int cx = i * 64 + 32, cy = MENU_Y + 22;
        uint16_t c = (i == 2 && !micReady) ? theme.faint : theme.text;
        switch (i) {
            case 0: triangle(cx, cy, false, c); break;
            case 1:
                tft.drawRoundRect(cx - 12, cy - 11, 24, 22, 3, c);
                for (int k = 0; k < 3; k++) tft.drawFastHLine(cx - 7, cy - 5 + k * 5, 14, c);
                break;
            case 2: micIcon(cx, cy - 2, 26, c); break;
            case 3:
                tft.drawRoundRect(cx - 12, cy - 11, 24, 22, 3, c);
                tft.fillRect(cx - 1, cy - 6, 3, 13, c);
                tft.fillRect(cx - 6, cy - 1, 13, 3, c);
                break;
            case 4: triangle(cx, cy, true, c); break;
        }
        text(tft, labels[i], cx, MENU_Y + 46, SMALL, c, TC_DATUM);
    }
}

MenuButton menuHit(int16_t x, int16_t y) {
    if (y < MENU_Y) return MENU_OUTSIDE;
    switch (x / 64) {
        case 0: return MENU_BACK;
        case 1: return MENU_NEWS;
        case 2: return MENU_ASK;
        case 3: return MENU_ADD;
        default: return MENU_NEXT;
    }
}

// ---------------- assistant ----------------

void listening() {
    tft.fillScreen(theme.bg);
    tft.fillCircle(SCREEN_W / 2, 86, 50, theme.accent);
    micIcon(SCREEN_W / 2, 84, 52, theme.black);
    text(tft, "Listening...", SCREEN_W / 2, 150, MEDB, theme.text, TC_DATUM);
    tft.drawRoundRect(58, 186, 204, 14, 7, theme.faint);
    text(tft, "Ask your question, then pause. Tap to stop.", SCREEN_W / 2, 214, SMALL, theme.dim, TC_DATUM);
}

void listeningLevel(int percent) {
    static int last = -1;
    int w = constrain(percent, 0, 100) * 200 / 100;
    if (w == last) return;
    last = w;
    tft.fillRoundRect(60, 188, 200, 10, 5, theme.bg);
    if (w > 4) tft.fillRoundRect(60, 188, w, 10, 5, theme.good);
}

void thinking(const String& heard) {
    tft.fillScreen(theme.bg);
    header("Assistant", theme.accent);
    if (heard.length()) {
        text(tft, "You asked:", 14, 42, SMALL, theme.dim);
        wrap(tft, "\"" + heard + "\"", 14, 64, SCREEN_W - 28, 4, MEDB, theme.text, LINE_M);
    }
    text(tft, "Thinking...", SCREEN_W / 2, 196, MEDB, theme.accent, TC_DATUM);
}

void answer(const String& question, const String& reply) {
    tft.fillScreen(theme.bg);
    header("Assistant", theme.accent);
    int y = 38;
    if (question.length()) {
        y += wrap(tft, question, 14, y, SCREEN_W - 28, 2, SMALL, theme.dim, LINE_S) * LINE_S + 8;
    }
    // Big letters when the answer is short, smaller when it's long.
    int maxLines = (SCREEN_H - 4 - y) / LINE_M;
    int needed = wrap(tft, reply, 14, y, SCREEN_W - 28, 99, MEDB, theme.text, LINE_M, false);
    if (needed <= maxLines) wrap(tft, reply, 14, y, SCREEN_W - 28, maxLines, MEDB, theme.text, LINE_M);
    else wrap(tft, reply, 14, y, SCREEN_W - 28, (SCREEN_H - 4 - y) / LINE_S, SMALL, theme.text, LINE_S);
}

// ---------------- first-start checks ----------------

void calibrationTarget(int x, int y, int step) {
    tft.fillScreen(TFT_BLACK);
    uint16_t c = theme.accent;
    tft.drawCircle(x, y, 10, c);
    tft.drawCircle(x, y, 2, c);
    tft.drawFastHLine(x - 16, y, 33, c);
    tft.drawFastVLine(x, y - 16, 33, c);
    text(tft, "Touch setup (" + String(step) + " of 2)", SCREEN_W / 2, 92, MEDB, theme.text, TC_DATUM);
    text(tft, "Tap the center of the target.", SCREEN_W / 2, 126, SMALL, theme.dim, TC_DATUM);
    text(tft, "A fingernail or stylus works best.", SCREEN_W / 2, 148, SMALL, theme.dim, TC_DATUM);
}

void colorCheck() {
    tft.fillScreen(theme.bg);
    text(tft, "Do the colors match their names?", SCREEN_W / 2, 8, SMALLB, theme.text, TC_DATUM);
    const char* names[] = {"Red", "Green", "Blue", "White"};
    uint16_t colors[] = {disp::rgb(255, 0, 0), disp::rgb(0, 255, 0), disp::rgb(0, 0, 255), disp::rgb(255, 255, 255)};
    for (int i = 0; i < 4; i++) {
        int x = 10 + i * 77;
        tft.fillRoundRect(x, 36, 70, 92, 8, colors[i]);
        text(tft, names[i], x + 35, 136, SMALLB, theme.text, TC_DATUM);
    }
    text(tft, "Photo negative? Tap Invert. Red and blue", SCREEN_W / 2, 160, SMALL, theme.dim, TC_DATUM);
    text(tft, "swapped? Tap Swap. Then Looks right.", SCREEN_W / 2, 178, SMALL, theme.dim, TC_DATUM);
    button(6, 200, 92, 36, "Invert", theme.cardHi);
    button(104, 200, 92, 36, "Swap", theme.cardHi);
    button(202, 200, 112, 36, "Looks right", theme.good, theme.black);
}

ColorButton colorHit(int16_t x, int16_t y) {
    if (y < 194) return COLOR_NONE;
    if (x < 101) return COLOR_INVERT;
    if (x < 199) return COLOR_SWAP;
    return COLOR_OK;
}

}  // namespace screens
