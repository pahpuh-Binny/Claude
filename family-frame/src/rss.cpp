#include "rss.h"

#include <string.h>

#include "textutil.h"

namespace rss {

static bool isItemTag(const char* name) { return strcmp(name, "item") == 0 || strcmp(name, "entry") == 0; }

void Parser::put(char c) {
    switch (state_) {
        case TEXT:
            if (c == '<') {
                state_ = TAG;
                tagLen_ = 0;
                tag_[0] = 0;
                closing_ = false;
                nameDone_ = false;
                firstTagChar_ = true;
                prev_ = 0;
            }
            return;

        case TAG:
            if (c == '>') {
                state_ = TEXT;
                onTag(prev_ == '/');
                return;
            }
            if (firstTagChar_) {
                firstTagChar_ = false;
                if (c == '/') {
                    closing_ = true;
                    prev_ = c;
                    return;
                }
            }
            if (!nameDone_) {
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/') {
                    nameDone_ = true;
                } else if ((size_t)tagLen_ + 1 < sizeof tag_) {
                    tag_[tagLen_++] = c;
                    tag_[tagLen_] = 0;
                }
            }
            prev_ = c;
            return;

        case CAPTURE:
            if ((size_t)capLen_ + 1 < sizeof cap_) cap_[capLen_++] = c;
            if (c == end_[match_]) {
                if (++match_ == endLen_) finishCapture();
            } else {
                match_ = (c == end_[0]) ? 1 : 0;
            }
            return;
    }
}

void Parser::onTag(bool selfClosing) {
    if (!closing_) {
        if (isItemTag(tag_)) {
            inItem_ = true;
            atom_ = tag_[0] == 'e';
            curTitle_[0] = 0;
            curSource_[0] = 0;
        } else if (inItem_ && !selfClosing && strcmp(tag_, "title") == 0 && !curTitle_[0]) {
            // Only the item's first <title>: Atom entries can nest a <source><title>.
            startCapture(1, "</title>");
        } else if (inItem_ && !atom_ && !selfClosing && strcmp(tag_, "source") == 0 && !curSource_[0]) {
            // RSS <source> is the publisher's name (Google News). Atom's holds nested elements.
            startCapture(2, "</source>");
        }
        return;
    }
    if (isItemTag(tag_) && inItem_) {
        inItem_ = false;
        if (curTitle_[0] && count_ < max_) {
            Item& it = items_[count_++];
            text::stripSourceSuffix(curTitle_, curSource_);
            strncpy(it.title, curTitle_, sizeof it.title - 1);
            it.title[sizeof it.title - 1] = 0;
            strncpy(it.source, curSource_, sizeof it.source - 1);
            it.source[sizeof it.source - 1] = 0;
        }
    }
}

void Parser::startCapture(uint8_t what, const char* endTag) {
    state_ = CAPTURE;
    what_ = what;
    end_ = endTag;
    endLen_ = (uint8_t)strlen(endTag);
    match_ = 0;
    capLen_ = 0;
}

void Parser::finishCapture() {
    cap_[capLen_] = 0;
    char* end = strstr(cap_, end_);
    if (end) *end = 0;
    if (what_ == 1) {
        text::clean(cap_, curTitle_, sizeof curTitle_);
    } else {
        text::clean(cap_, curSource_, sizeof curSource_);
    }
    state_ = TEXT;
}

}  // namespace rss
