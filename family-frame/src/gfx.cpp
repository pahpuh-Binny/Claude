#include "gfx.h"

#include <qrcode.h>

#include "display.h"

namespace gfx {

Theme theme;

const GFXfont* const SMALL = &FreeSans9pt7b;
const GFXfont* const SMALLB = &FreeSansBold9pt7b;
const GFXfont* const MED = &FreeSans12pt7b;
const GFXfont* const MEDB = &FreeSansBold12pt7b;
const GFXfont* const BIGB = &FreeSansBold18pt7b;
const GFXfont* const HUGEB = &FreeSansBold24pt7b;

void loadTheme() {
    theme.bg = disp::rgb(14, 18, 28);
    theme.card = disp::rgb(30, 37, 54);
    theme.cardHi = disp::rgb(48, 58, 84);
    theme.text = disp::rgb(242, 242, 246);
    theme.dim = disp::rgb(160, 168, 186);
    theme.faint = disp::rgb(90, 98, 118);
    theme.accent = disp::rgb(255, 176, 59);
    theme.good = disp::rgb(64, 200, 120);
    theme.warn = disp::rgb(255, 196, 0);
    theme.bad = disp::rgb(240, 80, 80);
    theme.white = disp::rgb(255, 255, 255);
    theme.black = disp::rgb(0, 0, 0);
}

uint16_t feedColor(int i) {
    static const uint8_t c[][3] = {
        {255, 112, 90},  {80, 170, 255}, {0, 200, 170},   {255, 184, 0},
        {180, 130, 255}, {255, 110, 180}, {150, 210, 60}, {255, 145, 50},
    };
    const uint8_t* p = c[(i < 0 ? 0 : i) % 8];
    return disp::rgb(p[0], p[1], p[2]);
}

int wrap(TFT_eSPI& d, const String& s, int x, int y, int w, int maxLines, const GFXfont* font, uint16_t color,
         int lineH, bool draw) {
    d.setFreeFont(font);
    d.setTextColor(color);
    d.setTextDatum(TL_DATUM);
    if (lineH <= 0) lineH = font->yAdvance;
    int n = s.length(), pos = 0, lines = 0;
    while (pos < n && lines < maxLines) {
        while (pos < n && s[pos] == ' ') pos++;
        if (pos >= n) break;
        // Take as many whole words as fit.
        int end = -1;
        for (int i = pos; i <= n; i++) {
            if (i == n || s[i] == ' ') {
                if (d.textWidth(s.substring(pos, i)) <= w) end = i;
                else break;
            }
        }
        if (end < 0) {  // one very long word: cut it
            end = pos + 1;
            while (end < n && d.textWidth(s.substring(pos, end + 1)) <= w) end++;
        }
        String line = s.substring(pos, end);
        int rest = end;
        while (rest < n && s[rest] == ' ') rest++;
        if (lines == maxLines - 1 && rest < n) {
            while (line.length() && d.textWidth(line + "...") > w) line.remove(line.length() - 1);
            line.trim();
            line += "...";
        }
        if (draw) d.drawString(line, x, y + lines * lineH);
        lines++;
        pos = end;
    }
    return lines;
}

void text(TFT_eSPI& d, const String& s, int x, int y, const GFXfont* font, uint16_t color, uint8_t datum) {
    d.setFreeFont(font);
    d.setTextColor(color);
    d.setTextDatum(datum);
    d.drawString(s, x, y);
}

String fit(TFT_eSPI& d, const String& s, int w, const GFXfont* font) {
    d.setFreeFont(font);
    if (d.textWidth(s) <= w) return s;
    String t = s;
    while (t.length() && d.textWidth(t + "...") > w) t.remove(t.length() - 1);
    t.trim();
    return t + "...";
}

void button(int x, int y, int w, int h, const char* label, uint16_t fill, uint16_t fg) {
    tft.fillRoundRect(x, y, w, h, 8, fill);
    text(tft, label, x + w / 2, y + h / 2 + 1, SMALLB, fg == 0xFFFF ? theme.text : fg, MC_DATUM);
}

bool hit(int16_t tx, int16_t ty, int x, int y, int w, int h) {
    return tx >= x && tx < x + w && ty >= y && ty < y + h;
}

// ESP-IDF's QR encoder calls back without a context pointer, so pass the box here.
static int qrX, qrY, qrSize;

static void qrDraw(esp_qrcode_handle_t q) {
    int n = esp_qrcode_get_size(q);
    int quiet = 2;
    int scale = qrSize / (n + 2 * quiet);
    if (scale < 1) scale = 1;
    int total = scale * (n + 2 * quiet);
    int ox = qrX + (qrSize - total) / 2, oy = qrY + (qrSize - total) / 2;
    tft.fillRect(ox, oy, total, total, TFT_WHITE);
    for (int yy = 0; yy < n; yy++) {
        for (int xx = 0; xx < n; xx++) {
            if (esp_qrcode_get_module(q, xx, yy))
                tft.fillRect(ox + (xx + quiet) * scale, oy + (yy + quiet) * scale, scale, scale, TFT_BLACK);
        }
    }
}

void qr(const char* s, int x, int y, int size) {
    qrX = x;
    qrY = y;
    qrSize = size;
    esp_qrcode_config_t c = ESP_QRCODE_CONFIG_DEFAULT();
    c.display_func = qrDraw;
    c.max_qrcode_version = 8;
    c.qrcode_ecc_level = ESP_QRCODE_ECC_LOW;
    esp_qrcode_generate(&c, s);
}

void degree(TFT_eSPI& d, int x, int y, int r, uint16_t color) {
    d.drawCircle(x, y, r, color);
    if (r > 2) d.drawCircle(x, y, r - 1, color);
}

static void cloud(TFT_eSPI& d, int cx, int cy, int r, uint16_t c) {
    d.fillCircle(cx - r * 45 / 100, cy + r * 10 / 100, r * 36 / 100, c);
    d.fillCircle(cx + r * 5 / 100, cy - r * 15 / 100, r * 50 / 100, c);
    d.fillCircle(cx + r * 50 / 100, cy + r * 12 / 100, r * 34 / 100, c);
    d.fillRoundRect(cx - r * 80 / 100, cy + r * 5 / 100, r * 160 / 100, r * 42 / 100, r * 20 / 100, c);
}

static void sun(TFT_eSPI& d, int cx, int cy, int r, uint16_t c) {
    d.fillCircle(cx, cy, r * 45 / 100, c);
    for (int i = 0; i < 8; i++) {
        float a = i * PI / 4;
        int x1 = cx + cosf(a) * r * 62 / 100, y1 = cy + sinf(a) * r * 62 / 100;
        int x2 = cx + cosf(a) * r * 92 / 100, y2 = cy + sinf(a) * r * 92 / 100;
        d.drawLine(x1, y1, x2, y2, c);
        d.drawLine(x1 + 1, y1, x2 + 1, y2, c);
    }
}

static void moon(TFT_eSPI& d, int cx, int cy, int r, uint16_t c, uint16_t bg) {
    d.fillCircle(cx, cy, r * 60 / 100, c);
    d.fillCircle(cx + r * 30 / 100, cy - r * 20 / 100, r * 50 / 100, bg);
}

// WMO weather codes as used by Open-Meteo.
void weatherIcon(TFT_eSPI& d, int code, bool day, int cx, int cy, int r, uint16_t bg) {
    uint16_t yellow = disp::rgb(255, 200, 40), grey = disp::rgb(200, 206, 218), dark = disp::rgb(130, 138, 156);
    uint16_t blue = disp::rgb(90, 170, 255), white = disp::rgb(250, 250, 255), moonC = disp::rgb(230, 230, 200);
    if (code == 0) {
        if (day) sun(d, cx, cy, r, yellow);
        else moon(d, cx, cy, r, moonC, bg);
        return;
    }
    if (code <= 2) {
        if (day) sun(d, cx - r / 3, cy - r / 3, r * 7 / 10, yellow);
        else moon(d, cx - r / 3, cy - r / 3, r * 7 / 10, moonC, bg);
        cloud(d, cx + r / 8, cy + r / 6, r * 8 / 10, grey);
        return;
    }
    if (code == 45 || code == 48) {
        for (int i = 0; i < 4; i++)
            d.fillRoundRect(cx - r * 8 / 10 + (i % 2) * r / 5, cy - r / 2 + i * r / 3, r * 14 / 10, r / 7 + 1, 2, grey);
        return;
    }
    bool storm = code >= 95, snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
    bool wet = !storm && !snow && code >= 51;
    cloud(d, cx, cy - r / 5, r, (storm || wet) ? dark : grey);
    if (wet) {
        for (int i = -1; i <= 1; i++) {
            int x = cx + i * r * 4 / 10;
            d.drawLine(x, cy + r * 45 / 100, x - r / 6, cy + r * 85 / 100, blue);
            d.drawLine(x + 1, cy + r * 45 / 100, x + 1 - r / 6, cy + r * 85 / 100, blue);
        }
    } else if (snow) {
        for (int i = -1; i <= 1; i++) d.fillCircle(cx + i * r * 4 / 10, cy + r * 65 / 100 + (i & 1) * r / 8, r / 9 + 1, white);
    } else if (storm) {
        int x = cx, y = cy + r * 30 / 100;
        d.fillTriangle(x, y, x - r / 4, y + r * 35 / 100, x + r / 10, y + r * 30 / 100, yellow);
        d.fillTriangle(x + r / 10, y + r * 25 / 100, x - r / 10, y + r * 70 / 100, x + r / 5, y + r * 25 / 100, yellow);
    }
}

void micIcon(int cx, int cy, int s, uint16_t c) {
    int w = s * 36 / 100, h = s * 60 / 100;
    tft.fillRoundRect(cx - w / 2, cy - s / 2, w, h, w / 2, c);
    for (int t = 0; t < 3; t++) {
        // U-shaped holder below the capsule
        tft.drawCircleHelper(cx, cy, s * 32 / 100 + t, 0x4 | 0x8, c);
    }
    tft.fillRect(cx - s * 34 / 100, cy - s * 6 / 100, 3, s * 8 / 100, c);
    tft.fillRect(cx + s * 32 / 100, cy - s * 6 / 100, 3, s * 8 / 100, c);
    tft.fillRect(cx - 1, cy + s * 32 / 100, 3, s * 14 / 100, c);
    tft.fillRect(cx - s / 5, cy + s * 45 / 100, s * 2 / 5, 3, c);
}

void speakerIcon(int cx, int cy, int s, uint16_t c) {
    int b = s / 5;
    tft.fillRect(cx - s / 2, cy - b, b, 2 * b, c);
    tft.fillTriangle(cx - s / 2 + b, cy - b, cx, cy - s / 2, cx, cy + s / 2, c);
    tft.fillTriangle(cx - s / 2 + b, cy - b, cx - s / 2 + b, cy + b, cx, cy + s / 2, c);
    for (int t = 0; t < 2; t++) {
        tft.drawCircleHelper(cx + 2, cy, s / 4 + t, 0x2 | 0x4, c);
        tft.drawCircleHelper(cx + 2, cy, s / 2 + t, 0x2 | 0x4, c);
    }
}

}  // namespace gfx
