// PC unit tests for the feed reader and text cleaner (no ESP32 needed).
//
//   g++ -std=c++17 -Wall -Wextra -I../src test_text.cpp ../src/textutil.cpp ../src/rss.cpp -o build/test_text
//   ./build/test_text
//
// or just run `make` in this folder.

#include <stdio.h>
#include <string.h>

#include <string>

#include "rss.h"
#include "textutil.h"

static int failures = 0;

static void expectEq(const char* what, const std::string& got, const std::string& want) {
    if (got != want) {
        printf("FAIL %s\n  got:  \"%s\"\n  want: \"%s\"\n", what, got.c_str(), want.c_str());
        failures++;
    } else {
        printf("ok   %s\n", what);
    }
}

static std::string clean(const char* s) {
    char out[256];
    text::clean(s, out, sizeof out);
    return out;
}

// Feeds the whole document in small, uneven pieces, like data arriving over Wi-Fi.
static int parse(const char* doc, rss::Item* items, int max) {
    rss::Parser p(items, max);
    size_t n = strlen(doc);
    for (size_t i = 0; i < n; i += 7) p.put(doc + i, (n - i) < 7 ? (n - i) : 7);
    return p.count();
}

static const char GOOGLE_NEWS[] = R"(<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><generator>NFE/5.0</generator>
<title>Top stories - Google News</title><link>https://news.google.com/</link>
<item><title>Senate passes bill to fund government &#8211; what&#39;s next - The Washington Post</title>
<link>https://news.google.com/rss/articles/abc</link><guid isPermaLink="false">abc</guid>
<pubDate>Tue, 25 Nov 2026 13:00:00 GMT</pubDate><description>&lt;ol&gt;&lt;li&gt;&lt;a href="x"&gt;Story&lt;/a&gt;</description>
<source url="https://www.washingtonpost.com">The Washington Post</source></item>
<item><title>Caf&#233; owners say “prices” are up — again - Reuters</title><source url="https://www.reuters.com">Reuters</source></item>
<item><title>Third story - AP News</title><source url="https://apnews.com">AP News</source></item>
</channel></rss>)";

static const char ATOM[] = R"(<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><title type="text">The Verge</title>
<entry><title type="html"><![CDATA[The best <em>Black Friday</em> TV deals &amp; more]]></title>
<link rel="alternate" href="https://example.com/1"/><content type="html"><![CDATA[<p>Body with <title>x</title></p>]]></content>
<source><title>Not this one</title></source></entry>
<entry><title type="html">Apple’s new iPad, reviewed 🍎</title><link href="https://example.com/2"/></entry>
</feed>)";

static const char RSS_SIMPLE[] = R"(<rss><channel><title>Slickdeals Frontpage</title>
<item><title><![CDATA[Amazon: 2-Pack Echo Dot $39.99 + Free Shipping]]></title><description><![CDATA[<img src="a.jpg"/> > nice]]></description></item>
<item><title/></item>
<item><title>Marvel Contest of Champions&amp;#39; new season</title></item>
</channel></rss>)";

int main() {
    // Text cleaning
    expectEq("entities", clean("Tom &amp; Jerry &lt;3 &quot;hi&quot; &#39;yo&#39; &#x2014; done"),
             "Tom & Jerry <3 \"hi\" 'yo' - done");
    expectEq("utf-8 punctuation", clean("“Quoted” — it’s… here"), "\"Quoted\" - it's... here");
    expectEq("accents", clean("Beyoncé, Dončić, Zoë, Łódź, São Paulo"), "Beyonce, Doncic, Zoe, Lodz, Sao Paulo");
    expectEq("emoji dropped", clean("Party 🎉 time ✨ now"), "Party time now");
    expectEq("whitespace", clean("  lots \n\t of   space  "), "lots of space");
    expectEq("tags and cdata", clean("<![CDATA[<b>Bold</b> move]]>"), "Bold move");
    expectEq("double encoded", clean("it&amp;#39;s &amp;amp; ok"), "it's & ok");
    expectEq("bare ampersand", clean("AT&T & Verizon"), "AT&T & Verizon");
    {
        char small[8];
        text::clean("A long headline here", small, sizeof small);
        expectEq("truncates safely", small, "A long");
    }

    // HH:MM
    uint16_t m = 0;
    expectEq("parse time", text::parseHHMM("07:30", &m) ? std::to_string(m) : "bad", "450");
    expectEq("reject time", text::parseHHMM("25:00", &m) ? "accepted" : "rejected", "rejected");
    char hhmm[6];
    text::formatHHMM(18 * 60 + 5, hhmm);
    expectEq("format time", hhmm, "18:05");

    // Google News RSS
    rss::Item items[6];
    int n = parse(GOOGLE_NEWS, items, 6);
    expectEq("gn count", std::to_string(n), "3");
    expectEq("gn title 1", items[0].title, "Senate passes bill to fund government - what's next");
    expectEq("gn source 1", items[0].source, "The Washington Post");
    expectEq("gn title 2", items[1].title, "Cafe owners say \"prices\" are up - again");
    expectEq("gn source 2", items[1].source, "Reuters");

    // Limit stops early
    n = parse(GOOGLE_NEWS, items, 2);
    expectEq("max items", std::to_string(n), "2");

    // Atom
    n = parse(ATOM, items, 6);
    expectEq("atom count", std::to_string(n), "2");
    expectEq("atom title 1", items[0].title, "The best Black Friday TV deals & more");
    expectEq("atom title 2", items[1].title, "Apple's new iPad, reviewed");
    expectEq("atom source empty", items[0].source, "");

    // Plain RSS with CDATA, an empty self-closed title and a double-encoded entity
    n = parse(RSS_SIMPLE, items, 6);
    expectEq("rss count", std::to_string(n), "2");
    expectEq("rss cdata", items[0].title, "Amazon: 2-Pack Echo Dot $39.99 + Free Shipping");
    expectEq("rss double", items[1].title, "Marvel Contest of Champions' new season");

    // A very long title is cut, not overflowed
    std::string longDoc = "<rss><item><title>" + std::string(1000, 'x') + "</title></item></rss>";
    n = parse(longDoc.c_str(), items, 6);
    expectEq("long count", std::to_string(n), "1");
    expectEq("long length", std::to_string(strlen(items[0].title)), "159");

    printf(failures ? "\n%d FAILED\n" : "\nall passed\n", failures);
    return failures ? 1 : 0;
}
