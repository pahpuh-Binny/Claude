#pragma once
// Text helpers in plain C++ (no Arduino headers) so they can be unit-tested on a PC.
// See test/test_text.cpp.

#include <stddef.h>
#include <stdint.h>

namespace text {

// Turns feed or AI text into plain ASCII that the display fonts can draw:
// drops CDATA markers and HTML tags, decodes entities (&amp; &#8217; ...),
// maps curly quotes, dashes and accented letters to ASCII, drops emoji and
// collapses whitespace. Always NUL-terminates. Returns the output length.
size_t clean(const char* in, char* out, size_t outSize);

// Appends the ASCII spelling of one Unicode code point ("" for emoji etc.).
// Returns the number of bytes written (never more than room).
size_t asciiFor(uint32_t cp, char* out, size_t room);

// Removes " - <source>" from the end of a title (Google News adds it).
void stripSourceSuffix(char* title, const char* source);

// "07:30" <-> minutes after midnight.
bool parseHHMM(const char* s, uint16_t* minutes);
void formatHHMM(uint16_t minutes, char out[6]);

}  // namespace text
