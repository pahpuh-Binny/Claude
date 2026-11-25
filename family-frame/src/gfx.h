#pragma once
// Drawing helpers shared by all screens: colors, fonts, word wrap, buttons,
// QR codes and little weather icons.

#include <Arduino.h>
#include <TFT_eSPI.h>

namespace gfx {

struct Theme {
    uint16_t bg, card, cardHi, text, dim, faint, accent, good, warn, bad, white, black;
};
extern Theme theme;
void loadTheme();  // call again after the red/blue swap setting changes

// Accent color for news feed number i.
uint16_t feedColor(int i);

extern const GFXfont* const SMALL;   // FreeSans 9pt
extern const GFXfont* const SMALLB;  // FreeSans Bold 9pt
extern const GFXfont* const MED;     // FreeSans 12pt
extern const GFXfont* const MEDB;    // FreeSans Bold 12pt
extern const GFXfont* const BIGB;    // FreeSans Bold 18pt
extern const GFXfont* const HUGEB;   // FreeSans Bold 24pt

// Word-wraps text into a box. Draws when draw is true; always returns the
// number of lines used. The last line ends in "..." if the text didn't fit.
int wrap(TFT_eSPI& d, const String& text, int x, int y, int w, int maxLines, const GFXfont* font,
         uint16_t color, int lineH = 0, bool draw = true);

void text(TFT_eSPI& d, const String& s, int x, int y, const GFXfont* font, uint16_t color,
          uint8_t datum = TL_DATUM);
// Shortens s with "..." until it fits in w pixels.
String fit(TFT_eSPI& d, const String& s, int w, const GFXfont* font);

void button(int x, int y, int w, int h, const char* label, uint16_t fill, uint16_t fg = 0xFFFF);
bool hit(int16_t tx, int16_t ty, int x, int y, int w, int h);

// Draws a QR code no larger than size x size (white quiet zone included).
void qr(const char* textToEncode, int x, int y, int size);

void degree(TFT_eSPI& d, int x, int y, int r, uint16_t color);
// bg is the color behind the icon (used to cut the crescent moon).
void weatherIcon(TFT_eSPI& d, int code, bool day, int cx, int cy, int r, uint16_t bg);
void micIcon(int cx, int cy, int s, uint16_t color);
void speakerIcon(int cx, int cy, int s, uint16_t color);

}  // namespace gfx
