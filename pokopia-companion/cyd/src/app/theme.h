// Colours and shared LVGL styles, matching the phone app's palette.
#pragma once
#include "lvgl.h"

LV_FONT_DECLARE(font_body_14)
LV_FONT_DECLARE(font_bold_14)
LV_FONT_DECLARE(font_small_12)
LV_FONT_DECLARE(font_title_20)
LV_FONT_DECLARE(font_hero_26)
LV_FONT_DECLARE(font_mono_12)

namespace theme {

struct Palette {
    lv_color_t bg, surface, surface2, ink, ink_soft, line;
    lv_color_t moss, moss_soft, on_moss;
    lv_color_t ditto, ditto_soft;
    lv_color_t water, water_soft;
    lv_color_t warn, warn_soft, danger;
};

extern Palette P;

extern lv_style_t screen;       // page background and default text
extern lv_style_t header;       // top bar
extern lv_style_t content;      // scrolling page body
extern lv_style_t scrollbar;
extern lv_style_t card;         // grouped content
extern lv_style_t row;          // tappable list row
extern lv_style_t pressed;      // pressed feedback for rows and buttons
extern lv_style_t btn;          // secondary button
extern lv_style_t btn_primary;  // filled moss button
extern lv_style_t btn_danger;   // armed destructive button
extern lv_style_t chip;         // small rounded label
extern lv_style_t chip_friend;  // befriended / built / learned
extern lv_style_t chip_seen;
extern lv_style_t badge_warn;   // "Check in-game"
extern lv_style_t sprite_box;

void apply(bool dark);
lv_color_t tile_color(uint8_t tile);

}  // namespace theme
