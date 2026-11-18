#include "save.h"

#include <ArduinoJson.h>
#include <string.h>

#include "../gen/game_data.h"
#include "platform.h"

namespace save {

static const char* SAVE_PATH = "/save.json";
static const char* SETTINGS_PATH = "/settings.json";

static JsonDocument doc;
static bool is_dirty = false;
static std::vector<CustomMon> mons_cache;
static std::vector<CustomHabitat> habs_cache;
static std::vector<Request> req_cache;
static Settings settings_value;

static JsonObject obj_field(const char* key) {
    if (!doc[key].is<JsonObject>()) doc[key].to<JsonObject>();
    return doc[key].as<JsonObject>();
}

static JsonArray arr_field(const char* key) {
    if (!doc[key].is<JsonArray>()) doc[key].to<JsonArray>();
    return doc[key].as<JsonArray>();
}

static void normalize() {
    if (!doc.is<JsonObject>()) doc.to<JsonObject>();
    doc["v"] = 1;
    for (const char* k : {"dex", "habitats", "moves", "areas", "areaNotes", "goals"}) obj_field(k);
    for (const char* k : {"customPokemon", "customHabitats", "requests"}) arr_field(k);
    if (!doc["notes"].is<const char*>()) doc["notes"] = "";
    if (!doc["tip"].is<int>()) doc["tip"] = 0;
}

static void rebuild_caches() {
    mons_cache.clear();
    for (JsonObject o : arr_field("customPokemon")) {
        CustomMon m;
        m.id = o["id"] | "";
        m.name = o["name"] | "";
        m.where = o["where"] | "";
        m.note = o["note"] | "";
        m.dex = o["dex"] | 0;
        for (JsonVariant s : o["specs"].as<JsonArray>()) m.specs.push_back(s | "");
        if (!m.id.empty()) mons_cache.push_back(m);
    }
    habs_cache.clear();
    for (JsonObject o : arr_field("customHabitats")) {
        CustomHabitat h;
        h.id = o["id"] | "";
        h.name = o["name"] | "";
        h.cond = o["cond"] | "";
        h.no = o["no"] | 0;
        for (JsonArray r : o["req"].as<JsonArray>()) h.req.push_back({r[0] | "", r[1] | 1});
        for (JsonVariant r : o["residents"].as<JsonArray>()) h.residents.push_back(r | "");
        if (!h.id.empty()) habs_cache.push_back(h);
    }
    req_cache.clear();
    for (JsonObject o : arr_field("requests")) {
        Request r;
        r.text = o["text"] | "";
        r.who = o["who"] | "";
        r.area = o["area"] | "";
        r.done = o["done"] | false;
        r.suggested = o["suggested"] | false;
        req_cache.push_back(r);
    }
}

static void fresh() {
    doc.clear();
    doc.to<JsonObject>();
    normalize();
    JsonArray reqs = arr_field("requests");
    for (int i = 0; i < gd::N_STARTER_REQUESTS; i++) {
        JsonObject r = reqs.add<JsonObject>();
        r["id"] = std::string("starter-") + std::to_string(i);
        r["text"] = gd::STARTER_REQUESTS[i].text;
        r["who"] = "";
        r["area"] = gd::STARTER_REQUESTS[i].area ? gd::STARTER_REQUESTS[i].area : "";
        r["done"] = false;
        r["suggested"] = true;
    }
}

static void changed() {
    is_dirty = true;
}

void load() {
    std::string text;
    bool ok = false;
    if (platform::read_file(SAVE_PATH, text)) {
        doc.clear();
        ok = !deserializeJson(doc, text) && doc.is<JsonObject>();
    }
    if (ok) normalize(); else fresh();
    rebuild_caches();
    is_dirty = false;
}

bool dirty() { return is_dirty; }

void write_now() {
    if (!is_dirty) return;
    std::string out;
    serializeJson(doc, out);
    if (platform::write_file(SAVE_PATH, out)) is_dirty = false;
}

// ---------- Pokédex ----------

Status mon_status(const std::string& id) {
    const char* s = doc["dex"][id] | "";
    if (!strcmp(s, "friend")) return FRIEND;
    if (!strcmp(s, "seen")) return SEEN;
    return NONE;
}

void set_mon_status(const std::string& id, Status s) {
    JsonObject dex = obj_field("dex");
    if (s == NONE) dex.remove(id);
    else dex[id] = (s == FRIEND) ? "friend" : "seen";
    changed();
}

const std::vector<CustomMon>& custom_mons() { return mons_cache; }

int custom_index_by_dex(uint16_t dex) {
    if (!dex) return -1;
    for (size_t i = 0; i < mons_cache.size(); i++)
        if (mons_cache[i].dex == dex) return (int)i;
    return -1;
}

static bool id_taken(const std::string& id) {
    for (int i = 0; i < gd::N_MONS; i++)
        if (id == gd::MONS[i].id) return true;
    for (const CustomMon& m : mons_cache)
        if (m.id == id) return true;
    return false;
}

int add_custom_species(uint16_t dex) {
    int existing = custom_index_by_dex(dex);
    if (existing >= 0 || dex < 1 || dex > gd::N_SPECIES) return existing;
    std::string id = gd::SPECIES_IDS[dex - 1];
    if (id_taken(id)) id += "-" + std::to_string(dex);
    JsonObject o = arr_field("customPokemon").add<JsonObject>();
    o["id"] = id;
    o["name"] = gd::SPECIES_NAMES[dex - 1];
    o["dex"] = dex;
    o["specs"].to<JsonArray>();
    o["where"] = "";
    o["note"] = "";
    rebuild_caches();
    changed();
    return custom_index_by_dex(dex);
}

// ---------- Habitats ----------

int habitat_count(const std::string& id) { return doc["habitats"][id] | 0; }

void set_habitat_count(const std::string& id, int n) {
    if (n < 0) n = 0;
    if (n > 99) n = 99;
    JsonObject h = obj_field("habitats");
    if (n == 0) h.remove(id); else h[id] = n;
    changed();
}

const std::vector<CustomHabitat>& custom_habitats() { return habs_cache; }

// ---------- Moves, areas, goals ----------

bool move_learned(const std::string& id) { return doc["moves"][id] | false; }

void set_move_learned(const std::string& id, bool on) {
    JsonObject m = obj_field("moves");
    if (on) m[id] = true; else m.remove(id);
    changed();
}

AreaStatus area_status(const std::string& id) {
    const char* s = doc["areas"][id] | "";
    if (!strcmp(s, "restored")) return AREA_RESTORED;
    if (!strcmp(s, "progress")) return AREA_PROGRESS;
    return AREA_NONE;
}

void set_area_status(const std::string& id, AreaStatus s) {
    JsonObject a = obj_field("areas");
    if (s == AREA_NONE) a.remove(id);
    else a[id] = (s == AREA_RESTORED) ? "restored" : "progress";
    changed();
}

static std::string goal_key(const std::string& area_id, int index) {
    return area_id + ":" + std::to_string(index);
}

bool goal_done(const std::string& area_id, int index) {
    return doc["goals"][goal_key(area_id, index)] | false;
}

void set_goal_done(const std::string& area_id, int index, bool on) {
    JsonObject g = obj_field("goals");
    if (on) g[goal_key(area_id, index)] = true; else g.remove(goal_key(area_id, index));
    changed();
}

// ---------- Requests and tips ----------

const std::vector<Request>& requests() { return req_cache; }

void set_request_done(size_t index, bool done) {
    JsonArray reqs = arr_field("requests");
    if (index >= reqs.size()) return;
    reqs[index]["done"] = done;
    rebuild_caches();
    changed();
}

int tip() { return doc["tip"] | 0; }

void set_tip(int index) {
    doc["tip"] = index;
    changed();
}

// ---------- Progress (same formula as the phone app) ----------

Progress progress() {
    Progress p{};
    p.mons = gd::N_MONS + (int)mons_cache.size();
    for (int i = 0; i < gd::N_MONS; i++)
        if (mon_status(gd::MONS[i].id) == FRIEND) p.friends++;
    for (const CustomMon& m : mons_cache)
        if (mon_status(m.id) == FRIEND) p.friends++;

    p.habs = gd::N_HABITATS + (int)habs_cache.size();
    for (int i = 0; i < gd::N_HABITATS; i++)
        if (habitat_count(gd::HABITATS[i].id) > 0) p.built++;
    for (const CustomHabitat& h : habs_cache)
        if (habitat_count(h.id) > 0) p.built++;

    p.moves_total = gd::N_MOVES;
    for (int i = 0; i < gd::N_MOVES; i++)
        if (move_learned(gd::MOVES[i].id)) p.moves++;

    for (int i = 0; i < gd::N_AREAS; i++) {
        if (gd::AREAS[i].step == 0) continue;
        p.areas_total++;
        if (area_status(gd::AREAS[i].id) == AREA_RESTORED) p.restored++;
    }

    double sum = (double)p.friends / p.mons + (p.habs ? (double)p.built / p.habs : 0) +
                 (double)p.moves / p.moves_total + (double)p.restored / p.areas_total;
    p.pct = (int)(sum / 4.0 * 100.0 + 0.5);
    return p;
}

// ---------- Save codes ----------

std::string export_json() {
    std::string out;
    serializeJson(doc, out);
    return out;
}

bool import_json(const std::string& text, std::string& error) {
    JsonDocument incoming;
    if (deserializeJson(incoming, text) || !incoming.is<JsonObject>()) {
        error = "That save code is incomplete.";
        return false;
    }
    if ((incoming["v"] | 0) != 1) {
        error = "That is not a save code from this guide.";
        return false;
    }
    doc = incoming;
    normalize();
    rebuild_caches();
    is_dirty = true;
    write_now();
    return true;
}

void reset() {
    fresh();
    rebuild_caches();
    is_dirty = true;
    write_now();
}

// ---------- Settings ----------

Settings& settings() { return settings_value; }

void load_settings() {
    std::string text;
    if (!platform::read_file(SETTINGS_PATH, text)) return;
    JsonDocument s;
    if (deserializeJson(s, text)) return;
    settings_value.dark = s["dark"] | true;
    settings_value.brightness = s["brightness"] | 80;
    settings_value.invert = s["invert"] | false;
    settings_value.swap_rb = s["swapRB"] | false;
    settings_value.setup_done = s["setupDone"] | false;
    if (s["cal"].is<JsonArray>() && s["cal"].size() == 4) {
        for (int i = 0; i < 4; i++) settings_value.cal[i] = s["cal"][i] | settings_value.cal[i];
        settings_value.cal_set = true;
    }
}

void write_settings() {
    JsonDocument s;
    s["dark"] = settings_value.dark;
    s["brightness"] = settings_value.brightness;
    s["invert"] = settings_value.invert;
    s["swapRB"] = settings_value.swap_rb;
    s["setupDone"] = settings_value.setup_done;
    if (settings_value.cal_set) {
        JsonArray cal = s["cal"].to<JsonArray>();
        for (int i = 0; i < 4; i++) cal.add(settings_value.cal[i]);
    }
    std::string out;
    serializeJson(s, out);
    platform::write_file(SETTINGS_PATH, out);
}

}  // namespace save
