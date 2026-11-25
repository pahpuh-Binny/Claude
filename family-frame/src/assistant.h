#pragma once
// The voice assistant: records from the INMP441 microphone, turns speech into
// text and asks an AI chat model, using any OpenAI-compatible service.
// Groq's free tier is the default (https://console.groq.com/keys).

#include <Arduino.h>

#include <functional>

namespace assistant {

struct Recording {
    bool ok = false;           // the microphone could be started
    bool heardSpeech = false;  // someone spoke (vs. only room noise)
    uint32_t ms = 0;           // length kept
    int peak = 0;              // loudest 16-bit sample, for the mic test
    int noise = 0;             // average background level
};

constexpr const char* RECORDING_PATH = "/frame/question.wav";

// Records 16 kHz mono WAV to path. With untilSilence, stops ~1 s after the
// speaker pauses; otherwise records for maxMs. level() gets 0..100 for a meter.
Recording record(const char* path, uint32_t maxMs, bool untilSilence, const std::function<void(int)>& level,
                 const std::function<bool()>& stop);

bool ready(String& why);  // key set, microphone on, Wi-Fi up

bool transcribe(const char* wavPath, String& text, String& error);
bool ask(const String& question, String& answer, String& error);

}  // namespace assistant
