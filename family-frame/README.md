# Family Frame for the Cheap Yellow Display

Firmware that turns an **ESP32-2432S028R** ("Cheap Yellow Display", or CYD) into a little digital picture frame that also keeps the family up to date: news briefings in the morning, at lunch and in the evening, weather, deals, and a voice assistant you can ask questions.

## What it does

- **Photo slideshow** from the microSD card, with a gentle fade, shuffle, and an optional **clock and weather overlay** in any corner.
- **Add photos from a phone, no app needed.** Tap the screen, tap **Add**, and scan the code with the phone's camera. Pick photos and they appear on the frame a few seconds later. Photos are resized on the phone first, so a 12-megapixel picture uploads as a small 320×240 file and tall photos show whole over a soft blurred background.
- **News cards between photos.** After every few photos the frame shows two headlines from one of your feeds, or today's weather.
- **Briefings** at three times you choose (7:30, 12:00 and 18:00 to start). The frame chimes, says good morning with the family's names, gives the date and weather, and reads the top headlines from each feed. Tap to skip a feed, hold to stop.
- **Follow anything.** Type a topic ("Kansas City Chiefs", "gardening") and the frame follows it through Google News. Ready-made sources include Google News sections, **The Washington Post**, NPR, BBC, AP, The Verge, Ars Technica, TechCrunch, **Amazon deals** (Slickdeals), **Marvel Contest of Champions**, Marvel movies and TV, and news about TikTok and Instagram trends. Any RSS or Atom link works too.
- **Voice assistant** (with the optional microphone and speaker): tap **Ask**, or hold the screen, and say *"What's the news today?"*, *"Any good Amazon deals?"* or *"How tall is the Eiffel Tower?"*. It knows the frame's latest headlines and the weather. It uses a free AI service ([Groq](https://console.groq.com)) and can be pointed at any OpenAI-compatible service later.
- **Night mode** dims or turns off the screen overnight. A tap wakes it.
- **A settings page** on any phone or computer on the same Wi-Fi, at `http://familyframe.local` or the address shown on the frame. It also updates the firmware, so you can send improvements later without a USB cable.

## What you need

- An ESP32-2432S028R CYD (the original with one micro-USB port, or the "CYD2USB" with USB-C and micro-USB).
- A **microSD card**, 32 GB or smaller, formatted **FAT32**.
- A USB power supply, 5 V 1 A or more (2 A if you add the speaker).
- Optional, for voice: an **INMP441** microphone and a small **8 ohm** speaker. See the [wiring guide](docs/WIRING.md).

## Install

### 1. Flash the firmware from your browser

Every push builds the firmware on GitHub Actions. Open this repository's **Actions** tab, choose the latest **Family Frame firmware** run, and download **FamilyFrame-firmware**.

| Your board | File |
| --- | --- |
| One micro-USB port (original CYD) | `family-frame-cyd-factory.bin` |
| USB-C and micro-USB ("CYD2USB") | `family-frame-cyd2usb-factory.bin` |

1. Use **Chrome or Edge on a computer** and plug the CYD in with a USB **data** cable.
2. Open <https://espressif.github.io/esptool-js/> and click **Connect**. Pick the board's serial port (often "CP210x" or "CH340").
3. Set *Flash Address* to `0x0`, choose the `-factory.bin` file and click **Program**.
4. When it finishes, press the board's **RST** button.

If Connect fails, hold **BOOT** while clicking Connect. On Windows you may need the CH340 or CP210x driver.

### 2. First start

Put the microSD card in first. Then:

1. **Touch setup**: tap the center of two targets.
2. **Color check**: if the four boxes look like a photo negative, tap **Invert**; if red and blue are swapped, tap **Swap**. Then **Looks right**.
3. **Wi-Fi**: the frame shows a code. Scan it (or join the Wi-Fi network **FamilyFrame-Setup**), and a setup page opens. Choose the home network, type its password, pick the time zone and the names for greetings. The frame restarts and joins.

Then open the frame's page (tap the screen, tap **Add**, and scan the code) and:

