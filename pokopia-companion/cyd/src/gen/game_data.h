// Types for the generated guide data (see tools/gen_cyd_assets.py).
#pragma once
#include <stdint.h>
#include "lvgl.h"

namespace gd {

enum Tile : uint8_t { T_GRASS, T_TREE, T_WATER, T_OCEAN, T_FLOWER, T_FIRE, T_ROCK, T_WOOD, T_SOIL };

struct Spec {
    const char* id;
    const char* name;
    const char* does;
    const char* const* examples;
    uint8_t n_examples;
    bool verify;
};

struct Mon {
    const char* id;          // same id the phone app uses
    const char* name;
    uint16_t dex;            // National Pokédex number, 0 for Pokopia originals
    uint16_t sprite_dex;     // species whose sprite is shown
    const char* base;        // base species for Pokopia originals
    uint8_t specs[3];
    uint8_t n_specs;
    const char* where;
    const char* role;
    const char* note;
    bool verify;
};

struct Req {
    const char* material;
    uint8_t count;
    uint8_t tile;
};

struct Habitat {
    const char* id;
    uint16_t no;
    const char* name;
    const Req* req;
    uint8_t n_req;
    const char* const* residents;
    uint8_t n_res;
    const char* cond;
    const char* note;
    bool verify;
};

struct Move {
    const char* id;
    const char* name;
    const char* from;
    uint16_t from_dex;
    int8_t area;             // index into AREAS, -1 if unknown
    const char* use;
    const char* meal;
    const char* note;
    bool verify;
};

struct Area {
    const char* id;
    const char* name;
    uint8_t step;            // 0 = side area
    const char* inspired;
    const char* summary;
    const char* const* goals;
    uint8_t n_goals;
    const char* unlocks;
    const char* note;
    bool verify;
};

struct Meal {
    const char* meal;
    const char* tool;
    bool heat;
    const char* move;
    const char* starter;
};

struct StarterRequest {
    const char* text;
    const char* area;
};

struct SpriteRef {
    uint16_t dex;
    const lv_image_dsc_t* large;
    const lv_image_dsc_t* small;
};

extern const Spec SPECS[];
extern const uint8_t N_SPECS;
extern const Mon MONS[];
extern const uint16_t N_MONS;
extern const Habitat HABITATS[];
extern const uint8_t N_HABITATS;
extern const Move MOVES[];
extern const uint8_t N_MOVES;
extern const Area AREAS[];
extern const uint8_t N_AREAS;
extern const Meal MEALS[];
extern const uint8_t N_MEALS;
extern const char* const TIPS[];
extern const uint8_t N_TIPS;
extern const char* const COOKING_NOTES[];
extern const uint8_t N_COOKING_NOTES;
extern const char* const CRAFTING[];
extern const uint8_t N_CRAFTING;
extern const char* const MULTIPLAYER[];
extern const uint8_t N_MULTIPLAYER;
extern const StarterRequest STARTER_REQUESTS[];
extern const uint8_t N_STARTER_REQUESTS;
extern const char* const COOKING_UNLOCK_NOTE;
extern const char* const POWER_UP_NOTE;
extern const char* const META_GAME;
extern const char* const META_PLATFORM;
extern const char* const META_RELEASED;
extern const char* const META_UPDATED;
extern const uint16_t META_HABITAT_TOTAL;

extern const char* const SPECIES_NAMES[];
extern const char* const SPECIES_IDS[];
extern const uint16_t N_SPECIES;

extern const SpriteRef BUILTIN_SPRITES[];
extern const uint16_t N_BUILTIN_SPRITES;

}  // namespace gd
