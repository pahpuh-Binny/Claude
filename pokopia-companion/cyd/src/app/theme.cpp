#include "theme.h"

#include "../gen/game_data.h"

namespace theme {

Palette P;
lv_style_t screen, header, content, scrollbar, card, row, pressed, btn, btn_primary, btn_danger;
lv_style_t chip, chip_friend, chip_seen, badge_warn, sprite_box;

static bool styles_ready = false;

static const Palette LIGHT = {
    lv_color_hex(0xedf1e8), lv_color_hex(0xfafcf7), lv_color_hex(0xe2e8db),
    lv_color_hex(0x1d2820), lv_color_hex(0x536157), lv_color_hex(0xc7d1c0),
    lv_color_hex(0x3b7547), lv_color_hex(0xd3e6d0), lv_color_hex(0xffffff),
    lv_color_hex(0x7a4ea6), lv_color_hex(0xebe0f5),
    lv_color_hex(0x2f6f9f), lv_color_hex(0xd7e7f3),
    lv_color_hex(0x8a5208), lv_color_hex(0xf6e6c6), lv_color_hex(0xa3271f),
};

static const Palette DARK = {
    lv_color_hex(0x111712), lv_color_hex(0x182019), lv_color_hex(0x222c23),
    lv_color_hex(0xe4ece2), lv_color_hex(0xa2b0a4), lv_color_hex(0x313d32),
    lv_color_hex(0x7cc48a), lv_color_hex(0x203626), lv_color_hex(0x0f2014),
    lv_color_hex(0xc7a3ec), lv_color_hex(0x31263d),
    lv_color_hex(0x86bde6), lv_color_hex(0x1b2e3c),
    lv_color_hex(0xebb05c), lv_color_hex(0x382a15), lv_color_hex(0xf2a9a3),
};

static void reset(lv_style_t* s) {
    if (styles_ready) lv_style_reset(s);
    lv_style_init(s);
}

void apply(bool dark) {
    P = dark ? DARK : LIGHT;

    reset(&screen);
    lv_style_set_bg_color(&screen, P.bg);
    lv_style_set_bg_opa(&screen, LV_OPA_COVER);
    lv_style_set_text_color(&screen, P.ink);
    lv_style_set_text_font(&screen, &font_body_14);

    reset(&header);
    lv_style_set_bg_color(&header, P.bg);
    lv_style_set_bg_opa(&header, LV_OPA_COVER);
    lv_style_set_border_color(&header, P.line);
    lv_style_set_border_width(&header, 1);
    lv_style_set_border_side(&header, LV_BORDER_SIDE_BOTTOM);
    lv_style_set_pad_hor(&header, 4);
    lv_style_set_pad_column(&header, 6);

    reset(&content);
    lv_style_set_pad_all(&content, 8);
    lv_style_set_pad_row(&content, 6);

    reset(&scrollbar);
    lv_style_set_width(&scrollbar, 3);
    lv_style_set_pad_right(&scrollbar, 2);
    lv_style_set_pad_ver(&scrollbar, 4);
    lv_style_set_radius(&scrollbar, 2);
    lv_style_set_bg_color(&scrollbar, P.ink_soft);
    lv_style_set_bg_opa(&scrollbar, LV_OPA_40);

    reset(&card);
    lv_style_set_bg_color(&card, P.surface);
    lv_style_set_bg_opa(&card, LV_OPA_COVER);
    lv_style_set_border_color(&card, P.line);
    lv_style_set_border_width(&card, 1);
    lv_style_set_radius(&card, 10);
    lv_style_set_pad_all(&card, 8);
    lv_style_set_pad_row(&card, 4);
    lv_style_set_pad_column(&card, 6);

    reset(&row);
    lv_style_set_bg_color(&row, P.surface);
    lv_style_set_bg_opa(&row, LV_OPA_COVER);
    lv_style_set_border_color(&row, P.line);
    lv_style_set_border_width(&row, 1);
    lv_style_set_radius(&row, 8);
    lv_style_set_pad_hor(&row, 6);
    lv_style_set_pad_ver(&row, 4);
    lv_style_set_pad_column(&row, 8);

    reset(&pressed);
    lv_style_set_bg_color(&pressed, P.surface2);
    lv_style_set_border_color(&pressed, P.ditto);

    reset(&btn);
    lv_style_set_bg_color(&btn, P.surface);
    lv_style_set_bg_opa(&btn, LV_OPA_COVER);
    lv_style_set_border_color(&btn, P.line);
    lv_style_set_border_width(&btn, 1);
    lv_style_set_radius(&btn, 8);
    lv_style_set_pad_hor(&btn, 10);
    lv_style_set_pad_ver(&btn, 6);
    lv_style_set_text_font(&btn, &font_bold_14);
    lv_style_set_text_color(&btn, P.ink);

    reset(&btn_primary);
    lv_style_set_bg_color(&btn_primary, P.moss);
    lv_style_set_border_color(&btn_primary, P.moss);
    lv_style_set_text_color(&btn_primary, P.on_moss);

    reset(&btn_danger);
    lv_style_set_bg_color(&btn_danger, P.danger);
    lv_style_set_border_color(&btn_danger, P.danger);
    lv_style_set_text_color(&btn_danger, P.surface);

    reset(&chip);
    lv_style_set_bg_color(&chip, P.surface2);
    lv_style_set_bg_opa(&chip, LV_OPA_COVER);
    lv_style_set_border_color(&chip, P.line);
    lv_style_set_border_width(&chip, 1);
    lv_style_set_radius(&chip, LV_RADIUS_CIRCLE);
    lv_style_set_pad_hor(&chip, 8);
    lv_style_set_pad_ver(&chip, 3);
    lv_style_set_text_font(&chip, &font_small_12);
    lv_style_set_text_color(&chip, P.ink);

    reset(&chip_friend);
    lv_style_set_bg_color(&chip_friend, P.moss);
    lv_style_set_border_color(&chip_friend, P.moss);
    lv_style_set_text_color(&chip_friend, P.on_moss);

    reset(&chip_seen);
    lv_style_set_bg_color(&chip_seen, P.water_soft);
    lv_style_set_border_color(&chip_seen, P.water);
    lv_style_set_text_color(&chip_seen, P.ink);

    reset(&badge_warn);
    lv_style_set_bg_color(&badge_warn, P.warn_soft);
    lv_style_set_bg_opa(&badge_warn, LV_OPA_COVER);
    lv_style_set_radius(&badge_warn, LV_RADIUS_CIRCLE);
    lv_style_set_pad_hor(&badge_warn, 7);
    lv_style_set_pad_ver(&badge_warn, 2);
    lv_style_set_text_font(&badge_warn, &font_small_12);
    lv_style_set_text_color(&badge_warn, P.warn);

    reset(&sprite_box);
    lv_style_set_bg_color(&sprite_box, P.surface2);
    lv_style_set_bg_opa(&sprite_box, LV_OPA_COVER);
    lv_style_set_radius(&sprite_box, 8);
    lv_style_set_clip_corner(&sprite_box, true);

    styles_ready = true;

    lv_display_t* disp = lv_display_get_default();
    if (disp) {
        lv_theme_t* th = lv_theme_default_init(disp, P.moss, P.ditto, dark, &font_body_14);
        lv_display_set_theme(disp, th);
    }
    lv_obj_report_style_change(NULL);
}

lv_color_t tile_color(uint8_t tile) {
    switch (tile) {
        case gd::T_GRASS: return lv_color_hex(0x5fa35a);
        case gd::T_TREE: return lv_color_hex(0x2f6b3a);
        case gd::T_WATER: return lv_color_hex(0x4a90c8);
        case gd::T_OCEAN: return lv_color_hex(0x2a6a99);
        case gd::T_FLOWER: return lv_color_hex(0xe08bb0);
        case gd::T_FIRE: return lv_color_hex(0xe07a2e);
        case gd::T_ROCK: return lv_color_hex(0x9a998c);
        case gd::T_SOIL: return lv_color_hex(0xbb9d73);
        default: return lv_color_hex(0xb08455);  // wood and furniture
    }
}

}  // namespace theme
