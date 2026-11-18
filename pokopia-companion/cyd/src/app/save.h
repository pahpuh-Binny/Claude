// Progress and settings.
//
// Progress is kept as the same JSON "save code" the phone and web app use,
// so it can move between the CYD and the app (SD card file or QR code).
#pragma once
#include <stdint.h>
#include <string>
#include <vector>

namespace save {

enum Status : uint8_t { NONE = 0, SEEN = 1, FRIEND = 2 };
enum AreaStatus : uint8_t { AREA_NONE = 0, AREA_PROGRESS = 1, AREA_RESTORED = 2 };

struct CustomMon {
    std::string id, name, where, note;
    uint16_t dex;
    std::vector<std::string> specs;
};

struct CustomHabitat {
    std::string id, name, cond;
    uint16_t no;
    std::vector<std::pair<std::string, int>> req;
    std::vector<std::string> residents;
};

struct Request {
    std::string text, who, area;
    bool done;
    bool suggested;
};

struct Progress {
    int pct;
    int friends, mons;
    int built, habs;
    int moves, moves_total;
    int restored, areas_total;
};

struct Settings {
    bool dark = true;
    bool invert = false;     // flips panel colours on boards that need it
    bool swap_rb = false;    // swaps red and blue on panels wired BGR
    bool setup_done = false; // first-boot touch and colour setup finished
    uint8_t brightness = 80;
    int16_t cal[4] = {200, 3700, 240, 3800};
    bool cal_set = false;
};

void load();                 // reads /save.json (or starts fresh)
bool dirty();
void write_now();            // writes /save.json if anything changed

// Pokédex
Status mon_status(const std::string& id);
void set_mon_status(const std::string& id, Status s);
const std::vector<CustomMon>& custom_mons();
int custom_index_by_dex(uint16_t dex);
int add_custom_species(uint16_t dex);  // returns its custom index

// Habitats
int habitat_count(const std::string& id);
void set_habitat_count(const std::string& id, int n);
const std::vector<CustomHabitat>& custom_habitats();

// Moves, areas, goals
bool move_learned(const std::string& id);
void set_move_learned(const std::string& id, bool on);
AreaStatus area_status(const std::string& id);
void set_area_status(const std::string& id, AreaStatus s);
bool goal_done(const std::string& area_id, int index);
void set_goal_done(const std::string& area_id, int index, bool on);

// Requests and tips
const std::vector<Request>& requests();
void set_request_done(size_t index, bool done);
int tip();
void set_tip(int index);

Progress progress();

// Save codes
std::string export_json();
bool import_json(const std::string& text, std::string& error);
void reset();

// Device settings (/settings.json)
Settings& settings();
void load_settings();
void write_settings();

}  // namespace save
