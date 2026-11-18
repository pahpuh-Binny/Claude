# CSI Beacon & WiFi-Sensing Radar for the Cheap Yellow Display

Firmware for the **ESP32-2432S028R** ("Cheap Yellow Display" / CYD) that turns the
board into a **WiFi Channel-State-Information (CSI) beacon** and a standalone
**WiFi "radar"** display. It's meant to pair with a second ESP32 device — for
example an M5Stack **Cardputer** — that captures CSI and does the sensing math.

## What CSI sensing actually is

Every WiFi frame, as it travels from transmitter to receiver, bounces off walls,
furniture and people. The receiver's radio measures how each of the ~64
OFDM subcarriers was attenuated and phase-shifted along the way — that per-frame
snapshot is the **Channel State Information**. When something in the room moves,
the multipath changes and the CSI changes with it. Watching CSI over time lets
you detect **presence, motion, breathing, gestures, or someone walking through a
doorway** — all without a camera and without the sensed person carrying anything.
It's a well-established research technique (and the basis of commercial "WiFi
motion" features in some home routers).

To measure CSI you need two things:

1. **A steady transmitter** emitting frames at a known, regular rate — the *beacon*.
2. **A receiver** that captures those frames and reads the CSI — here, the radar
   mode, or your Cardputer.

This firmware is both halves. It only transmits beacon frames and reads radio
channel state; it does not deauthenticate clients, capture credentials, crack
passwords, or attack other networks.

## Modes

Switch modes with the **BOOT** button (cycles Beacon → Radar → LoRa) or over the
USB serial console.

### 1. Beacon (transmit) — the default

- Brings up a SoftAP named `CSI-BEACON`. A SoftAP already emits ~10 management
  beacon frames per second on its own, which a receiver can lock onto with **no
  association required**.
- On top of that it **injects extra 802.11 action frames** at a configurable rate
  (100 Hz by default) from a fixed, recognizable source MAC
  (`02:43:53:49:00:01`) with a `CSIB` magic marker and an incrementing sequence
  number + timestamp in the payload. Your receiver filters on that MAC so it only
  measures CSI from *this* beacon and ignores unrelated traffic.
- The screen shows the channel, injection rate, and frames-sent counter. The
  onboard LED pulses blue.

Steady, high-rate, identifiable frames are exactly what a CSI receiver wants: more
frames per second = finer time resolution for motion.

### 2. Radar (receive + visualise)

- Puts the ESP32 into promiscuous mode with CSI capture enabled
  (`esp_wifi_set_csi`) and draws a live **amplitude waterfall**: time scrolls
  left-to-right, subcarriers run top-to-bottom, colour = amplitude
  (blue → green → red).
- A **motion bar** at the top rises when the channel starts jittering — i.e. when
  something moves. The LED turns **red on movement**, **green when still**.
- Point one CYD running Beacon at another running Radar on the same channel and
  walk between them to watch the waterfall ripple.

### 3. LoRa link (optional — needs extra hardware)

The CYD has **no onboard LoRa radio**, so this mode is only compiled into the
`cyd-lora` build and only works if you wire an **SX1276/78** module to the board
(see *Wiring* below). It's a small plaintext broadcast link: it sends a periodic
`hello` beacon and prints/counts whatever it receives, over the serial console.
Handy as an out-of-band control/telemetry channel between your two devices when
you don't want to use WiFi.

> **Meshtastic note:** this is *not* the Meshtastic protocol — it's a minimal raw
> LoRa link. It defaults to the LongFast sync word (`0x2B`) and 915 MHz so it sits
> on the same radio settings, but it will not join a Meshtastic mesh or speak its
> encrypted packet format. If you want a real mesh, flash
> [Meshtastic](https://meshtastic.org) onto a supported LoRa board and use this
> firmware's WiFi/CSI features alongside it. The raw link here is for simple
> device-to-device messaging you fully control.

## Install

### 1. Pick a build

| Your board | Build | File |
| --- | --- | --- |
| One micro-USB port (original CYD, ILI9341) | `cyd` | `firmware-cyd.bin` |
| Two ports, USB-C + micro-USB (ST7789) | `cyd2usb` | `firmware-cyd2usb.bin` |
| Either, **with a wired SX127x LoRa module** | `cyd-lora` | `firmware-cyd-lora.bin` |

If unsure, start with `cyd`. If the screen is white or garbled, use `cyd2usb`.

### 2. Flash from your browser (no installs)

1. Use **Chrome or Edge** and a USB **data** cable.
2. Open <https://espressif.github.io/esptool-js/>, click **Connect**, pick the
   serial port (often "CP210x" or "CH340").
3. Flash address `0x0`, choose the `.bin`, click **Program**. Press **RST** when done.

If Connect fails, hold **BOOT** while clicking Connect; on Windows install the
CH340/CP210x driver.

## Using it with a Cardputer (or any CSI receiver)

1. Flash **Beacon** mode onto the CYD. Note its channel (serial: `ch 6`) and the
   source MAC `02:43:53:49:00:01`.
2. On the receiver, enable CSI capture on the **same channel** and (optionally)
   filter for that source MAC. Most ESP32 CSI tools expose exactly these two
   settings. The beacon's steady 100 Hz injected frames become your CSI stream.
3. Feed the CSI into whatever sensing you're doing (amplitude variance for
   motion, phase for finer work, or a model for gesture/breathing).

To sanity-check without the Cardputer, flash **Radar** onto a *second* CYD on the
same channel and watch its waterfall react to the beacon and to movement.

## Serial console (115200 baud)

```
mode beacon|radar|lora   switch mode
ch <1-13>                set WiFi channel (beacon & radar)
rate <hz>                set beacon injection rate (0 = automatic beacons only)
lora <text>              send a LoRa message (lora mode)
stats                    print counters
help
```

## Configuration

Power-on defaults live in [`src/config.h`](src/config.h): AP SSID/password,
beacon source MAC, magic marker, channel, injection rate, start-up mode, and all
the LoRa radio + pin settings. Everything there can also be changed at runtime
over serial.

## Wiring an SX127x (LoRa) module

The CYD exposes only a handful of GPIOs, so LoRa shares the SD card's VSPI bus and
uses the broken-out header pins. **Defaults in `config.h`:**

| SX127x pin | CYD pin | Notes |
| --- | --- | --- |
| SCK  | GPIO18 | shared with the SD card slot |
| MISO | GPIO19 | shared with the SD card slot |
| MOSI | GPIO23 | shared with the SD card slot |
| NSS/CS | GPIO22 | CN1 header |
| RST | GPIO27 | P3 / "extend" header |
| DIO0 | GPIO35 | CN1 header (input-only GPIO — fine for an IRQ line) |
| VCC | 3V3 | **3.3 V only** |
| GND | GND | |

Verify these against *your* CYD revision before powering up — board layouts vary,
and GPIO21 is the backlight on most units. Pick a frequency band (`CFG_LORA_FREQ_MHZ`)
that is both legal in your country and matches your module (433/868/915 MHz), and
keep the transmit power within your local limits.

## Build it yourself

```sh
pip install platformio
pio run -e cyd                 # or: cyd2usb, cyd-lora
pio run -e cyd -t upload       # flash over USB
pio run -e cyd -t upload -t monitor
```

## Responsible use

CSI sensing can detect people and their movement. Only operate it on radios,
spectrum and spaces you're authorised to use, follow your local radio
regulations, and don't use it to monitor anyone without their knowledge and
consent.
