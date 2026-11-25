#pragma once
// Full-screen layouts. Each function draws one screen; main.cpp decides when.

#include <Arduino.h>

namespace screens {

String clockText();  // "7:42 PM" or "19:42"

void boot(const String& line);
void message(const String& title, const String& body, uint16_t accent);
void setupHotspot();
void addPhotos(bool noPhotosYet);
enum AddPhotosButton { ADD_NONE, ADD_SPEAKER_TEST, ADD_MIC_TEST, ADD_CLOSE };
AddPhotosButton addPhotosHit(int16_t x, int16_t y);

// News card for feed i showing headlines [page*2, page*2+2). Returns false if the
// feed has nothing at that page. interactive adds Prev / Read / Close / Next buttons.
bool newsCard(int feed, int page, bool interactive);
int newsPages(int feed);
enum NewsButton { NEWS_NONE, NEWS_PREV, NEWS_READ, NEWS_CLOSE, NEWS_NEXT };
NewsButton newsHit(int16_t x, int16_t y);

bool weatherCard();  // false when there's no weather yet
void greeting(bool waitingForNews);

void menuBar(bool micReady);
enum MenuButton { MENU_OUTSIDE, MENU_BACK, MENU_NEWS, MENU_ASK, MENU_ADD, MENU_NEXT };
MenuButton menuHit(int16_t x, int16_t y);

void listening();
void listeningLevel(int percent);
void thinking(const String& heard);
void answer(const String& question, const String& reply);

void calibrationTarget(int x, int y, int step);
void colorCheck();
enum ColorButton { COLOR_NONE, COLOR_INVERT, COLOR_SWAP, COLOR_OK };
ColorButton colorHit(int16_t x, int16_t y);

}  // namespace screens