- **Settings tab**: type the town for weather and press **Find**, check the briefing times, and press **Save settings**.
- **News tab**: switch feeds on or off, add topics, press **Save feeds**.
- **Assistant tab** (if you wired the mic and speaker): get a free key and tick the boxes. See [Voice assistant](#voice-assistant).

### Getting it ready as a gift

- Load photos before wrapping it: upload them from your phone, or copy JPEG files into the card's `photos` folder on a computer.
- If you know their Wi-Fi details, enter them on the **Frame** tab as the very last step: the frame then joins only their network, so you can't reach its page at your place any more. Otherwise they scan the setup code on the frame when they plug it in.
- Set their names, town and time zone, so the first morning briefing is personal.

## Everyday use

| To... | Do this |
| --- | --- |
| See the buttons | Tap the screen. **Back** and **Next** move through photos, **News** opens the headlines, **Ask** starts the assistant, **Add** shows the code for adding photos. |
| Ask a question | Tap **Ask**, or hold the screen anywhere. Wait for the beep, speak, then pause. |
| Read the news | Tap **News**. Use the arrows to page; **Read** reads the two headlines aloud. |
| Add photos | Tap **Add** and scan the code with the phone's camera. The phone must be on the same Wi-Fi. |
| Wake the screen at night | Tap it. It stays on for two minutes. |
| Hear the briefing now | On the page's **News** tab, press **Play briefing**. Or ask "play the briefing". |
| Change Wi-Fi | On the page's **Frame** tab. If the frame can't reach the page, hold the screen while plugging it in, and it starts the setup network again. |
| Redo touch or colors | Hold the **BOOT** button while plugging it in, or use **Settings › Redo touch setup**. |

## Voice assistant

The assistant needs the microphone wired ([wiring guide](docs/WIRING.md)) and a free key from Groq:

1. Open [console.groq.com/keys](https://console.groq.com/keys) and sign in.
2. Click **Create API Key**, copy it, paste it on the frame page's **Assistant** tab and press **Save**.

The frame records the question to the SD card, sends it to Groq's Whisper speech-to-text, then sends the words and the frame's latest headlines and weather to a Groq chat model. The answer is shown on screen and read aloud with Google Translate's free voice. Groq's free plan has daily limits far above what a household uses.

To switch to a paid service later, open **Assistant › Advanced** and change the address and model names. Any OpenAI-compatible service works (OpenAI's address is `https://api.openai.com/v1`). The model names are settings too, so if Groq retires a model, type its replacement there.

You can try it without the mic from the **Try it by typing** box on the Assistant tab.

## About the news sources

| Source | How the frame gets it |
| --- | --- |
| Google News, topics you type | Google News RSS feeds |
| The Washington Post | Its public RSS feeds. If the Post changes them, use the "WaPo (via Google News)" preset. |
| Tech news | The Verge, Ars Technica, TechCrunch and Engadget RSS feeds |
| Amazon deals | Slickdeals' RSS, filtered for Amazon. These are deals people vote up, not Amazon's own list. |
| Marvel Contest of Champions, Marvel movies | Google News searches |
| X (Twitter), TikTok, Instagram | **Not directly.** X charges for reading posts through its API, and TikTok and Instagram don't let devices read feeds at all. The frame follows news *about* them through Google News instead. If you have an RSS link for an account (services like rss.app make them), paste it in as a custom feed. |

The **News** tab shows each feed with a green or red dot, so you can see at a glance if a source stops working.

## Why Wi-Fi instead of Bluetooth for photos

- iPhones can't send photos to other devices over Bluetooth (only AirDrop, between Apple devices), so Bluetooth would leave out iPhone users entirely.
- The CYD's ESP32 has no extra memory chip. Bluetooth, Wi-Fi and secure web connections together don't fit, and the news and assistant need Wi-Fi.
- The Wi-Fi page works on every phone and computer with no app, and it's much faster.

## What about videos?

Not supported. The CYD has a small 320×240 screen and no extra memory, so it can't play videos from TikTok, Instagram or YouTube. Short, silent, low-frame-rate clips stored on the SD card could be added later, but they'd look choppy.

## The SD card

```
photos/            your photos (JPEG). Add or remove files here from a computer if you like.
frame/config.json  all settings, including Wi-Fi and AI key (readable, so keep the card private)
frame/news.json    the latest headlines, so cards show right away after a restart
frame/photos.idx   the photo list (rebuilt automatically)
```

Photos copied straight from a camera work, but large ones take several seconds to draw and are cropped to fit. Progressive JPEGs can't be shown. Uploading through the page avoids both.

## Privacy and security

- Photos and settings stay on the SD card. Nothing is uploaded anywhere except: the questions you ask (to the AI service), text to be spoken (to Google), and your town's coordinates (to Open-Meteo for weather).
- Anyone on the home Wi-Fi can open the settings page unless you set a **page password** on the **Frame** tab (user name `family`).
- To keep working as sites renew their security certificates, the frame doesn't verify certificates. A device on the same network could in theory read its traffic, including the AI key. Use a key made just for the frame.

## Troubleshooting

| Problem | Try |
| --- | --- |
| White or garbled screen | Flash the other `-factory.bin` file (the two board types use different display chips). |
| Taps land in the wrong place | Hold **BOOT** while plugging in to redo touch setup. |
| "Insert a microSD card" | Use a FAT32 card of 32 GB or less, pushed in until it clicks. |
| No headlines | Check the **News** tab's dots. Red means the site refused or moved its feed; remove it or pick another source. |
| No weather | Set the town on the **Settings** tab and press **Save settings**. |
| No voice | Tick **Speaker is connected** and press **Test speaker**. The voice needs internet. |
| Assistant says the key was refused | Make a new key at console.groq.com/keys and paste it again. |
| Can't open `familyframe.local` | Some Android phones don't support `.local` names. Use the number address shown under **Add** on the frame. |

## Build it yourself

You need [PlatformIO](https://platformio.org/install/cli) (`pip install platformio`).

```sh
cd family-frame
pio run -e cyd                 # or -e cyd2usb
pio run -e cyd -t upload       # build and flash over USB
pio device monitor             # serial log, 115200 baud
cd test && make                # PC tests for the feed reader and text cleanup
```

Each build writes two files to `.pio/build/<env>/`: `family-frame-<env>-factory.bin` for a first install at address 0x0, and `family-frame-<env>-update.bin` for the web page's **Update firmware** button.

### How it's put together

```
src/main.cpp        what's on screen, touch, the daily schedule (briefings, night mode)
src/photos.cpp      photo list, JPEG drawing, clock + weather overlay
src/news.cpp        feeds and weather, fetched by a background task
src/rss.cpp         streaming RSS/Atom reader (keeps only titles; feeds can be huge)
src/textutil.cpp    turns feed text into plain ASCII for the display fonts
src/screens.cpp     every screen layout                 src/gfx.cpp      drawing helpers, QR codes, icons
src/assistant.cpp   microphone recording, speech-to-text, AI chat
src/speaker.cpp     chimes, text-to-speech and MP3 playback through the built-in DAC
src/webui.cpp       the web page's server; the page itself is in web_index.h
src/settings.cpp    settings saved as frame/config.json
```

| Part | Pins |
| --- | --- |
| Display (HSPI) | MOSI 13, MISO 12, SCK 14, CS 15, DC 2, backlight 21 |
| Touch XPT2046 (bit-banged) | CLK 25, MOSI 32, MISO 39, CS 33 |
| microSD (VSPI) | SCK 18, MISO 19, MOSI 23, CS 5 |
| Speaker (on-board amplifier) | GPIO 26 (DAC) |
| INMP441 microphone (I2S1) | SCK 22, WS 27, SD 35 |
| RGB LED (active low) | R 4, G 16, B 17 |

## Credits

Built with [TFT_eSPI](https://github.com/Bodmer/TFT_eSPI), [TJpg_Decoder](https://github.com/Bodmer/TJpg_Decoder), [ArduinoJson](https://arduinojson.org) and [ESP8266Audio](https://github.com/earlephilhower/ESP8266Audio). Weather from [Open-Meteo](https://open-meteo.com). Headlines belong to their publishers. Not affiliated with Google, The Washington Post, Amazon, Slickdeals, Marvel, Kabam or Groq.
