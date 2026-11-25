#include "photos.h"

#include <SD.h>
#include <TJpg_Decoder.h>
#include <time.h>

#include <vector>

#include "display.h"
#include "gfx.h"
#include "news.h"
#include "settings.h"

namespace photos {

static const char* INDEX_PATH = "/frame/photos.idx";
static const int MAX_PHOTOS = 5000;

static std::vector<uint32_t> offsets;  // where each name starts in the index file
static std::vector<uint16_t> order;    // slideshow order (shuffled or not)
static int pos = -1;
static String current;

// ---------------- clock overlay ----------------
// While a photo is decoded, the pixels under the overlay box are darkened and
// kept here, so the clock can be redrawn every minute without decoding again.

static const int OV_MAX_W = 196, OV_H = 58, OV_MARGIN = 6, OV_RADIUS = 12;
static uint16_t* ovCache = nullptr;  // pixels in display byte order
static int ovX = 0, ovY = 0, ovW = 0;
static bool ovCapture = false, ovValid = false;
static uint8_t ovInset[OV_H];

static inline uint16_t swap16(uint16_t v) { return (uint16_t)((v >> 8) | (v << 8)); }

static inline uint16_t swapRedBlue(uint16_t c) { return (uint16_t)((c << 11) | (c & 0x07E0) | (c >> 11)); }

static inline uint16_t darken(uint16_t c) {
    uint16_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    return (uint16_t)(((r * 2 / 5) << 11) | ((g * 2 / 5) << 5) | (b * 2 / 5));
}

static void placeOverlay() {
    news::Weather w = news::weather();
    ovW = w.valid ? OV_MAX_W : 136;
    bool right = cfg.clockCorner & 1, bottom = cfg.clockCorner & 2;
    ovX = right ? SCREEN_W - ovW - OV_MARGIN : OV_MARGIN;
    ovY = bottom ? SCREEN_H - OV_H - OV_MARGIN : OV_MARGIN;
    for (int y = 0; y < OV_H; y++) {
        int dy = y < OV_RADIUS ? OV_RADIUS - y : (y >= OV_H - OV_RADIUS ? y - (OV_H - OV_RADIUS - 1) : 0);
        ovInset[y] = dy ? (uint8_t)(OV_RADIUS - sqrtf((float)(OV_RADIUS * OV_RADIUS - dy * dy))) : 0;
    }
}

static bool jpegBlock(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t* px) {
    if (y >= SCREEN_H) return false;  // the rest is off screen: stop decoding
    bool swapRB = cfg.swapRB;
    if (swapRB) {
        for (uint32_t i = 0, n = (uint32_t)w * h; i < n; i++) px[i] = swap16(swapRedBlue(swap16(px[i])));
    }
    if (ovCapture) {
        int x0 = max<int>(x, ovX), x1 = min<int>(x + w, ovX + ovW);
        int y0 = max<int>(y, ovY), y1 = min<int>(y + h, ovY + OV_H);
        for (int yy = y0; yy < y1; yy++) {
            int ly = yy - ovY;
            for (int xx = x0; xx < x1; xx++) {
                int lx = xx - ovX;
                uint16_t& p = px[(yy - y) * w + (xx - x)];
                if (lx >= ovInset[ly] && lx < ovW - ovInset[ly]) p = swap16(darken(swap16(p)));
                ovCache[ly * OV_MAX_W + lx] = p;
            }
        }
    }
    tft.pushImage(x, y, w, h, px);
    return true;
}

static bool isJpeg(const String& name) {
    if (name.isEmpty() || name[0] == '.') return false;  // skip macOS "._" files
    String n = name;
    n.toLowerCase();
    return n.endsWith(".jpg") || n.endsWith(".jpeg");
}

static void buildOrder() {
    order.resize(offsets.size());
    for (size_t i = 0; i < order.size(); i++) order[i] = (uint16_t)i;
    if (cfg.shuffle) {
        for (int i = (int)order.size() - 1; i > 0; i--) {
            int j = (int)(esp_random() % (uint32_t)(i + 1));
            std::swap(order[i], order[j]);
        }
    }
    pos = -1;
}

static String nameAt(int idx) {
    if (idx < 0 || idx >= (int)offsets.size()) return "";
    File f = SD.open(INDEX_PATH, FILE_READ);
    if (!f) return "";
    f.seek(offsets[idx]);
    String s = f.readStringUntil('\n');
    f.close();
    return s;
}

void begin() {
    TJpgDec.setSwapBytes(true);
    TJpgDec.setCallback(jpegBlock);
    if (!ovCache) ovCache = (uint16_t*)malloc(OV_MAX_W * OV_H * 2);
    SD.mkdir(DIR);
    SD.mkdir("/frame");
    rescan();
}

void rescan() {
    offsets.clear();
    File idx = SD.open(INDEX_PATH, FILE_WRITE);
    File dir = SD.open(DIR);
    if (idx && dir) {
        for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
            String n = f.name();
            int slash = n.lastIndexOf('/');
            if (slash >= 0) n = n.substring(slash + 1);
            bool ok = !f.isDirectory() && isJpeg(n);
            f.close();
            if (!ok) continue;
            offsets.push_back(idx.position());
            idx.print(n);
            idx.print('\n');
            if ((int)offsets.size() >= MAX_PHOTOS) break;
        }
    }
    if (dir) dir.close();
    if (idx) idx.close();
    buildOrder();
    Serial.printf("Photos: %u\n", (unsigned)offsets.size());
}

int count() { return (int)offsets.size(); }

String currentName() { return current; }

