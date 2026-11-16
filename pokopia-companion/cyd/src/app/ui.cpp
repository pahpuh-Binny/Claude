// Pokopia Companion UI for the 320x240 CYD screen (LVGL 9).
//
// Every screen is rebuilt when you open it and deleted when you leave, which
// keeps memory low on an ESP32 without PSRAM. A small back stack remembers
// where you came from and how far you had scrolled.

#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <algorithm>
#include <string>
#include <vector>

#include "../gen/game_data.h"
#include "platform.h"
#include "save.h"
#include "theme.h"
#include "src/libs/qrcode/qrcodegen.h"  // QR encoder bundled with LVGL

using theme::P;

namespace ui {

static const int32_t W = 320;
static const int32_t H = 240;
static const int32_t HEADER_H = 36;
static const int32_t BAR_H = 44;

// ===================================================================
// Pokémon and habitat references
// ===================================================================
//
// Screens pass a single int to say which Pokémon they show:
//   0..999       built-in guide entry (gd::MONS index)
//   1000..9999   Pokémon added in the app or here (custom index + 1000)
//   10000+dex    any species from the National Pokédex, not tracked yet

static const int REF_CUSTOM = 1000;
static const int REF_SPECIES = 10000;

struct MonView {
    std::string id, name, where, role, note, base;
    uint16_t dex = 0;
    uint16_t sprite_dex = 0;
    std::vector<int> specs;  // indexes into gd::SPECS
    bool verify = false;
    bool custom = false;
    bool tracked = true;     // false for an untracked species
};

static int spec_index_by_id(const std::string& id) {
    for (int i = 0; i < gd::N_SPECS; i++)
        if (id == gd::SPECS[i].id) return i;
    return -1;
}

static bool same_name(const char* a, const char* b) {
    for (; *a && *b; a++, b++) {
        char x = (*a >= 'A' && *a <= 'Z') ? *a + 32 : *a;
        char y = (*b >= 'A' && *b <= 'Z') ? *b + 32 : *b;
        if (x != y) return false;
    }
    return *a == *b;
}

static uint16_t species_dex_by_name(const char* name) {
    for (int i = 0; i < gd::N_SPECIES; i++)
        if (same_name(gd::SPECIES_NAMES[i], name)) return i + 1;
    return 0;
}

static int ref_for_dex(uint16_t dex) {
    for (int i = 0; i < gd::N_MONS; i++)
        if (gd::MONS[i].dex == dex) return i;
    int c = save::custom_index_by_dex(dex);
    if (c >= 0) return REF_CUSTOM + c;
    return REF_SPECIES + dex;
}

static int ref_for_name(const char* name) {
    for (int i = 0; i < gd::N_MONS; i++)
        if (same_name(gd::MONS[i].name, name)) return i;
    const auto& customs = save::custom_mons();
    for (size_t j = 0; j < customs.size(); j++)
        if (same_name(customs[j].name.c_str(), name)) return REF_CUSTOM + (int)j;
    uint16_t dex = species_dex_by_name(name);
    return dex ? ref_for_dex(dex) : -1;
}

static MonView mon_view(int ref) {
    MonView v;
    if (ref >= REF_SPECIES) {
        uint16_t dex = ref - REF_SPECIES;
        int resolved = ref_for_dex(dex);
        if (resolved < REF_SPECIES) return mon_view(resolved);
        v.id = gd::SPECIES_IDS[dex - 1];
        v.name = gd::SPECIES_NAMES[dex - 1];
        v.dex = v.sprite_dex = dex;
        v.tracked = false;
        return v;
    }
    if (ref >= REF_CUSTOM) {
        const save::CustomMon& m = save::custom_mons()[ref - REF_CUSTOM];
        v.id = m.id;
        v.name = m.name;
        v.where = m.where;
        v.note = m.note;
        v.dex = m.dex;
        v.sprite_dex = m.dex ? m.dex : species_dex_by_name(m.name.c_str());
        for (const std::string& s : m.specs) {
            int i = spec_index_by_id(s);
            if (i >= 0) v.specs.push_back(i);
        }
        v.custom = true;
        return v;
    }
    const gd::Mon& m = gd::MONS[ref];
    v.id = m.id;
    v.name = m.name;
    v.where = m.where ? m.where : "";
    v.role = m.role ? m.role : "";
    v.note = m.note ? m.note : "";
    v.base = m.base ? m.base : "";
    v.dex = m.dex;
    v.sprite_dex = m.sprite_dex;
    for (int i = 0; i < m.n_specs; i++) v.specs.push_back(m.specs[i]);
    v.verify = m.verify;
    return v;
}

static save::Status ref_status(int ref) {
    MonView v = mon_view(ref);
    return v.tracked ? save::mon_status(v.id) : save::NONE;
}

// Sets a status, adding an untracked species to your Pokédex first.
// Returns the reference to use from now on.
static int set_ref_status(int ref, save::Status s) {
    MonView v = mon_view(ref);
    if (!v.tracked) {
        int c = save::add_custom_species(v.dex);
        if (c < 0) return ref;
        ref = REF_CUSTOM + c;
        v = mon_view(ref);
    }
    save::set_mon_status(v.id, s);
    if (s == save::FRIEND) platform::led_flash(0, 255, 0, 250);
    return ref;
}

struct HabView {
    std::string id, name, cond, note;
    uint16_t no = 0;
    std::vector<gd::Req> req;
    std::vector<std::string> req_names;
    std::vector<std::string> residents;
    bool verify = false;
    bool custom = false;
};

static uint8_t tile_for(const std::string& material) {
    std::string s = material;
    for (char& c : s) if (c >= 'A' && c <= 'Z') c += 32;
    auto any = [&](std::initializer_list<const char*> words) {
        for (const char* w : words) if (s.find(w) != std::string::npos) return true;
        return false;
    };
    if (any({"ocean", "sea"})) return gd::T_OCEAN;
    if (any({"water", "pond", "duckweed", "river"})) return gd::T_WATER;
    if (any({"tree", "shrub", "bush"})) return gd::T_TREE;
    if (any({"flower", "blossom", "petal"})) return gd::T_FLOWER;
    if (any({"grass", "hedge", "moss", "leaf", "vine"})) return gd::T_GRASS;
    if (any({"fire", "torch", "lamp", "lantern", "ember"})) return gd::T_FIRE;
    if (any({"rock", "stone", "boulder", "iron", "metal", "ore"})) return gd::T_ROCK;
    if (any({"sand", "soil", "dirt", "mud"})) return gd::T_SOIL;
    return gd::T_WOOD;
}

static int habitat_total() { return gd::N_HABITATS + (int)save::custom_habitats().size(); }

static HabView hab_view(int ref) {
    HabView v;
    if (ref >= REF_CUSTOM) {
        const save::CustomHabitat& h = save::custom_habitats()[ref - REF_CUSTOM];
        v.id = h.id;
        v.name = h.name;
        v.cond = h.cond;
        v.no = h.no;
        for (const auto& r : h.req) {
            v.req_names.push_back(r.first);
            v.req.push_back({nullptr, (uint8_t)r.second, tile_for(r.first)});
        }
        v.residents = h.residents;
        v.custom = true;
    } else {
        const gd::Habitat& h = gd::HABITATS[ref];
        v.id = h.id;
        v.name = h.name;
        v.cond = h.cond ? h.cond : "";
        v.note = h.note ? h.note : "";
        v.no = h.no;
        for (int i = 0; i < h.n_req; i++) {
            v.req.push_back(h.req[i]);
            v.req_names.push_back(h.req[i].material);
        }
        for (int i = 0; i < h.n_res; i++) v.residents.push_back(h.residents[i]);
        v.verify = h.verify;
    }
    for (size_t i = 0; i < v.req.size(); i++) v.req[i].material = v.req_names[i].c_str();
    return v;
}

static std::vector<int> habitat_refs() {
    std::vector<int> refs;
    for (int i = 0; i < gd::N_HABITATS; i++) refs.push_back(i);
    for (size_t j = 0; j < save::custom_habitats().size(); j++) refs.push_back(REF_CUSTOM + (int)j);
    std::stable_sort(refs.begin(), refs.end(), [](int a, int b) {
        uint16_t na = hab_view(a).no, nb = hab_view(b).no;
        return (na ? na : 9999) < (nb ? nb : 9999);
    });
    return refs;
}

static std::string dex_label(uint16_t dex) {
    if (!dex) return "Pokopia";
    char buf[8];
    snprintf(buf, sizeof buf, "#%03u", dex);
    return buf;
}

static const char* status_text(save::Status s) {
    return s == save::FRIEND ? "Befriended" : s == save::SEEN ? "Seen" : "Not met";
}

static save::Status next_status(save::Status s) {
    return s == save::NONE ? save::SEEN : s == save::SEEN ? save::FRIEND : save::NONE;
}

// ===================================================================
// Navigation
// ===================================================================

enum Scr {
    S_HOME, S_DEX, S_MON, S_HABITATS, S_HABITAT, S_MOVES, S_MOVE, S_SPECS, S_SPEC,
    S_AREAS, S_AREA, S_COOKING, S_TIPS, S_REQUESTS, S_SETTINGS, S_CALIBRATE, S_QR, S_ABOUT
};

struct Nav {
    Scr scr;
    int param;
    int32_t scroll;
};

static Nav stack[12];
static int depth = 0;
static lv_obj_t* scroller = nullptr;  // the current screen's scrolling area
static bool build_pending = false;

static void build_current();

static void remember_scroll() {
    if (depth > 0 && scroller) stack[depth - 1].scroll = lv_obj_get_scroll_y(scroller);
}

static void build_async(void*) {
    build_pending = false;
    build_current();
}

static void schedule_build() {
    if (build_pending) return;
    build_pending = true;
    lv_async_call(build_async, nullptr);
}

static void go(Scr s, int param = 0) {
    remember_scroll();
    if (depth < (int)(sizeof stack / sizeof stack[0])) depth++;
    stack[depth - 1] = {s, param, 0};
    schedule_build();
}

static void back() {
    if (depth > 1) depth--;
    schedule_build();
}

static void reload(bool keep_scroll = true) {
    if (keep_scroll) remember_scroll();
    else stack[depth - 1].scroll = 0;
    schedule_build();
}

static void go_home() {
    depth = 1;
    stack[0] = {S_HOME, 0, 0};
    schedule_build();
}

// ===================================================================
// Widgets
// ===================================================================

static lv_obj_t* plain(lv_obj_t* parent) {
    lv_obj_t* o = lv_obj_create(parent);
    lv_obj_remove_style_all(o);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(o, LV_OBJ_FLAG_EVENT_BUBBLE);
    return o;
}

static lv_obj_t* box(lv_obj_t* parent, lv_flex_flow_t flow, int32_t gap) {
    lv_obj_t* o = plain(parent);
    lv_obj_set_size(o, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(o, flow);
    lv_obj_set_style_pad_row(o, gap, 0);
    lv_obj_set_style_pad_column(o, gap, 0);
    return o;
}

static lv_obj_t* text(lv_obj_t* parent, const char* str, const lv_font_t* font = &font_body_14,
                      lv_color_t color = P.ink, bool wrap = true) {
    lv_obj_t* l = lv_label_create(parent);
    lv_label_set_text(l, str ? str : "");
    lv_obj_set_style_text_font(l, font, 0);
    lv_obj_set_style_text_color(l, color, 0);
    if (wrap) {
        lv_obj_set_width(l, lv_pct(100));
        lv_label_set_long_mode(l, LV_LABEL_LONG_WRAP);
    }
    return l;
}

static lv_obj_t* text_dot(lv_obj_t* parent, const char* str, const lv_font_t* font, lv_color_t color) {
    lv_obj_t* l = text(parent, str, font, color, false);
    lv_label_set_long_mode(l, LV_LABEL_LONG_DOT);
    lv_obj_set_width(l, lv_pct(100));
    return l;
}

static lv_obj_t* eyebrow(lv_obj_t* parent, const char* str) {
    lv_obj_t* l = text(parent, str, &font_small_12, P.moss);
    lv_obj_set_style_text_letter_space(l, 1, 0);
    return l;
}

static void style_clickable(lv_obj_t* o, lv_style_t* base) {
    lv_obj_add_style(o, base, 0);
    lv_obj_add_style(o, &theme::pressed, LV_STATE_PRESSED);
    lv_obj_add_flag(o, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_EVENT_BUBBLE);
}

static lv_obj_t* button(lv_obj_t* parent, const char* label, lv_event_cb_t cb, void* user = nullptr,
                        bool primary = false) {
    lv_obj_t* b = plain(parent);
    style_clickable(b, &theme::btn);
    if (primary) lv_obj_add_style(b, &theme::btn_primary, 0);
    lv_obj_set_size(b, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_t* l = lv_label_create(b);
    lv_label_set_text(l, label);
    lv_obj_center(l);
    lv_obj_add_event_cb(b, cb, LV_EVENT_CLICKED, user);
    return b;
}

static void set_button_text(lv_obj_t* b, const char* label) {
    lv_label_set_text(lv_obj_get_child(b, 0), label);
}

static lv_obj_t* chip(lv_obj_t* parent, const char* label, lv_style_t* extra = nullptr) {
    lv_obj_t* c = plain(parent);
    lv_obj_add_style(c, &theme::chip, 0);
    if (extra) lv_obj_add_style(c, extra, 0);
    lv_obj_set_size(c, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_t* l = lv_label_create(c);
    lv_label_set_text(l, label);
    return c;
}

static void verify_badge(lv_obj_t* parent) {
    lv_obj_t* b = plain(parent);
    lv_obj_add_style(b, &theme::badge_warn, 0);
    lv_obj_set_size(b, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_label_set_text(lv_label_create(b), "CHECK IN-GAME");
}

static void verify_note(lv_obj_t* parent, const std::string& note) {
    verify_badge(parent);
    if (!note.empty()) text(parent, note.c_str(), &font_small_12, P.ink_soft);
}

// A block like the ones habitats are built from.
static lv_obj_t* block(lv_obj_t* parent, uint8_t tile, int32_t size) {
    lv_obj_t* b = plain(parent);
    lv_obj_set_size(b, size, size);
    lv_color_t c = theme::tile_color(tile);
    lv_obj_set_style_bg_color(b, c, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(b, size > 14 ? 4 : 2, 0);
    lv_obj_set_style_border_side(b, LV_BORDER_SIDE_BOTTOM, 0);
    lv_obj_set_style_border_width(b, size / 5 > 1 ? size / 5 : 2, 0);
    lv_obj_set_style_border_color(b, lv_color_darken(c, 50), 0);
    return b;
}

static const gd::SpriteRef* builtin_sprite(uint16_t dex) {
    int lo = 0, hi = gd::N_BUILTIN_SPRITES - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (gd::BUILTIN_SPRITES[mid].dex == dex) return &gd::BUILTIN_SPRITES[mid];
        if (gd::BUILTIN_SPRITES[mid].dex < dex) lo = mid + 1; else hi = mid - 1;
    }
    return nullptr;
}

enum SpriteSize { SPR_LARGE, SPR_SMALL, SPR_CHIP };

// Pokémon picture: from flash for guide Pokémon, from the SD card for the
// rest, or the first letter of the name when there is no picture.
static lv_obj_t* sprite(lv_obj_t* parent, uint16_t dex, const std::string& name, SpriteSize size,
                        bool highlight = false) {
    static const int32_t dims[3][2] = {{96, 90}, {36, 34}, {32, 30}};
    lv_obj_t* frame = plain(parent);
    lv_obj_set_size(frame, dims[size][0], dims[size][1]);
    if (size != SPR_CHIP) {
        lv_obj_add_style(frame, &theme::sprite_box, 0);
        if (highlight) lv_obj_set_style_bg_color(frame, P.moss_soft, 0);
    }
    const gd::SpriteRef* ref = dex ? builtin_sprite(dex) : nullptr;
    lv_obj_t* img = nullptr;
    if (ref) {
        img = lv_image_create(frame);
        lv_image_set_src(img, size == SPR_LARGE ? ref->large : ref->small);
    } else if (dex && platform::sd_has_sprites()) {
        char path[40];
        snprintf(path, sizeof path, "S:/pokopia/sprites/%04u%s.bin", dex, size == SPR_LARGE ? "" : "s");
        img = lv_image_create(frame);
        lv_image_set_src(img, path);
    }
    if (img) {
        lv_obj_center(img);
    } else {
        char initial[2] = {name.empty() ? '?' : name[0], 0};
        lv_obj_t* l = lv_label_create(frame);
        lv_label_set_text(l, initial);
        lv_obj_set_style_text_font(l, size == SPR_LARGE ? &font_hero_26 : &font_bold_14, 0);
        lv_obj_set_style_text_color(l, P.ink_soft, 0);
        lv_obj_center(l);
    }
    return frame;
}

// --- Screen frame: header with back button and title, then a body ---

static lv_obj_t* current_screen = nullptr;

static void on_back(lv_event_t*) { back(); }

struct Frame {
    lv_obj_t* header;
    lv_obj_t* right;   // header area for an action
    lv_obj_t* body;    // scrolling content
    lv_obj_t* bar;     // optional bottom bar
};

static Frame frame(const char* title, bool with_bar = false) {
    Frame f{};
    lv_obj_t* scr = current_screen;

    f.header = plain(scr);
    lv_obj_add_style(f.header, &theme::header, 0);
    lv_obj_set_size(f.header, W, HEADER_H);
    lv_obj_set_flex_flow(f.header, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(f.header, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);

    lv_obj_t* back_btn = plain(f.header);
    lv_obj_set_size(back_btn, 34, 30);
    style_clickable(back_btn, &theme::btn);
    lv_obj_set_style_pad_all(back_btn, 0, 0);
    lv_obj_t* arrow = lv_label_create(back_btn);
    lv_label_set_text(arrow, LV_SYMBOL_LEFT);
    lv_obj_center(arrow);
    lv_obj_add_event_cb(back_btn, on_back, LV_EVENT_CLICKED, nullptr);

    lv_obj_t* t = text_dot(f.header, title, &font_title_20, P.ink);
    lv_obj_set_width(t, 1);
    lv_obj_set_flex_grow(t, 1);

    f.right = plain(f.header);
    lv_obj_set_size(f.right, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(f.right, LV_FLEX_FLOW_ROW);
    lv_obj_set_style_pad_column(f.right, 4, 0);

    int32_t body_h = H - HEADER_H - (with_bar ? BAR_H : 0);
    f.body = plain(scr);
    lv_obj_add_flag(f.body, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_remove_flag(f.body, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_set_scroll_dir(f.body, LV_DIR_VER);
    lv_obj_add_style(f.body, &theme::content, 0);
    lv_obj_add_style(f.body, &theme::scrollbar, LV_PART_SCROLLBAR);
    lv_obj_set_size(f.body, W, body_h);
    lv_obj_set_pos(f.body, 0, HEADER_H);
    lv_obj_set_flex_flow(f.body, LV_FLEX_FLOW_COLUMN);
    scroller = f.body;

    if (with_bar) {
        f.bar = plain(scr);
        lv_obj_set_size(f.bar, W, BAR_H);
        lv_obj_set_pos(f.bar, 0, H - BAR_H);
        lv_obj_set_style_bg_color(f.bar, P.surface, 0);
        lv_obj_set_style_bg_opa(f.bar, LV_OPA_COVER, 0);
        lv_obj_set_style_border_side(f.bar, LV_BORDER_SIDE_TOP, 0);
        lv_obj_set_style_border_width(f.bar, 1, 0);
        lv_obj_set_style_border_color(f.bar, P.line, 0);
        lv_obj_set_style_pad_hor(f.bar, 8, 0);
        lv_obj_set_style_pad_column(f.bar, 8, 0);
        lv_obj_set_flex_flow(f.bar, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(f.bar, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    }
    return f;
}

// A tappable list row laid out left to right.
static lv_obj_t* list_row(lv_obj_t* parent, lv_event_cb_t cb, intptr_t param, int32_t height = 44) {
    lv_obj_t* r = plain(parent);
    style_clickable(r, &theme::row);
    lv_obj_set_size(r, lv_pct(100), height);
    lv_obj_set_flex_flow(r, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_add_event_cb(r, cb, LV_EVENT_CLICKED, (void*)param);
    return r;
}

// The growing middle column of a row: a title line and a detail line.
static lv_obj_t* row_text(lv_obj_t* row, const std::string& title, const std::string& detail,
                          const lv_font_t* detail_font = &font_small_12) {
    lv_obj_t* col = plain(row);
    lv_obj_set_height(col, LV_SIZE_CONTENT);
    lv_obj_set_width(col, 1);
    lv_obj_set_flex_grow(col, 1);
    lv_obj_set_flex_flow(col, LV_FLEX_FLOW_COLUMN);
    text_dot(col, title.c_str(), &font_bold_14, P.ink);
    if (!detail.empty()) text_dot(col, detail.c_str(), detail_font, P.ink_soft);
    return col;
}

static void chevron(lv_obj_t* row) {
    lv_obj_t* l = lv_label_create(row);
    lv_label_set_text(l, LV_SYMBOL_RIGHT);
    lv_obj_set_style_text_color(l, P.ink_soft, 0);
    lv_obj_set_style_text_font(l, &font_small_12, 0);
}

// A checkbox drawn to match the rest of the UI.
static lv_obj_t* check(lv_obj_t* parent, bool on, lv_event_cb_t cb, intptr_t param) {
    lv_obj_t* c = plain(parent);
    lv_obj_set_size(c, 26, 26);
    lv_obj_set_ext_click_area(c, 8);
    style_clickable(c, &theme::btn);
    lv_obj_set_style_pad_all(c, 0, 0);
    lv_obj_t* mark = lv_label_create(c);
    lv_label_set_text(mark, LV_SYMBOL_OK);
    lv_obj_center(mark);
    if (on) lv_obj_add_style(c, &theme::btn_primary, 0);
    else lv_obj_add_flag(mark, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_event_cb(c, cb, LV_EVENT_CLICKED, (void*)param);
    return c;
}

static void set_check(lv_obj_t* c, bool on) {
    lv_obj_t* mark = lv_obj_get_child(c, 0);
    if (on) {
        lv_obj_add_style(c, &theme::btn_primary, 0);
        lv_obj_remove_flag(mark, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_remove_style(c, &theme::btn_primary, 0);
        lv_obj_add_flag(mark, LV_OBJ_FLAG_HIDDEN);
    }
}

static intptr_t param_of(lv_event_t* e) { return (intptr_t)lv_event_get_user_data(e); }

// Pokémon chip: small picture and name, opens that Pokémon.
static void on_open_mon(lv_event_t* e) { go(S_MON, (int)param_of(e)); }

static void mon_chip(lv_obj_t* parent, const char* name) {
    int ref = ref_for_name(name);
    lv_obj_t* c = plain(parent);
    style_clickable(c, &theme::chip);
    lv_obj_set_size(c, LV_SIZE_CONTENT, 32);
    lv_obj_set_style_pad_left(c, 2, 0);
    lv_obj_set_style_pad_ver(c, 1, 0);
    lv_obj_set_flex_flow(c, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(c, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(c, 3, 0);
    MonView v = ref >= 0 ? mon_view(ref) : MonView();
    sprite(c, ref >= 0 ? v.sprite_dex : 0, name, SPR_CHIP);
    lv_label_set_text(lv_label_create(c), name);
    if (ref >= 0) lv_obj_add_event_cb(c, on_open_mon, LV_EVENT_CLICKED, (void*)(intptr_t)ref);
    else lv_obj_remove_flag(c, LV_OBJ_FLAG_CLICKABLE);
}

static lv_obj_t* wrap_row(lv_obj_t* parent) {
    lv_obj_t* r = box(parent, LV_FLEX_FLOW_ROW_WRAP, 4);
    return r;
}

// Two-tap confirmation for actions that replace or erase progress.
static lv_obj_t* armed_btn = nullptr;
static lv_timer_t* disarm_timer = nullptr;
static std::string armed_label;

static void disarm(lv_timer_t*) {
    if (armed_btn) {
        lv_obj_remove_style(armed_btn, &theme::btn_danger, 0);
        set_button_text(armed_btn, armed_label.c_str());
    }
    armed_btn = nullptr;
    disarm_timer = nullptr;
}

static bool confirm_tap(lv_obj_t* b, const char* armed_text) {
    if (armed_btn == b) {
        if (disarm_timer) lv_timer_delete(disarm_timer);
        disarm_timer = nullptr;
        armed_btn = nullptr;
        return true;
    }
    if (armed_btn) disarm(nullptr);
    armed_btn = b;
    armed_label = lv_label_get_text(lv_obj_get_child(b, 0));
    lv_obj_add_style(b, &theme::btn_danger, 0);
    set_button_text(b, armed_text);
    if (disarm_timer) lv_timer_delete(disarm_timer);
    disarm_timer = lv_timer_create(disarm, 4000, nullptr);
    lv_timer_set_repeat_count(disarm_timer, 1);
    return false;
}

// ===================================================================
// Home
// ===================================================================

// 20 x 3 restoration map. g grass, t tree, f flower, w water.
static const char ISLAND[] =
    "ggtgggfggtggfgggtggf"
    "gfggggwwgggtggwwgggg"
    "tgggfwwwggfgggwwwggt";
static const int ISLAND_N = 60;

static void island_rank(int* rank) {
    int order[ISLAND_N];
    for (int i = 0; i < ISLAND_N; i++) order[i] = i;
    uint32_t seed = 11;
    for (int i = ISLAND_N - 1; i > 0; i--) {
        seed = (uint32_t)(((uint64_t)seed * 16807u) % 2147483647u);
        int j = (int)((double)seed / 2147483647.0 * (i + 1));
        int t = order[i]; order[i] = order[j]; order[j] = t;
    }
    for (int pos = 0; pos < ISLAND_N; pos++) rank[order[pos]] = pos;
}

static void on_home_tile(lv_event_t* e) { go((Scr)param_of(e)); }
static void on_settings(lv_event_t*) { go(S_SETTINGS); }

static void home_tile(lv_obj_t* parent, Scr target, const char* label, const char* value,
                      uint16_t sprite_dex, int tile) {
    lv_obj_t* t = plain(parent);
    style_clickable(t, &theme::row);
    lv_obj_set_size(t, 152, 35);
    lv_obj_set_style_pad_hor(t, 4, 0);
    lv_obj_set_style_pad_ver(t, 0, 0);
    lv_obj_set_style_pad_column(t, 6, 0);
    lv_obj_set_flex_flow(t, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(t, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_add_event_cb(t, on_home_tile, LV_EVENT_CLICKED, (void*)(intptr_t)target);
    if (sprite_dex) {
        sprite(t, sprite_dex, label, SPR_CHIP);
    } else {
        lv_obj_t* holder = plain(t);
        lv_obj_set_size(holder, 30, 28);
        lv_obj_t* b = block(holder, (uint8_t)tile, 20);
        lv_obj_center(b);
    }
    lv_obj_t* l = text_dot(t, label, &font_bold_14, P.ink);
    lv_obj_set_width(l, 1);
    lv_obj_set_flex_grow(l, 1);
    text(t, value, &font_mono_12, P.ink_soft, false);
}

static void build_home() {
    lv_obj_t* scr = current_screen;
    scroller = nullptr;
    save::Progress pr = save::progress();

    // Header: sprout logo, name, restoration percentage and settings.
    lv_obj_t* head = plain(scr);
    lv_obj_set_size(head, W - 12, 32);
    lv_obj_set_pos(head, 6, 4);
    lv_obj_set_flex_flow(head, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(head, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(head, 6, 0);

    lv_obj_t* logo = plain(head);
    lv_obj_set_size(logo, 24, 24);
    struct { int x, y, w, h; uint8_t tile; } parts[] = {
        {11, 6, 2, 10, gd::T_TREE}, {6, 5, 5, 3, gd::T_GRASS}, {13, 2, 5, 3, gd::T_GRASS},
        {1, 15, 22, 3, gd::T_GRASS}, {1, 18, 22, 5, gd::T_SOIL},
    };
    for (auto& p : parts) {
        lv_obj_t* r = plain(logo);
        lv_obj_set_pos(r, p.x, p.y);
        lv_obj_set_size(r, p.w, p.h);
        lv_obj_set_style_bg_color(r, theme::tile_color(p.tile), 0);
        lv_obj_set_style_bg_opa(r, LV_OPA_COVER, 0);
    }
    lv_obj_t* name = text(head, "Pokopia", &font_title_20, P.ink, false);
    lv_obj_set_flex_grow(name, 1);

    char pct[8];
    snprintf(pct, sizeof pct, "%d%%", pr.pct);
    lv_obj_t* pct_col = plain(head);
    lv_obj_set_size(pct_col, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(pct_col, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(pct_col, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(pct_col, 4, 0);
    text(pct_col, pct, &font_title_20, P.moss, false);
    text(pct_col, "restored", &font_small_12, P.ink_soft, false);

    lv_obj_t* gear = button(head, LV_SYMBOL_SETTINGS, on_settings);
    lv_obj_set_size(gear, 32, 30);
    lv_obj_set_style_pad_all(gear, 0, 0);

    // Restoration map: green spreads as progress grows.
    lv_obj_t* map = plain(scr);
    lv_obj_set_size(map, 20 * 13 + 19 * 2, 3 * 13 + 2 * 2);
    lv_obj_set_pos(map, (W - (20 * 13 + 19 * 2)) / 2, 40);
    int rank[ISLAND_N];
    island_rank(rank);
    int green = pr.pct > 0 ? std::max(1, (int)(pr.pct / 100.0 * ISLAND_N + 0.5)) : 0;
    for (int i = 0; i < ISLAND_N; i++) {
        uint8_t tile;
        if (rank[i] < green) {
            char c = ISLAND[i];
            tile = c == 't' ? gd::T_TREE : c == 'f' ? gd::T_FLOWER : c == 'w' ? gd::T_WATER : gd::T_GRASS;
        } else {
            tile = (i % 11 == 3) ? gd::T_ROCK : gd::T_SOIL;
        }
        lv_obj_t* b = block(map, tile, 13);
        lv_obj_set_pos(b, (i % 20) * 15, (i / 20) * 15);
        if (rank[i] >= green && tile == gd::T_SOIL && (i * 7) % 3 == 0)
            lv_obj_set_style_bg_color(b, lv_color_hex(0xa88a62), 0);
    }

    // Sections: two columns of wide tiles.
    lv_obj_t* grid = plain(scr);
    lv_obj_set_size(grid, 308, 152);
    lv_obj_set_pos(grid, 6, 87);
    lv_obj_set_flex_flow(grid, LV_FLEX_FLOW_ROW_WRAP);
    lv_obj_set_style_pad_row(grid, 4, 0);
    lv_obj_set_style_pad_column(grid, 4, 0);

    char v[4][16];
    snprintf(v[0], 16, "%d/%d", pr.friends, pr.mons);
    snprintf(v[1], 16, "%d/%d", pr.built, pr.habs);
    snprintf(v[2], 16, "%d/%d", pr.moves, pr.moves_total);
    snprintf(v[3], 16, "%d/%d", pr.restored, pr.areas_total);
    int open = 0;
    for (const auto& r : save::requests()) if (!r.done) open++;
    char reqs[16], specs[8], tips[8];
    snprintf(reqs, sizeof reqs, "%d", open);
    snprintf(specs, sizeof specs, "%d", gd::N_SPECS);
    snprintf(tips, sizeof tips, "%d", gd::N_TIPS);

    home_tile(grid, S_DEX, "Pokédex", v[0], 1, 0);
    home_tile(grid, S_HABITATS, "Habitats", v[1], 0, gd::T_GRASS);
    home_tile(grid, S_MOVES, "Moves", v[2], 7, 0);
    home_tile(grid, S_AREAS, "Areas", v[3], 0, gd::T_SOIL);
    home_tile(grid, S_SPECS, "Specialties", specs, 25, 0);
    char meals[8];
    snprintf(meals, sizeof meals, "%d", gd::N_MEALS);
    home_tile(grid, S_COOKING, "Cooking", meals, 0, gd::T_FIRE);
    home_tile(grid, S_REQUESTS, "Requests", reqs, 570, 0);
    home_tile(grid, S_TIPS, "Field tips", tips, 0, gd::T_FLOWER);
}

// ===================================================================
// Pokédex
// ===================================================================

enum DexMode { DEX_GUIDE, DEX_FRIENDS, DEX_ALL };
static int dex_mode = DEX_GUIDE;
static int dex_page = 0;
static const int DEX_PAGE_SIZE = 20;

static void apply_status_chip(lv_obj_t* c, save::Status s) {
    lv_obj_remove_style(c, &theme::chip_friend, 0);
    lv_obj_remove_style(c, &theme::chip_seen, 0);
    if (s == save::FRIEND) lv_obj_add_style(c, &theme::chip_friend, 0);
    if (s == save::SEEN) lv_obj_add_style(c, &theme::chip_seen, 0);
    lv_label_set_text(lv_obj_get_child(c, 0), status_text(s));
}

static void on_status_chip(lv_event_t* e) {
    lv_obj_t* c = lv_event_get_target_obj(e);
    int ref = (int)(intptr_t)lv_obj_get_user_data(c);
    save::Status s = next_status(ref_status(ref));
    ref = set_ref_status(ref, s);
    lv_obj_set_user_data(c, (void*)(intptr_t)ref);
    apply_status_chip(c, s);
    lv_obj_t* row = lv_obj_get_parent(c);
    lv_obj_t* pic = lv_obj_get_child(row, 0);
    lv_obj_set_style_bg_color(pic, s == save::FRIEND ? P.moss_soft : P.surface2, 0);
}

static void on_open_mon_row(lv_event_t* e) { go(S_MON, (int)param_of(e)); }

static void mon_row(lv_obj_t* parent, int ref) {
    MonView v = mon_view(ref);
    save::Status s = ref_status(ref);
    lv_obj_t* r = list_row(parent, on_open_mon_row, ref);
    lv_obj_set_style_pad_left(r, 3, 0);
    sprite(r, v.sprite_dex, v.name, SPR_SMALL, s == save::FRIEND);
    row_text(r, v.name, dex_label(v.dex), &font_mono_12);
    lv_obj_t* c = chip(r, status_text(s));
    lv_obj_add_flag(c, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_remove_flag(c, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_set_ext_click_area(c, 6);
    lv_obj_add_style(c, &theme::pressed, LV_STATE_PRESSED);
    lv_obj_set_user_data(c, (void*)(intptr_t)ref);
    apply_status_chip(c, s);
    lv_obj_add_event_cb(c, on_status_chip, LV_EVENT_CLICKED, nullptr);
}

static std::vector<int> guide_refs() {
    std::vector<int> refs;
    for (int i = 0; i < gd::N_MONS; i++) refs.push_back(i);
    for (size_t j = 0; j < save::custom_mons().size(); j++) refs.push_back(REF_CUSTOM + (int)j);
    std::stable_sort(refs.begin(), refs.end(), [](int a, int b) {
        uint16_t da = mon_view(a).dex, db = mon_view(b).dex;
        return (da ? da : 9999) < (db ? db : 9999);
    });
    return refs;
}

static void on_dex_mode(lv_event_t*) {
    dex_mode = (dex_mode + 1) % 3;
    dex_page = 0;
    reload(false);
}

static void on_dex_page(lv_event_t* e) {
    int pages = (gd::N_SPECIES + DEX_PAGE_SIZE - 1) / DEX_PAGE_SIZE;
    dex_page = (dex_page + (int)param_of(e) + pages) % pages;
    reload(false);
}

static void build_dex() {
    Frame f = frame("Pokédex", dex_mode == DEX_ALL);
    static const char* modes[] = {"Guide", "Friends", "All"};
    lv_obj_t* m = button(f.right, modes[dex_mode], on_dex_mode);
    lv_obj_set_style_pad_ver(m, 4, 0);

    save::Progress pr = save::progress();
    if (dex_mode == DEX_ALL) {
        int first = dex_page * DEX_PAGE_SIZE + 1;
        int last = std::min(first + DEX_PAGE_SIZE - 1, (int)gd::N_SPECIES);
        if (!platform::sd_has_sprites() && dex_page == 0)
            text(f.body, "Add the SD card pack to see every Pokémon's picture.", &font_small_12, P.ink_soft);
        for (int d = first; d <= last; d++) mon_row(f.body, ref_for_dex(d));
        button(f.bar, LV_SYMBOL_LEFT " Prev", on_dex_page, (void*)(intptr_t)-1);
        char range[40];
        snprintf(range, sizeof range, "#%03d - #%03d", first, last);
        text(f.bar, range, &font_mono_12, P.ink_soft, false);
        button(f.bar, "Next " LV_SYMBOL_RIGHT, on_dex_page, (void*)(intptr_t)1);
        return;
    }
    char line[64];
    snprintf(line, sizeof line, "%d of %d befriended. Tap a status to change it.", pr.friends, pr.mons);
    text(f.body, line, &font_small_12, P.ink_soft);
    int shown = 0;
    for (int ref : guide_refs()) {
        if (dex_mode == DEX_FRIENDS && ref_status(ref) != save::FRIEND) continue;
        mon_row(f.body, ref);
        shown++;
    }
    if (!shown)
        text(f.body, "No befriended Pokémon yet. Mark them in the Guide list.", &font_body_14, P.ink_soft);
}

static void on_mon_status(lv_event_t* e) {
    lv_obj_t* b = lv_event_get_target_obj(e);
    int ref = stack[depth - 1].param;
    save::Status s = next_status(ref_status(ref));
    ref = set_ref_status(ref, s);
    stack[depth - 1].param = ref;
    lv_obj_remove_style(b, &theme::btn_primary, 0);
    lv_obj_remove_style(b, &theme::chip_seen, 0);
    if (s == save::FRIEND) lv_obj_add_style(b, &theme::btn_primary, 0);
    if (s == save::SEEN) lv_obj_add_style(b, &theme::chip_seen, 0);
    set_button_text(b, status_text(s));
    lv_obj_t* pic = (lv_obj_t*)lv_obj_get_user_data(b);
    lv_obj_set_style_bg_color(pic, s == save::FRIEND ? P.moss_soft : P.surface2, 0);
}

static void on_open_spec(lv_event_t* e) { go(S_SPEC, (int)param_of(e)); }

static void build_mon(int ref) {
    MonView v = mon_view(ref);
    save::Status s = ref_status(ref);
    Frame f = frame(v.name.c_str());
    lv_obj_set_flex_flow(f.body, LV_FLEX_FLOW_ROW);
    lv_obj_remove_flag(f.body, LV_OBJ_FLAG_SCROLLABLE);
    scroller = nullptr;

    lv_obj_t* left = plain(f.body);
    lv_obj_set_size(left, 96, lv_pct(100));
    lv_obj_set_flex_flow(left, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(left, 6, 0);
    lv_obj_t* pic = sprite(left, v.sprite_dex, v.name, SPR_LARGE, s == save::FRIEND);
    lv_obj_t* sb = button(left, status_text(s), on_mon_status);
    lv_obj_set_width(sb, 96);
    if (s == save::FRIEND) lv_obj_add_style(sb, &theme::btn_primary, 0);
    if (s == save::SEEN) lv_obj_add_style(sb, &theme::chip_seen, 0);
    lv_obj_set_user_data(sb, pic);
    lv_obj_t* num = text(left, dex_label(v.dex).c_str(), &font_mono_12, P.ink_soft, false);
    lv_obj_set_style_text_align(num, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_width(num, 96);

    lv_obj_t* right = plain(f.body);
    lv_obj_add_flag(right, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_remove_flag(right, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_set_scroll_dir(right, LV_DIR_VER);
    lv_obj_add_style(right, &theme::scrollbar, LV_PART_SCROLLBAR);
    lv_obj_set_height(right, lv_pct(100));
    lv_obj_set_width(right, 1);
    lv_obj_set_flex_grow(right, 1);
    lv_obj_set_flex_flow(right, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(right, 6, 0);
    lv_obj_set_style_pad_left(right, 6, 0);
    scroller = right;

    if (!v.specs.empty()) {
        lv_obj_t* specs = wrap_row(right);
        for (int i : v.specs) {
            lv_obj_t* c = chip(specs, gd::SPECS[i].name);
            style_clickable(c, &theme::chip);
            lv_obj_add_event_cb(c, on_open_spec, LV_EVENT_CLICKED, (void*)(intptr_t)i);
        }
    }
    bool any = !v.specs.empty();
    if (!v.where.empty()) {
        std::string w = "Found: " + v.where;
        text(right, w.c_str());
        any = true;
    }
    if (!v.role.empty()) { text(right, v.role.c_str()); any = true; }
    if (!v.note.empty() && !v.verify) { text(right, v.note.c_str(), &font_body_14, P.ink_soft); any = true; }
    if (!v.base.empty()) {
        std::string b = "Pokopia's own character. The picture shows " + v.base + ".";
        text(right, b.c_str(), &font_small_12, P.ink_soft);
        any = true;
    }
    if (v.verify) { verify_note(right, v.note); any = true; }
    if (v.custom) text(right, "Added to your Pokédex.", &font_small_12, P.ink_soft);
    if (!any && !v.custom)
        text(right, v.tracked ? "No guide notes yet." :
                    "Not in your Pokédex yet. Tap the status button to add it.",
             &font_body_14, P.ink_soft);
}

// ===================================================================
// Habitats
// ===================================================================

static void on_open_habitat(lv_event_t* e) { go(S_HABITAT, (int)param_of(e)); }

static void build_habitats() {
    Frame f = frame("Habitats");
    save::Progress pr = save::progress();
    char line[96];
    snprintf(line, sizeof line, "%d of %d built. The full Habitat Dex has %u.", pr.built, pr.habs,
             gd::META_HABITAT_TOTAL);
    text(f.body, line, &font_small_12, P.ink_soft);
    for (int ref : habitat_refs()) {
        HabView h = hab_view(ref);
        int n = save::habitat_count(h.id);
        lv_obj_t* r = list_row(f.body, on_open_habitat, ref, 48);
        lv_obj_t* tiles = plain(r);
        lv_obj_set_size(tiles, 34, 34);
        lv_obj_set_flex_flow(tiles, LV_FLEX_FLOW_ROW_WRAP);
        lv_obj_set_style_pad_row(tiles, 2, 0);
        lv_obj_set_style_pad_column(tiles, 2, 0);
        lv_obj_set_flex_align(tiles, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
        for (size_t i = 0; i < h.req.size() && i < 4; i++) block(tiles, h.req[i].tile, 15);
        std::string detail;
        for (size_t i = 0; i < h.req.size(); i++) {
            if (i) detail += ", ";
            detail += h.req_names[i] + " x" + std::to_string(h.req[i].count);
        }
        row_text(r, h.name, detail);
        if (n > 0) {
            char b[16];
            snprintf(b, sizeof b, "x%d", n);
            chip(r, b, &theme::chip_friend);
        }
        if (h.verify) {
            lv_obj_t* dot = plain(r);
            lv_obj_set_size(dot, 8, 8);
            lv_obj_set_style_radius(dot, 4, 0);
            lv_obj_set_style_bg_color(dot, P.warn, 0);
            lv_obj_set_style_bg_opa(dot, LV_OPA_COVER, 0);
        }
    }
}

static lv_obj_t* built_label = nullptr;

static void update_built_label(int n) {
    char b[24];
    if (n == 0) snprintf(b, sizeof b, "Not built yet");
    else snprintf(b, sizeof b, n == 1 ? "Built once" : "Built %d times", n);
    lv_label_set_text(built_label, b);
    lv_obj_set_style_text_color(built_label, n ? P.moss : P.ink_soft, 0);
}

static void on_built(lv_event_t* e) {
    HabView h = hab_view(stack[depth - 1].param);
    int n = std::max(0, std::min(99, save::habitat_count(h.id) + (int)param_of(e)));
    save::set_habitat_count(h.id, n);
    update_built_label(n);
}

static void build_habitat(int ref) {
    HabView h = hab_view(ref);
    Frame f = frame(h.name.c_str(), true);
    if (h.no) {
        char no[16];
        snprintf(no, sizeof no, "No. %03u", h.no);
        text(f.right, no, &font_mono_12, P.ink_soft, false);
    }
    eyebrow(f.body, "WHAT IT NEEDS");
    for (size_t i = 0; i < h.req.size(); i++) {
        lv_obj_t* r = box(f.body, LV_FLEX_FLOW_ROW, 8);
        lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
        block(r, h.req[i].tile, 22);
        lv_obj_t* name = text(r, h.req_names[i].c_str(), &font_bold_14, P.ink, false);
        lv_obj_set_flex_grow(name, 1);
        char count[8];
        snprintf(count, sizeof count, "x%d", h.req[i].count);
        text(r, count, &font_mono_12, P.ink_soft, false);
    }
    eyebrow(f.body, "ATTRACTS");
    if (h.residents.empty()) {
        text(f.body, "Residents not confirmed yet.", &font_body_14, P.ink_soft);
    } else {
        lv_obj_t* w = wrap_row(f.body);
        for (const std::string& r : h.residents) mon_chip(w, r.c_str());
    }
    if (!h.cond.empty()) text(f.body, h.cond.c_str(), &font_body_14, P.ink_soft);
    if (h.verify) verify_note(f.body, h.note);

    built_label = text(f.bar, "", &font_bold_14, P.ink, false);
    lv_obj_set_flex_grow(built_label, 1);
    lv_obj_t* minus = button(f.bar, LV_SYMBOL_MINUS, on_built, (void*)(intptr_t)-1);
    lv_obj_set_size(minus, 44, 32);
    lv_obj_t* plus = button(f.bar, LV_SYMBOL_PLUS, on_built, (void*)(intptr_t)1, true);
    lv_obj_set_size(plus, 44, 32);
    update_built_label(save::habitat_count(h.id));
}

// ===================================================================
// Ditto's moves
// ===================================================================

static void on_move_check(lv_event_t* e) {
    int i = (int)param_of(e);
    bool on = !save::move_learned(gd::MOVES[i].id);
    save::set_move_learned(gd::MOVES[i].id, on);
    set_check(lv_event_get_target_obj(e), on);
}

static void on_open_move(lv_event_t* e) { go(S_MOVE, (int)param_of(e)); }

static void build_moves() {
    Frame f = frame("Ditto's moves");
    save::Progress pr = save::progress();
    char line[80];
    snprintf(line, sizeof line, "%d of %d learned. Moves use PP: eat a meal or rest at a Pokémon Center.",
             pr.moves, pr.moves_total);
    text(f.body, line, &font_small_12, P.ink_soft);
    for (int i = 0; i < gd::N_MOVES; i++) {
        const gd::Move& m = gd::MOVES[i];
        lv_obj_t* r = list_row(f.body, on_open_move, i, 46);
        check(r, save::move_learned(m.id), on_move_check, i);
        sprite(r, m.from_dex, m.from, SPR_SMALL);
        std::string detail = std::string("From ") + m.from + (m.meal ? std::string(" · ") + m.meal : "");
        row_text(r, m.name, detail);
        if (m.verify) {
            lv_obj_t* dot = plain(r);
            lv_obj_set_size(dot, 8, 8);
            lv_obj_set_style_radius(dot, 4, 0);
            lv_obj_set_style_bg_color(dot, P.warn, 0);
            lv_obj_set_style_bg_opa(dot, LV_OPA_COVER, 0);
        }
    }
}

static void on_move_learned(lv_event_t* e) {
    int i = stack[depth - 1].param;
    bool on = !save::move_learned(gd::MOVES[i].id);
    save::set_move_learned(gd::MOVES[i].id, on);
    lv_obj_t* b = lv_event_get_target_obj(e);
    set_button_text(b, on ? LV_SYMBOL_OK " Learned" : "Not learned");
    if (on) lv_obj_add_style(b, &theme::btn_primary, 0);
    else lv_obj_remove_style(b, &theme::btn_primary, 0);
}

static void build_move(int i) {
    const gd::Move& m = gd::MOVES[i];
    Frame f = frame(m.name);
    lv_obj_set_flex_flow(f.body, LV_FLEX_FLOW_ROW);
    lv_obj_remove_flag(f.body, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t* left = plain(f.body);
    lv_obj_set_size(left, 96, lv_pct(100));
    lv_obj_set_flex_flow(left, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(left, 6, 0);
    int ref = ref_for_name(m.from);
    lv_obj_t* pic = sprite(left, m.from_dex, m.from, SPR_LARGE);
    style_clickable(pic, &theme::sprite_box);
    lv_obj_add_event_cb(pic, on_open_mon, LV_EVENT_CLICKED, (void*)(intptr_t)ref);
    bool on = save::move_learned(m.id);
    lv_obj_t* b = button(left, on ? LV_SYMBOL_OK " Learned" : "Not learned", on_move_learned, nullptr, on);
    lv_obj_set_width(b, 96);

    lv_obj_t* right = plain(f.body);
    lv_obj_add_flag(right, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_remove_flag(right, LV_OBJ_FLAG_EVENT_BUBBLE);
    lv_obj_set_scroll_dir(right, LV_DIR_VER);
    lv_obj_add_style(right, &theme::scrollbar, LV_PART_SCROLLBAR);
    lv_obj_set_height(right, lv_pct(100));
    lv_obj_set_width(right, 1);
    lv_obj_set_flex_grow(right, 1);
    lv_obj_set_flex_flow(right, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(right, 6, 0);
    lv_obj_set_style_pad_left(right, 6, 0);
    scroller = right;

    std::string from = std::string("Taught by ") + m.from;
    if (m.area >= 0) from += std::string(" in ") + gd::AREAS[m.area].name;
    text(right, from.c_str(), &font_bold_14);
    text(right, m.use);
    if (m.meal) {
        std::string meal = std::string("Power-up: eat ") + m.meal + ".";
        text(right, meal.c_str(), &font_body_14, P.moss);
    }
    if (m.verify) verify_note(right, m.note ? m.note : "");
    else if (m.note) text(right, m.note, &font_small_12, P.ink_soft);
}

// ===================================================================
// Specialties
// ===================================================================

static std::vector<std::string> spec_examples(int i) {
    std::vector<std::string> names;
    for (int k = 0; k < gd::SPECS[i].n_examples; k++) names.push_back(gd::SPECS[i].examples[k]);
    auto add = [&](const std::string& n) {
        if (std::find(names.begin(), names.end(), n) == names.end()) names.push_back(n);
    };
    for (int k = 0; k < gd::N_MONS; k++)
        for (int s = 0; s < gd::MONS[k].n_specs; s++)
            if (gd::MONS[k].specs[s] == i) add(gd::MONS[k].name);
    for (const auto& m : save::custom_mons())
        for (const auto& s : m.specs)
            if (s == gd::SPECS[i].id) add(m.name);
    return names;
}

static void build_specs() {
    Frame f = frame("Specialties");
    text(f.body, "What each resident does for your town.", &font_small_12, P.ink_soft);
    for (int i = 0; i < gd::N_SPECS; i++) {
        std::vector<std::string> ex = spec_examples(i);
        lv_obj_t* r = list_row(f.body, on_open_spec, i, 46);
        uint16_t dex = ex.empty() ? 0 : mon_view(ref_for_name(ex[0].c_str())).sprite_dex;
        sprite(r, dex, gd::SPECS[i].name, SPR_SMALL);
        row_text(r, gd::SPECS[i].name, gd::SPECS[i].does);
        chevron(r);
    }
}

static void build_spec(int i) {
    const gd::Spec& s = gd::SPECS[i];
    Frame f = frame(s.name);
    text(f.body, s.does);
    if (s.verify) verify_badge(f.body);
    std::vector<std::string> ex = spec_examples(i);
    eyebrow(f.body, "POKÉMON WITH IT");
    if (ex.empty()) {
        text(f.body, "No confirmed examples yet.", &font_body_14, P.ink_soft);
    } else {
        lv_obj_t* w = wrap_row(f.body);
        for (const std::string& n : ex) mon_chip(w, n.c_str());
    }
}

// ===================================================================
// Areas
// ===================================================================

static const char* area_status_text(save::AreaStatus s) {
    return s == save::AREA_RESTORED ? "Restored" : s == save::AREA_PROGRESS ? "In progress" : "Not started";
}

static void on_open_area(lv_event_t* e) { go(S_AREA, (int)param_of(e)); }

static void build_areas() {
    Frame f = frame("Areas");
    text(f.body, "The story starts in one area and branches into two. Finish both to open the last.",
         &font_small_12, P.ink_soft);
    // Story areas in order (steps 1-4), then side areas (step 0).
    for (int step : {1, 2, 3, 4, 0}) {
        {
            for (int i = 0; i < gd::N_AREAS; i++) {
                const gd::Area& a = gd::AREAS[i];
                bool side = a.step == 0;
                if (a.step != step) continue;
                save::AreaStatus s = save::area_status(a.id);
                lv_obj_t* r = list_row(f.body, on_open_area, i, 44);
                lv_obj_t* badge = plain(r);
                lv_obj_set_size(badge, 26, 26);
                lv_obj_set_style_radius(badge, 6, 0);
                lv_obj_set_style_bg_opa(badge, LV_OPA_COVER, 0);
                lv_obj_set_style_bg_color(badge, s == save::AREA_RESTORED ? P.moss : P.moss_soft, 0);
                lv_obj_t* n = lv_label_create(badge);
                char num[12];
                snprintf(num, sizeof num, "%s", side ? "+" : std::to_string(a.step).c_str());
                lv_label_set_text(n, num);
                lv_obj_set_style_text_font(n, &font_mono_12, 0);
                lv_obj_set_style_text_color(n, s == save::AREA_RESTORED ? P.on_moss : P.ink, 0);
                lv_obj_center(n);
                row_text(r, a.name, side ? "Side area" : area_status_text(s));
                if (s != save::AREA_NONE && !side)
                    chip(r, area_status_text(s), s == save::AREA_RESTORED ? &theme::chip_friend : &theme::chip_seen);
                else
                    chevron(r);
            }
        }
    }
}

static void on_goal(lv_event_t* e) {
    const gd::Area& a = gd::AREAS[stack[depth - 1].param];
    int g = (int)param_of(e);
    bool on = !save::goal_done(a.id, g);
    save::set_goal_done(a.id, g, on);
    set_check(lv_event_get_target_obj(e), on);
}

static void on_area_status(lv_event_t* e) {
    const gd::Area& a = gd::AREAS[stack[depth - 1].param];
    save::AreaStatus s = (save::AreaStatus)((save::area_status(a.id) + 1) % 3);
    save::set_area_status(a.id, s);
    lv_obj_t* b = lv_event_get_target_obj(e);
    std::string label = std::string("Status: ") + area_status_text(s);
    set_button_text(b, label.c_str());
    if (s == save::AREA_RESTORED) lv_obj_add_style(b, &theme::btn_primary, 0);
    else lv_obj_remove_style(b, &theme::btn_primary, 0);
}

static void build_area(int i) {
    const gd::Area& a = gd::AREAS[i];
    Frame f = frame(a.name, a.step > 0);
    if (a.inspired) {
        std::string b = std::string("Based on ") + a.inspired;
        text(f.body, b.c_str(), &font_small_12, P.ink_soft);
    }
    text(f.body, a.summary);
    if (a.n_goals) {
        eyebrow(f.body, "GOALS");
        for (int g = 0; g < a.n_goals; g++) {
            lv_obj_t* r = box(f.body, LV_FLEX_FLOW_ROW, 8);
            lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START);
            check(r, save::goal_done(a.id, g), on_goal, g);
            lv_obj_t* t = text(r, a.goals[g]);
            lv_obj_set_width(t, 1);
            lv_obj_set_flex_grow(t, 1);
        }
    }
    if (a.unlocks) {
        std::string o = std::string("Opens: ") + a.unlocks;
        text(f.body, o.c_str(), &font_small_12, P.ink_soft);
    }
    if (a.verify) verify_note(f.body, a.note ? a.note : "");
    if (a.step > 0) {
        save::AreaStatus s = save::area_status(a.id);
        std::string label = std::string("Status: ") + area_status_text(s);
        lv_obj_t* b = button(f.bar, label.c_str(), on_area_status, nullptr, s == save::AREA_RESTORED);
        lv_obj_set_width(b, lv_pct(100));
    }
}

// ===================================================================
// Cooking, tips, requests
// ===================================================================

static void bullet_list(lv_obj_t* parent, const char* const* items, int n) {
    for (int i = 0; i < n; i++) {
        lv_obj_t* r = box(parent, LV_FLEX_FLOW_ROW, 6);
        block(r, gd::T_GRASS, 6);
        lv_obj_t* t = text(r, items[i], &font_small_12, P.ink);
        lv_obj_set_width(t, 1);
        lv_obj_set_flex_grow(t, 1);
    }
}

static void build_cooking() {
    Frame f = frame("Cooking & crafting");
    eyebrow(f.body, "MEALS THAT POWER UP DITTO");
    for (int i = 0; i < gd::N_MEALS; i++) {
        const gd::Meal& m = gd::MEALS[i];
        lv_obj_t* c = plain(f.body);
        lv_obj_add_style(c, &theme::card, 0);
        lv_obj_set_size(c, lv_pct(100), LV_SIZE_CONTENT);
        lv_obj_set_flex_flow(c, LV_FLEX_FLOW_ROW_WRAP);
        lv_obj_set_flex_align(c, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
        block(c, m.heat ? gd::T_FIRE : gd::T_WOOD, 16);
        lv_obj_t* name = text(c, m.meal, &font_bold_14, P.ink, false);
        lv_obj_set_flex_grow(name, 1);
        std::string up = std::string("Powers up ") + m.move;
        text(c, up.c_str(), &font_bold_14, P.moss, false);
        std::string how = std::string(m.tool) + (m.heat ? " over a fire" : ", no heat") + ". Start with " + m.starter + ".";
        text(c, how.c_str(), &font_small_12, P.ink_soft);
    }
    verify_note(f.body, gd::POWER_UP_NOTE);
    eyebrow(f.body, "UNLOCKING COOKING");
    verify_note(f.body, gd::COOKING_UNLOCK_NOTE);
    eyebrow(f.body, "COOKING TIPS");
    bullet_list(f.body, gd::COOKING_NOTES, gd::N_COOKING_NOTES);
    eyebrow(f.body, "CRAFTING");
    bullet_list(f.body, gd::CRAFTING, gd::N_CRAFTING);
    eyebrow(f.body, "PLAYING TOGETHER");
    bullet_list(f.body, gd::MULTIPLAYER, gd::N_MULTIPLAYER);
}

static void on_tip(lv_event_t* e) {
    int n = gd::N_TIPS;
    save::set_tip(((save::tip() + (int)param_of(e)) % n + n) % n);
    reload(false);
}

static void build_tips() {
    Frame f = frame("Field tips", true);
    int i = ((save::tip() % gd::N_TIPS) + gd::N_TIPS) % gd::N_TIPS;
    char head[24];
    snprintf(head, sizeof head, "TIP %d OF %d", i + 1, gd::N_TIPS);
    eyebrow(f.body, head);
    lv_obj_t* t = text(f.body, gd::TIPS[i], &font_title_20, P.ink);
    lv_obj_set_style_text_line_space(t, -2, 0);
    button(f.bar, LV_SYMBOL_LEFT " Previous", on_tip, (void*)(intptr_t)-1);
    button(f.bar, "Next tip " LV_SYMBOL_RIGHT, on_tip, (void*)(intptr_t)1, true);
}

static void on_request(lv_event_t* e) {
    size_t i = (size_t)param_of(e);
    bool on = !save::requests()[i].done;
    save::set_request_done(i, on);
    set_check(lv_event_get_target_obj(e), on);
}

static void build_requests() {
    Frame f = frame("Requests");
    const auto& reqs = save::requests();
    if (reqs.empty()) text(f.body, "No requests yet.", &font_body_14, P.ink_soft);
    for (size_t i = 0; i < reqs.size(); i++) {
        const save::Request& r = reqs[i];
        lv_obj_t* row = plain(f.body);
        lv_obj_add_style(row, &theme::row, 0);
        lv_obj_set_size(row, lv_pct(100), LV_SIZE_CONTENT);
        lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
        lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
        check(row, r.done, on_request, (intptr_t)i);
        std::string meta;
        if (!r.who.empty()) meta = "For " + r.who;
        for (int a = 0; a < gd::N_AREAS; a++)
            if (r.area == gd::AREAS[a].id) meta += (meta.empty() ? "" : " · ") + std::string(gd::AREAS[a].name);
        if (r.suggested) meta += (meta.empty() ? "" : " · ") + std::string("Suggested");
        lv_obj_t* col = plain(row);
        lv_obj_set_size(col, 1, LV_SIZE_CONTENT);
        lv_obj_set_flex_grow(col, 1);
        lv_obj_set_flex_flow(col, LV_FLEX_FLOW_COLUMN);
        text(col, r.text.c_str(), &font_body_14, r.done ? P.ink_soft : P.ink);
        if (!meta.empty()) text(col, meta.c_str(), &font_small_12, P.ink_soft);
    }
    text(f.body, "Add new requests in the phone app, then load its save code here from Settings.",
         &font_small_12, P.ink_soft);
}

// ===================================================================
// Settings, calibration, QR code, about
// ===================================================================

static lv_obj_t* settings_msg = nullptr;

static void say(const char* msg, bool good = true) {
    if (!settings_msg) return;
    lv_label_set_text(settings_msg, msg);
    lv_obj_set_style_text_color(settings_msg, good ? P.moss : P.danger, 0);
    lv_obj_remove_flag(settings_msg, LV_OBJ_FLAG_HIDDEN);
}

static void on_theme(lv_event_t*) {
    save::settings().dark = !save::settings().dark;
    save::write_settings();
    theme::apply(save::settings().dark);
    reload();
}

static void on_invert(lv_event_t* e) {
    save::settings().invert = !save::settings().invert;
    save::write_settings();
    platform::set_color_inversion(save::settings().invert);
    set_button_text(lv_event_get_target_obj(e), save::settings().invert ? "On" : "Off");
}

static void on_brightness(lv_event_t* e) {
    lv_obj_t* s = lv_event_get_target_obj(e);
    save::settings().brightness = (uint8_t)lv_slider_get_value(s);
    platform::set_backlight(save::settings().brightness);
    if (lv_event_get_code(e) == LV_EVENT_RELEASED) save::write_settings();
}

static void on_export(lv_event_t*) {
    if (!platform::sd_available()) { say("No SD card found. Insert one and try again.", false); return; }
    if (platform::sd_write("/pokopia/save.json", save::export_json()))
        say("Saved to pokopia/save.json on the SD card.");
    else
        say("Could not write to the SD card.", false);
}

static void on_import(lv_event_t* e) {
    if (!platform::sd_available()) { say("No SD card found. Insert one and try again.", false); return; }
    std::string text_in;
    if (!platform::sd_read("/pokopia/save.json", text_in)) {
        say("No pokopia/save.json on the SD card.", false);
        return;
    }
    if (!confirm_tap(lv_event_get_target_obj(e), "Tap again to replace")) return;
    std::string error;
    if (save::import_json(text_in, error)) say("Save code loaded.");
    else say(error.c_str(), false);
}

static void on_reset(lv_event_t* e) {
    if (!confirm_tap(lv_event_get_target_obj(e), "Tap again to erase")) return;
    save::reset();
    say("All progress erased.");
}

static void on_go(lv_event_t* e) { go((Scr)param_of(e)); }

static lv_obj_t* settings_row(lv_obj_t* parent, const char* label, const char* detail = nullptr) {
    lv_obj_t* r = plain(parent);
    lv_obj_add_style(r, &theme::row, 0);
    lv_obj_set_size(r, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_style_min_height(r, 40, 0);
    lv_obj_set_flex_flow(r, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(r, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_t* col = plain(r);
    lv_obj_set_size(col, 1, LV_SIZE_CONTENT);
    lv_obj_set_flex_grow(col, 1);
    lv_obj_set_flex_flow(col, LV_FLEX_FLOW_COLUMN);
    text(col, label, &font_bold_14);
    if (detail) text(col, detail, &font_small_12, P.ink_soft);
    return r;
}

static void build_settings() {
    Frame f = frame("Settings");
    settings_msg = text(f.body, "", &font_bold_14, P.moss);
    lv_obj_add_flag(settings_msg, LV_OBJ_FLAG_HIDDEN);

    lv_obj_t* r = settings_row(f.body, "Theme");
    button(r, save::settings().dark ? "Dark" : "Light", on_theme);

    r = settings_row(f.body, "Brightness");
    lv_obj_t* s = lv_slider_create(r);
    lv_obj_set_size(s, 120, 10);
    lv_obj_set_ext_click_area(s, 12);
    lv_slider_set_range(s, 10, 100);
    lv_slider_set_value(s, save::settings().brightness, LV_ANIM_OFF);
    lv_obj_add_event_cb(s, on_brightness, LV_EVENT_VALUE_CHANGED, nullptr);
    lv_obj_add_event_cb(s, on_brightness, LV_EVENT_RELEASED, nullptr);
    lv_obj_set_style_margin_right(s, 10, 0);

    r = settings_row(f.body, "Invert colors", "Turn on if the screen looks like a negative");
    button(r, save::settings().invert ? "On" : "Off", on_invert);

    r = settings_row(f.body, "Touch calibration", "Fix taps that land in the wrong place");
    button(r, "Start", on_go, (void*)(intptr_t)S_CALIBRATE);

    eyebrow(f.body, "SAVE CODE");
    text(f.body, "Move progress to and from the phone app. It uses the same save code.",
         &font_small_12, P.ink_soft);
    r = settings_row(f.body, "Save to SD card", "Writes pokopia/save.json");
    button(r, LV_SYMBOL_SAVE, on_export);
    r = settings_row(f.body, "Load from SD card", "Reads pokopia/save.json");
    button(r, LV_SYMBOL_DOWNLOAD, on_import);
    r = settings_row(f.body, "Show as QR code", "Scan it with your phone");
    button(r, "Show", on_go, (void*)(intptr_t)S_QR);

    eyebrow(f.body, "DEVICE");
    r = settings_row(f.body, "Erase all progress");
    lv_obj_t* erase = button(r, LV_SYMBOL_TRASH, on_reset);
    lv_obj_set_style_text_color(erase, P.danger, 0);
    r = settings_row(f.body, "About this device");
    button(r, "Open", on_go, (void*)(intptr_t)S_ABOUT);
}

// Touch calibration: tap two targets, the corners map raw readings to pixels.
static lv_timer_t* cal_timer = nullptr;
static int cal_step = 0;
static int32_t cal_sum[2][2];
static int cal_samples = 0;
static bool cal_was_down = false;
static lv_obj_t* cal_target = nullptr;
static lv_obj_t* cal_label = nullptr;
static const int32_t CAL_PTS[2][2] = {{24, 24}, {W - 24, H - 24}};

static void cal_place_target() {
    lv_obj_set_pos(cal_target, CAL_PTS[cal_step][0] - 12, CAL_PTS[cal_step][1] - 12);
}

static void cal_finish() {
    int32_t x1 = cal_sum[0][0], y1 = cal_sum[0][1], x2 = cal_sum[1][0], y2 = cal_sum[1][1];
    int32_t dx = (x2 - x1) * CAL_PTS[0][0] / (CAL_PTS[1][0] - CAL_PTS[0][0]);
    int32_t dy = (y2 - y1) * CAL_PTS[0][1] / (CAL_PTS[1][1] - CAL_PTS[0][1]);
    int16_t cal[4] = {(int16_t)(x1 - dx), (int16_t)(x2 + dx), (int16_t)(y1 - dy), (int16_t)(y2 + dy)};
    for (int i = 0; i < 4; i++) save::settings().cal[i] = cal[i];
    save::settings().cal_set = true;
    save::write_settings();
    platform::set_touch_calibration(cal);
}

static void cal_poll(lv_timer_t*) {
    int16_t x, y;
    bool down = platform::touch_raw(&x, &y);
    if (down) {
        if (!cal_was_down) { cal_sum[cal_step][0] = cal_sum[cal_step][1] = 0; cal_samples = 0; }
        cal_sum[cal_step][0] += x;
        cal_sum[cal_step][1] += y;
        cal_samples++;
    } else if (cal_was_down && cal_samples >= 3) {
        cal_sum[cal_step][0] /= cal_samples;
        cal_sum[cal_step][1] /= cal_samples;
        cal_step++;
        if (cal_step == 2) {
            lv_timer_delete(cal_timer);
            cal_timer = nullptr;
            cal_finish();
            back();
            return;
        }
        cal_place_target();
        lv_label_set_text(cal_label, "Now tap the target in the bottom-right corner.");
    }
    cal_was_down = down;
}

static void on_cal_cancel(lv_event_t*) {
    if (cal_timer) lv_timer_delete(cal_timer);
    cal_timer = nullptr;
    back();
}

static void build_calibrate() {
    lv_obj_t* scr = current_screen;
    scroller = nullptr;
    cal_step = 0;
    cal_samples = 0;
    cal_was_down = false;
    cal_label = text(scr, "Tap the center of the target in the top-left corner. Use a stylus or fingernail.",
                     &font_bold_14, P.ink);
    lv_obj_set_width(cal_label, 240);
    lv_obj_set_style_text_align(cal_label, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_align(cal_label, LV_ALIGN_CENTER, 0, -14);
    lv_obj_t* cancel = button(scr, "Cancel", on_cal_cancel);
    lv_obj_align(cancel, LV_ALIGN_CENTER, 0, 36);

    cal_target = plain(scr);
    lv_obj_set_size(cal_target, 24, 24);
    lv_obj_set_style_radius(cal_target, 12, 0);
    lv_obj_set_style_border_width(cal_target, 3, 0);
    lv_obj_set_style_border_color(cal_target, P.ditto, 0);
    lv_obj_t* dot = plain(cal_target);
    lv_obj_set_size(dot, 6, 6);
    lv_obj_set_style_radius(dot, 3, 0);
    lv_obj_set_style_bg_color(dot, P.ditto, 0);
    lv_obj_set_style_bg_opa(dot, LV_OPA_COVER, 0);
    lv_obj_center(dot);
    cal_place_target();
    if (cal_timer) lv_timer_delete(cal_timer);
    cal_timer = lv_timer_create(cal_poll, 30, nullptr);
}

// The QR code is drawn into an 8-bit grayscale canvas: LVGL draws that format
// directly, so it needs one buffer of at most 236x236 bytes and nothing more.
static const int QR_MAX_VERSION = 22;  // 105 modules: 2 pixels each still fits
static uint8_t* qr_pixels = nullptr;

static void free_qr(lv_event_t*) {
    free(qr_pixels);
    qr_pixels = nullptr;
}

static void build_qr() {
    lv_obj_t* scr = current_screen;
    scroller = nullptr;

    lv_obj_t* field = plain(scr);
    lv_obj_set_size(field, 240, H);
    lv_obj_set_style_bg_color(field, lv_color_white(), 0);
    lv_obj_set_style_bg_opa(field, LV_OPA_COVER, 0);

    lv_obj_t* side = plain(scr);
    lv_obj_set_size(side, W - 240, H);
    lv_obj_set_pos(side, 240, 0);
    lv_obj_set_style_pad_all(side, 6, 0);
    lv_obj_set_style_pad_row(side, 8, 0);
    lv_obj_set_flex_flow(side, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_flex_align(side, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_START);
    text(side, "Scan with your phone. Copy the text into the app's Load save code box.", &font_small_12, P.ink_soft);
    lv_obj_t* done = button(side, "Done", on_back, nullptr, true);
    lv_obj_set_width(done, lv_pct(100));

    static uint8_t qr[qrcodegen_BUFFER_LEN_FOR_VERSION(QR_MAX_VERSION)];
    static uint8_t temp[qrcodegen_BUFFER_LEN_FOR_VERSION(QR_MAX_VERSION)];
    std::string code = save::export_json();
    bool ok = qrcodegen_encodeText(code.c_str(), temp, qr, qrcodegen_Ecc_LOW, 1, QR_MAX_VERSION,
                                   qrcodegen_Mask_AUTO, true);
    int modules = ok ? qrcodegen_getSize(qr) + 4 : 0;  // with a 2-module quiet zone
    int scale = modules ? 236 / modules : 0;
    int side_px = modules * scale;
    if (ok && scale >= 2) qr_pixels = (uint8_t*)malloc((size_t)side_px * side_px);
    if (!qr_pixels) {
        lv_obj_t* msg = text(field, "Your save code is too long for a QR code. Use Save to SD card in "
                                    "Settings instead.", &font_bold_14, lv_color_black());
        lv_obj_set_width(msg, 200);
        lv_obj_center(msg);
        return;
    }
    for (int y = 0; y < side_px; y++)
        for (int x = 0; x < side_px; x++)
            qr_pixels[y * side_px + x] = qrcodegen_getModule(qr, x / scale - 2, y / scale - 2) ? 0 : 255;
    lv_obj_t* canvas = lv_canvas_create(field);
    lv_canvas_set_buffer(canvas, qr_pixels, side_px, side_px, LV_COLOR_FORMAT_L8);
    lv_obj_center(canvas);
    lv_obj_add_event_cb(canvas, free_qr, LV_EVENT_DELETE, nullptr);
}

static void build_about() {
    Frame f = frame("About");
    text(f.body, "Pokopia Companion", &font_title_20);
    char line[96];
    snprintf(line, sizeof line, "Firmware %s. Guide data from %s.", platform::firmware_version(), gd::META_UPDATED);
    text(f.body, line, &font_small_12, P.ink_soft);
    snprintf(line, sizeof line, "SD card: %s", !platform::sd_available() ? "not found" :
             platform::sd_has_sprites() ? "pictures for all 1,025 Pokémon" : "found, no picture pack");
    text(f.body, line, &font_small_12, P.ink_soft);
    snprintf(line, sizeof line, "Free memory: %lu KB", (unsigned long)(platform::free_heap() / 1024));
    text(f.body, line, &font_small_12, P.ink_soft);
    text(f.body, "Unofficial fan-made guide for Pokémon Pokopia on Nintendo Switch 2. Not affiliated with "
                 "or endorsed by Nintendo, The Pokémon Company or Game Freak.", &font_small_12, P.ink);
    text(f.body, "Pokémon images © Nintendo, Creatures Inc. and GAME FREAK inc., via Pokémon Database. "
                 "Fonts: Atkinson Hyperlegible, Baloo 2 and DM Mono (SIL Open Font License).",
         &font_small_12, P.ink_soft);
    text(f.body, "Details marked Check in-game have conflicting reports in community guides.",
         &font_small_12, P.ink_soft);
}

// ===================================================================
// Screen building and start-up
// ===================================================================

static void build_current() {
    lv_obj_t* old = lv_screen_active();
    current_screen = lv_obj_create(nullptr);
    lv_obj_remove_style_all(current_screen);
    lv_obj_add_style(current_screen, &theme::screen, 0);
    lv_obj_remove_flag(current_screen, LV_OBJ_FLAG_SCROLLABLE);
    scroller = nullptr;
    settings_msg = nullptr;
    built_label = nullptr;
    armed_btn = nullptr;
    if (disarm_timer) { lv_timer_delete(disarm_timer); disarm_timer = nullptr; }
    if (cal_timer) { lv_timer_delete(cal_timer); cal_timer = nullptr; }

    const Nav& n = stack[depth - 1];
    switch (n.scr) {
        case S_HOME: build_home(); break;
        case S_DEX: build_dex(); break;
        case S_MON: build_mon(n.param); break;
        case S_HABITATS: build_habitats(); break;
        case S_HABITAT: build_habitat(n.param); break;
        case S_MOVES: build_moves(); break;
        case S_MOVE: build_move(n.param); break;
        case S_SPECS: build_specs(); break;
        case S_SPEC: build_spec(n.param); break;
        case S_AREAS: build_areas(); break;
        case S_AREA: build_area(n.param); break;
        case S_COOKING: build_cooking(); break;
        case S_TIPS: build_tips(); break;
        case S_REQUESTS: build_requests(); break;
        case S_SETTINGS: build_settings(); break;
        case S_CALIBRATE: build_calibrate(); break;
        case S_QR: build_qr(); break;
        case S_ABOUT: build_about(); break;
    }
    lv_screen_load(current_screen);
    if (old && old != current_screen) lv_obj_delete(old);
    if (scroller && n.scroll) {
        lv_obj_update_layout(scroller);
        lv_obj_scroll_to_y(scroller, n.scroll, LV_ANIM_OFF);
    }
}

static void save_timer(lv_timer_t*) {
    if (save::dirty()) save::write_now();
}

void start() {
    theme::apply(save::settings().dark);
    lv_timer_create(save_timer, 2000, nullptr);
    go_home();
}

}  // namespace ui
