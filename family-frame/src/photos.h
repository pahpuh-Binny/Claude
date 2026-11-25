#pragma once
// The photo slideshow: an index of /photos on the SD card, JPEG drawing and
// the clock overlay that sits on top of each photo.

#include <Arduino.h>

#include <functional>

namespace photos {

constexpr const char* DIR = "/photos";

void begin();
void rescan();
int count();

// Picks and draws the next / previous photo. False when there are none
// (or none that can be shown).
bool showNext();
bool showPrev();
bool show(const String& name);  // a file name inside /photos
String currentName();

// A newly uploaded photo: it goes next in the slideshow.
void added(const String& name);
bool remove(const String& name);
void forEachName(const std::function<void(const String&)>& fn);

// The clock / weather overlay drawn over the last photo.
void drawOverlay();

}  // namespace photos
