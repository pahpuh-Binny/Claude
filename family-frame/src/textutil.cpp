#include "textutil.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace text {

// Base letters for U+00C0..U+00FF and U+0100..U+017F.
static const char LATIN1[] = "AAAAAAACEEEEIIIIDNOOOOOxOUUUUYTsaaaaaaaceeeeiiiidnooooo/ouuuuyty";
static const char LATIN_EXT_A[] =
    "AaAaAaCcCcCcCcDdDdEeEeEeEeEeGgGgGgGgHhHhIiIiIiIiIiIiJjKkkLlLlLlLlLlNnNnNnnNnOoOoOoOoRrRrRrSsSsSsSs"
    "TtTtTtUuUuUuUuUuUuWwYyYZzZzZzs";
static_assert(sizeof(LATIN1) == 65, "Latin-1 table must cover 0xC0..0xFF");
static_assert(sizeof(LATIN_EXT_A) == 129, "Latin Extended-A table must cover 0x100..0x17F");

static size_t put(const char* s, char* out, size_t room) {
    size_t n = strlen(s);
    if (n > room) n = room;
    memcpy(out, s, n);
    return n;
}

size_t asciiFor(uint32_t cp, char* out, size_t room) {
    if (room == 0) return 0;
    if (cp < 0x20 || cp == 0x7F) return put(" ", out, room);
    if (cp < 0x7F) {
        out[0] = (char)cp;
        return 1;
    }
    if (cp >= 0xC0 && cp <= 0xFF) {
        out[0] = LATIN1[cp - 0xC0];
        return 1;
    }
    if (cp >= 0x100 && cp <= 0x17F) {
        out[0] = LATIN_EXT_A[cp - 0x100];
        return 1;
    }
    switch (cp) {
        case 0xA0: case 0x2002: case 0x2003: case 0x2004: case 0x2005: case 0x2006:
        case 0x2007: case 0x2008: case 0x2009: case 0x200A: case 0x202F: case 0x205F:
        case 0x3000:
            return put(" ", out, room);
        case 0x2018: case 0x2019: case 0x201A: case 0x201B: case 0x2032: case 0xB4: case 0x2BC:
            return put("'", out, room);
        case 0x201C: case 0x201D: case 0x201E: case 0x2033: case 0xAB: case 0xBB:
            return put("\"", out, room);
        case 0x2010: case 0x2011: case 0x2012: case 0x2013: case 0x2014: case 0x2015: case 0x2212:
        case 0x2022: case 0xB7: case 0x2027: case 0x2219: case 0x25CF:
            return put("-", out, room);
        case 0x2026: return put("...", out, room);
        case 0xA9: return put("(c)", out, room);
        case 0xAE: return put("(R)", out, room);
        case 0x2122: return put("TM", out, room);
        case 0xBC: return put("1/4", out, room);
        case 0xBD: return put("1/2", out, room);
        case 0xBE: return put("3/4", out, room);
        case 0x20AC: return put("EUR ", out, room);
        case 0xA3: return put("GBP ", out, room);
        case 0xA5: return put("JPY ", out, room);
        case 0xA2: return put("c", out, room);
        case 0x2192: return put("->", out, room);
        case 0x2190: return put("<-", out, room);
        case 0x2605: case 0x2606: return put("*", out, room);
        case 0x2116: return put("No.", out, room);
        default:
            return 0;  // emoji, symbols and scripts the fonts can't draw
    }
}

struct Entity {
    const char* name;
    uint16_t cp;
};

static const Entity ENTITIES[] = {
    {"amp", '&'},       {"lt", '<'},        {"gt", '>'},        {"quot", '"'},     {"apos", '\''},
    {"nbsp", 0xA0},     {"ndash", 0x2013},  {"mdash", 0x2014},  {"hellip", 0x2026}, {"lsquo", 0x2018},
    {"rsquo", 0x2019},  {"sbquo", 0x201A},  {"ldquo", 0x201C},  {"rdquo", 0x201D}, {"bdquo", 0x201E},
    {"laquo", 0xAB},    {"raquo", 0xBB},    {"copy", 0xA9},     {"reg", 0xAE},      {"trade", 0x2122},
    {"deg", 0xB0},      {"middot", 0xB7},   {"bull", 0x2022},   {"times", 0xD7},    {"euro", 0x20AC},
    {"pound", 0xA3},    {"cent", 0xA2},     {"yen", 0xA5},      {"frac12", 0xBD},   {"frac14", 0xBC},
    {"frac34", 0xBE},   {"eacute", 0xE9},   {"Eacute", 0xC9},   {"egrave", 0xE8},   {"aacute", 0xE1},
    {"agrave", 0xE0},   {"oacute", 0xF3},   {"uacute", 0xFA},   {"iacute", 0xED},   {"ntilde", 0xF1},
    {"ccedil", 0xE7},   {"uuml", 0xFC},     {"ouml", 0xF6},     {"auml", 0xE4},     {"szlig", 0xDF},
    {"thinsp", 0x2009}, {"ensp", 0x2002},   {"emsp", 0x2003},   {"zwj", 0x200D},    {"zwnj", 0x200C},
};

