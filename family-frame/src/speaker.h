#pragma once
// Sound through the CYD's "SPEAK" connector: chimes, recorded clips and
// text-to-speech. The on-board amplifier is fed by the ESP32's 8-bit DAC on
// GPIO 26, so voices sound like a small radio: clear enough for news and answers.

#include <Arduino.h>

#include <functional>

namespace speaker {

using StopFn = std::function<bool()>;

bool enabled();  // cfg.speakerOn

void chime();      // two gentle notes (briefings)
void listenBeep(); // short beep before the microphone listens
void doneBeep();

bool playWav(const char* path, const StopFn& stop = nullptr);  // 16-bit mono PCM

// Speaks text with Google Translate's free voice (needs internet).
// Returns false if the voice couldn't be fetched; stop() is polled while playing.
bool say(const String& text, const StopFn& stop = nullptr);

// True after say() returned because stop() asked it to.
bool wasStopped();

}  // namespace speaker
