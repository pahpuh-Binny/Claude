#pragma once
// News feeds and weather, fetched by a background task so the screen and web
// page stay responsive while it works.

#include <Arduino.h>

#include "rss.h"
#include "settings.h"

namespace news {

constexpr int ITEMS_PER_FEED = 6;

struct Feed {
    rss::Item items[ITEMS_PER_FEED];
    uint8_t count = 0;
    int16_t status = 0;     // last HTTP status (200 = fine), negative = connection error, 0 = never tried
    uint32_t updated = 0;   // unix time of the last successful fetch
};

struct Weather {
    bool valid = false;
    float temp = 0, hi = 0, lo = 0;
    int code = 0;
    bool day = true;
    int rain = 0;           // chance of precipitation today, %
    uint32_t updated = 0;
};

void begin();               // loads the cached headlines and starts the task
void requestRefresh();      // fetch every enabled feed (and the weather) soon
void requestWeather();
bool busy();
uint32_t lastRefreshMs();   // millis() when the last full refresh finished
// True once after each refresh finishes (the main loop then saves the cache).
bool takeFinished();
void saveCache();

// Copies under a lock; safe to call while the task is running.
Feed feed(int i);
Weather weather();
int feedsWithItems();

String weatherText(int code);
// One-line summary, e.g. "45 degrees and cloudy, high of 52".
String weatherSentence(bool spoken);
// Compact headline list for the AI assistant's context.
String contextForAssistant(int perFeed, size_t maxChars);

}  // namespace news