// Parses "&name;" / "&#123;" / "&#x1F;" at s (which points at '&').
// Returns the number of chars consumed, or 0 if it isn't an entity.
static size_t parseEntity(const char* s, uint32_t* cp) {
    const char* semi = nullptr;
    for (int i = 1; i < 12 && s[i]; i++) {
        if (s[i] == ';') {
            semi = s + i;
            break;
        }
        if (!isalnum((unsigned char)s[i]) && s[i] != '#') return 0;
    }
    if (!semi || semi == s + 1) return 0;
    size_t len = (size_t)(semi - s) + 1;
    if (s[1] == '#') {
        char* end = nullptr;
        unsigned long v = (s[2] == 'x' || s[2] == 'X') ? strtoul(s + 3, &end, 16) : strtoul(s + 2, &end, 10);
        if (end != semi) return 0;
        *cp = (uint32_t)v;
        return len;
    }
    for (const Entity& e : ENTITIES) {
        size_t n = strlen(e.name);
        if (n == (size_t)(semi - s - 1) && strncmp(s + 1, e.name, n) == 0) {
            *cp = e.cp;
            return len;
        }
    }
    return 0;
}

// Decodes one UTF-8 sequence. Invalid bytes are read as Latin-1.
static size_t decodeUtf8(const unsigned char* s, uint32_t* cp) {
    unsigned char c = s[0];
    int extra = 0;
    uint32_t v = 0;
    if (c >= 0xF0 && c <= 0xF4) {
        extra = 3;
        v = c & 0x07;
    } else if (c >= 0xE0) {
        extra = 2;
        v = c & 0x0F;
    } else if (c >= 0xC2 && c < 0xE0) {
        extra = 1;
        v = c & 0x1F;
    } else {
        *cp = c;
        return 1;
    }
    for (int i = 1; i <= extra; i++) {
        if ((s[i] & 0xC0) != 0x80) {
            *cp = c;
            return 1;
        }
        v = (v << 6) | (s[i] & 0x3F);
    }
    *cp = v;
    return (size_t)extra + 1;
}

size_t clean(const char* in, char* out, size_t outSize) {
    if (outSize == 0) return 0;
    size_t o = 0;
    bool space = true;  // swallow leading spaces
    char tmp[8];
    auto emit = [&](const char* s, size_t n) {
        for (size_t k = 0; k < n && o + 1 < outSize; k++) {
            char ch = s[k];
            if (ch == ' ') {
                if (space) continue;
                space = true;
            } else {
                space = false;
            }
            out[o++] = ch;
        }
    };
    const char* p = in;
    while (*p && o + 1 < outSize) {
        if (strncmp(p, "<![CDATA[", 9) == 0) {
            p += 9;
            continue;
        }
        if (strncmp(p, "]]>", 3) == 0) {
            p += 3;
            continue;
        }
        unsigned char c = (unsigned char)*p;
        if (c == '<' && (isalpha((unsigned char)p[1]) || p[1] == '/' || p[1] == '!' || p[1] == '?')) {
            const char* close = strchr(p, '>');
            p = close ? close + 1 : p + strlen(p);
            emit(" ", 1);  // tags like <br> separate words
            continue;
        }
        if (c == '&') {
            uint32_t cp;
            size_t used = parseEntity(p, &cp);
            if (used) {
                p += used;
                // Feeds sometimes double-encode: "&amp;#39;" means an apostrophe.
                if (cp == '&' && *p) {
                    char again[16] = "&";
                    strncpy(again + 1, p, sizeof again - 2);
                    uint32_t cp2;
                    size_t used2 = parseEntity(again, &cp2);
                    if (used2) {
                        p += used2 - 1;
                        cp = cp2;
                    }
                }
                emit(tmp, asciiFor(cp, tmp, sizeof tmp));
                continue;
            }
        }
        if (c < 0x80) {
            char ch = (c < 0x20 || c == 0x7F) ? ' ' : (char)c;
            emit(&ch, 1);
            p++;
            continue;
        }
        uint32_t cp;
        p += decodeUtf8((const unsigned char*)p, &cp);
        emit(tmp, asciiFor(cp, tmp, sizeof tmp));
    }
    while (o > 0 && out[o - 1] == ' ') o--;
    out[o] = 0;
    return o;
}

void stripSourceSuffix(char* title, const char* source) {
    if (!source || !*source) return;
    size_t tl = strlen(title), sl = strlen(source);
    if (tl < sl + 4) return;
    char* tail = title + tl - sl;
    if (strcmp(tail, source) == 0 && strncmp(tail - 3, " - ", 3) == 0) tail[-3] = 0;
}

bool parseHHMM(const char* s, uint16_t* minutes) {
    if (!s) return false;
    int h = 0, m = 0;
    if (sscanf(s, "%d:%d", &h, &m) != 2 || h < 0 || h > 23 || m < 0 || m > 59) return false;
    *minutes = (uint16_t)(h * 60 + m);
    return true;
}

void formatHHMM(uint16_t minutes, char out[6]) {
    snprintf(out, 6, "%02u:%02u", (unsigned)((minutes / 60) % 24), (unsigned)(minutes % 60));
}

}  // namespace text
