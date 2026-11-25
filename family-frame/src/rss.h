#pragma once
// Streaming RSS 2.0 / Atom reader in plain C++ (unit-tested in test/test_text.cpp).
//
// Feeds can be hundreds of KB, far more than the ESP32 has free, so bytes are
// fed in as they arrive and only each item's title (plus Google News' <source>)
// is kept.

#include <stddef.h>
#include <stdint.h>

namespace rss {

struct Item {
    char title[160];
    char source[48];
};

class Parser {
public:
    Parser(Item* items, int maxItems) : items_(items), max_(maxItems) {}
    void put(char c);
    void put(const char* data, size_t n) {
        for (size_t i = 0; i < n && !full(); i++) put(data[i]);
    }
    int count() const { return count_; }
    bool full() const { return count_ >= max_; }

private:
    void onTag(bool selfClosing);
    void startCapture(uint8_t what, const char* endTag);
    void finishCapture();

    Item* items_;
    int max_;
    int count_ = 0;

    enum State : uint8_t { TEXT, TAG, CAPTURE } state_ = TEXT;
    char tag_[24] = {};
    uint8_t tagLen_ = 0;
    bool closing_ = false;
    bool nameDone_ = false;
    bool firstTagChar_ = true;
    char prev_ = 0;

    bool inItem_ = false;
    bool atom_ = false;
    char curTitle_[160] = {};
    char curSource_[48] = {};

    uint8_t what_ = 0;  // 1 = title, 2 = source
    char cap_[420] = {};
    uint16_t capLen_ = 0;
    const char* end_ = nullptr;
    uint8_t endLen_ = 0;
    uint8_t match_ = 0;
};

}  // namespace rss
