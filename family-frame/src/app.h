#pragma once
// Requests from the web page. Web handlers only set these; the main loop
// carries them out, so a slow job never blocks the page's reply.

#include <Arduino.h>

namespace app {

void requestBriefing();
void requestShowPhoto(const String& name);
void photoUploaded(const String& name);
void photosChanged();
void requestSay(const String& text);
void requestMicTest();
void requestSpeakerTest();
void requestTouchSetup();
void requestReboot();
void settingsChanged(bool wifiChanged);

}  // namespace app