static bool draw(const String& name) {
    String path = String(DIR) + "/" + name;
    uint16_t w = 0, h = 0;
    if (TJpgDec.getSdJpgSize(&w, &h, path) != JDR_OK || !w || !h) {
        Serial.printf("Can't read %s (progressive JPEGs aren't supported)\n", path.c_str());
        return false;
    }
    // Scale down by 2, 4 or 8 until the photo fits; anything still too big is centred and cropped.
    uint8_t scale = 1;
    while (scale < 8 && (w / scale > SCREEN_W || h / scale > SCREEN_H)) scale *= 2;
    int sw = w / scale, sh = h / scale;
    int x = (SCREEN_W - sw) / 2, y = (SCREEN_H - sh) / 2;
    TJpgDec.setJpgScale(scale);

    ovCapture = cfg.clock && ovCache;
    if (ovCapture) {
        placeOverlay();
        memset(ovCache, 0, OV_MAX_W * OV_H * 2);  // black where the photo doesn't reach
    }
    if (sw < SCREEN_W || sh < SCREEN_H) {
        // Letterbox bars
        if (y > 0) {
            tft.fillRect(0, 0, SCREEN_W, y, TFT_BLACK);
            tft.fillRect(0, y + sh, SCREEN_W, SCREEN_H - y - sh, TFT_BLACK);
        }
        if (x > 0) {
            tft.fillRect(0, 0, x, SCREEN_H, TFT_BLACK);
            tft.fillRect(x + sw, 0, SCREEN_W - x - sw, SCREEN_H, TFT_BLACK);
        }
    }
    JRESULT r = TJpgDec.drawSdJpg(x, y, path);
    ovCapture = false;
    ovValid = cfg.clock && ovCache && r == JDR_OK;
    current = name;
    if (ovValid) drawOverlay();
    return r == JDR_OK;
}

static bool step(int dir) {
    if (offsets.empty()) return false;
    for (int attempt = 0; attempt < 8 && attempt < (int)offsets.size() + 1; attempt++) {
        pos += dir;
        if (pos >= (int)order.size()) {
            String last = current;
            buildOrder();
            pos = 0;
            // Don't show the same photo twice in a row across a reshuffle.
            if (order.size() > 1 && nameAt(order[0]) == last) std::swap(order[0], order[1]);
        } else if (pos < 0) {
            pos = (int)order.size() - 1;
        }
        if (draw(nameAt(order[pos]))) return true;
    }
    return false;
}

bool showNext() { return step(1); }
bool showPrev() { return step(-1); }

bool show(const String& name) { return draw(name); }

void added(const String& name) {
    File idx = SD.open(INDEX_PATH, FILE_APPEND);
    if (!idx) return;
    uint32_t off = idx.size();
    idx.print(name);
    idx.print('\n');
    idx.close();
    offsets.push_back(off);
    // Slot it in right after the current photo.
    uint16_t id = (uint16_t)(offsets.size() - 1);
    order.insert(order.begin() + (pos + 1 < 0 ? 0 : pos + 1), id);
}

bool remove(const String& name) {
    if (name.indexOf('/') >= 0 || name.startsWith(".")) return false;
    bool ok = SD.remove(String(DIR) + "/" + name);
    rescan();
    return ok;
}

void forEachName(const std::function<void(const String&)>& fn) {
    File f = SD.open(INDEX_PATH, FILE_READ);
    if (!f) return;
    while (f.available()) {
        String s = f.readStringUntil('\n');
        if (s.length()) fn(s);
    }
    f.close();
}

void drawOverlay() {
    if (!ovValid || !cfg.clock) return;
    TFT_eSprite spr(&tft);
    spr.setColorDepth(16);
    if (!spr.createSprite(ovW, OV_H)) return;
    for (int y = 0; y < OV_H; y++) spr.pushImage(0, y, ovW, 1, ovCache + y * OV_MAX_W);

    time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    char hm[8], ampm[3] = "", date[20];
    if (cfg.clock24) {
        strftime(hm, sizeof hm, "%H:%M", &t);
    } else {
        int h12 = t.tm_hour % 12 ? t.tm_hour % 12 : 12;
        snprintf(hm, sizeof hm, "%d:%02d", h12, t.tm_min);
        strcpy(ampm, t.tm_hour < 12 ? "AM" : "PM");
    }
    strftime(date, sizeof date, "%a, %b ", &t);
    snprintf(date + strlen(date), sizeof date - strlen(date), "%d", t.tm_mday);

    uint16_t white = gfx::theme.white, soft = disp::rgb(215, 220, 230);
    gfx::text(spr, hm, 12, 6, gfx::BIGB, white);
    spr.setFreeFont(gfx::BIGB);
    int tw = spr.textWidth(hm);
    if (ampm[0]) gfx::text(spr, ampm, 12 + tw + 4, 8, gfx::SMALLB, soft);
    gfx::text(spr, date, 12, 37, gfx::SMALL, soft);

    news::Weather w = news::weather();
    if (w.valid && ovW >= OV_MAX_W) {
        String temp = String((int)lroundf(w.temp));
        spr.setFreeFont(gfx::MEDB);
        int tempW = spr.textWidth(temp);
        int tx = ovW - 18 - tempW;
        gfx::text(spr, temp, tx, 8, gfx::MEDB, white);
        gfx::degree(spr, ovW - 12, 11, 3, white);
        gfx::weatherIcon(spr, w.code, w.day, tx - 18, 19, 12, darken(gfx::theme.black));
        String hl = "H" + String((int)lroundf(w.hi)) + " L" + String((int)lroundf(w.lo));
        gfx::text(spr, hl, ovW - 10, 37, gfx::SMALL, soft, TR_DATUM);
    }
    spr.pushSprite(ovX, ovY);
    spr.deleteSprite();
}

}  // namespace photos
