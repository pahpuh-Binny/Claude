# USB Capture

An Android app that turns your phone or tablet into a monitor and recorder for **USB HDMI capture cards** and **USB webcams** (any UVC "USB Video Class" device). Plug a console, camera or computer into a capture card, plug the card into the phone's USB-C port, and the picture appears.

It has its own USB video driver built in, so it works on phones whose Android has no USB camera support, and it is built to take the highest resolution and frame rate the card offers without stutters or crashes. Anything that does go wrong is written to a log you can view and share from the app.

## Install

1. Get `app-release.apk`: open this repository's **Actions** tab, pick the latest **USB Capture APK** run and download the `UsbCapture-apk` artifact (a zip with the APK inside).
2. Open the APK on your phone and allow installing from that app (Chrome, Files…), then tap **Install**.
3. Open **USB Capture**. Allow **Camera** (Android requires it before any app can use a USB camera or capture card; your phone's own cameras are never used) and **Microphone** (the capture card's sound arrives as a USB microphone).
4. Plug in the capture card. When Android asks, tick **Always open** so it connects instantly next time.

Needs Android 8.0 or newer and a phone with USB OTG (USB host) support, which nearly all USB-C phones have.

## Two modes

**Play mode** (default) is made for using the phone as a monitor: full screen, landscape, big buttons that fade away after a few seconds, and a quick settings sheet with just the essentials (quality, resolution, frame rate, fit/fill, sound, volume, lip-sync delay, rotation, brightness).

**Expert mode** (tap **Play** at the top to switch) adds a side panel with every setting in tabs:

| Tab | What's in it |
| --- | --- |
| **Video** | Quality preset, resolution, frame rate and pixel format (MJPEG, YUY2, NV12…), a list of every mode the device offers, the negotiated USB settings |
| **Image** | The device's own picture controls, read from the device: brightness, contrast, hue, saturation, sharpness, gamma, white balance, gain, backlight, anti-flicker (50/60 Hz), exposure mode/time, focus, autofocus, zoom, pan/tilt, roll, privacy… Reset to defaults |
| **Display** | Fit / Fill / Stretch / Pixel-perfect (integer) / 1:1 scaling, aspect ratio override (16:9, 4:3, 21:9, 8:7…), smooth / nearest / sharp-bilinear filtering, overscan crop, zoom, rotate, mirror, flip, brightness, contrast, saturation, gamma, hue, sharpen, CRT scanlines, black & white, BT.601/709 and limited/full range |
| **Assist** | Rule-of-thirds / quarters / golden grids, centre mark, safe areas, zebra stripes, false colour, focus peaking (sensitivity and colour), luma or RGB histogram, audio meters, live statistics |
| **Audio** | Capture audio on/off, monitoring through the phone, volume up to 200%, A/V sync delay, input device, MS2109 audio fix, commentary microphone mixing (device, level, mute) |
| **Record** | H.264 or HEVC, recording resolution and bitrate, audio on/off, instant replay length, screenshot format |
| **Advanced** | MJPEG decoder threads, 16-bit fast decoding, incomplete-frame handling, USB error tolerance, auto recovery, display latency, USB transfer queue tuning, maximum bandwidth |
| **App** | Mode, rotation, full screen, control auto-hide, maximum brightness, auto picture-in-picture, haptics, test pattern |
| **Info** | Device name, USB ID, connection speed, full USB descriptor report (copy or write to the log) |

## Features

- **Built-in UVC driver** written in C on top of Linux usbfs: isochronous *and* bulk transfers, USB 2 high-bandwidth and USB 3 SuperSpeed endpoints, automatic USB bandwidth selection, a deep transfer queue on a high-priority thread.
- **Highest quality without hiccups**: frames are kept in a small "newest frame wins" ring buffer at every stage (USB → decoder → screen), so a slow moment drops a frame instead of building up delay; MJPEG is decoded on up to 4 threads; YUV formats are converted on the GPU. If a mode fails, the app falls back to the next best one automatically, and if video stops it restarts the stream by itself.
- **Formats**: MJPEG, YUY2, UYVY, YVYU, NV12, NV21, I420, YV12, grey, RGB565, RGB24/BGR24, BGRA.
- **Full screen** with hidden system bars and auto-hiding controls, **picture-in-picture**, touch lock, pinch to zoom, double-tap fit/fill, matching the screen refresh rate to the video, keep screen on, max brightness.
- **Audio**: plays the capture card's sound with adjustable lip-sync delay and clock-drift correction (no slowly growing delay or crackles), volume boost, level meters, and a fix for MS2109 cards that report their audio format wrongly. A **commentary microphone** (phone, headset or USB mic) can be mixed into recordings.
- **Recording** to MP4 (H.264/HEVC + AAC) in Movies/USB Capture, automatically split before 4 GB with no frames lost; **instant replay** of the last 15 s–2 min; full-resolution **screenshots** (PNG/JPEG) in Pictures/USB Capture.
- **Test pattern** (1080p60 colour bars with a frame counter) to try everything without a device.
- **Keyboard / remote shortcuts**: F full screen, S screenshot, R record, M mute, P picture-in-picture, E expert mode, I statistics, Z scaling, G grid, Space show/hide controls.

## Logs and crash reports

Everything goes to a log you can open from the **Logs** button (top right), copy, or **Share** as files:

- Java crashes on any thread, **native crashes** (signal handler in the C engine), and on Android 11+ the system's own record of **ANRs** ("not responding"), low-memory kills and native crashes from the previous run.
- **UI freezes** longer than 2 seconds, with where the app was stuck.
- **Video hiccups**: gaps between frames that are much longer than expected, summarised every 10 seconds.
- USB problems: transfer errors, damaged or dropped frames, stalls, disconnects, every mode tried and why it failed, the device's full descriptors.
- Phone temperature warnings and low-memory warnings.

If the app crashed or froze last time, it tells you on the next start and offers to show the report. The log rotates at 2 MB, so it never fills the phone.

## Tips

- **Cheap USB 2 cards (MS2109 and similar)** do 1920×1080 at 30 fps or 1280×720 at 60 fps using MJPEG. "Best quality" picks 1080p30, "Smoothest" picks 720p60.
- **USB 3 cards** can do 1080p60 uncompressed, but only if the phone's port *and* the cable are USB 3. The Info tab shows the actual connection speed.
- If the sound comes before the picture, increase **Sound delay**. If blacks look grey, set **Range** to Full.
- If a card keeps disconnecting, it may need more power than the phone gives: use a powered USB-C hub.

## Build it yourself

You need JDK 17+, the Android SDK (platform 35), NDK 27.2.12479018 and CMake 3.22.1.

```sh
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/app-release.apk
```

Every push that changes `usb-capture/` builds the APK on GitHub Actions (`.github/workflows/usb-capture-android.yml`) after running the tests.

### Tests

```sh
# Native frame assembler under AddressSanitizer, UBSan and ThreadSanitizer (Linux host)
CC=gcc android/app/src/test/native/run_tests.sh
# Descriptor parsing, mode ranking and screen geometry on the JVM
cd android && ./gradlew testReleaseUnitTest
```

The native tests feed synthetic UVC packets through the frame assembler (headers, frame toggles, errors, truncated/oversized/invalid JPEGs, isochronous packet parsing, a multi-threaded stress run). The JVM tests parse descriptor sets laid out exactly like an MS2109 USB 2 card and a USB 3 bulk card.

## How it works

```
android/app/src/main/
  cpp/uvc_stream.c        USB transfers (usbfs ioctls), UVC payload parsing, frame ring buffer
  cpp/crash_handler.c     Writes a report when native code crashes
  java/.../uvc/           Descriptor parser, probe/commit negotiation, bandwidth choice, picture controls
  java/.../video/         Frame sources: raw (zero-copy), MJPEG decoder pool, test pattern
  java/.../render/        OpenGL ES 3 renderer: YUV→RGB on the GPU, scaling, effects, encoder/screenshot passes
  java/.../audio/         Capture-card audio monitor with drift correction and mic mixing
  java/.../record/        MediaCodec encoders, MP4 writer with auto-split, instant replay buffer
  java/.../log/           Event log, crash/ANR reports, UI freeze watchdog
  java/.../CaptureController.java  Device lifecycle, mode fallback, stall watchdog and recovery
  java/.../MainActivity.java       The screen (Play and Expert modes)
```

Android opens the USB device and hands its file descriptor to the C engine, which keeps 8–16 transfers queued at all times and assembles frames. Uncompressed frames are uploaded straight from native memory to the GPU; MJPEG frames are decoded on worker threads. Each frame is converted once to an RGB texture, which is then drawn to the screen, the video encoder, screenshots and the histogram.

Not supported yet: devices that only send H.264/H.265 streams, and P010 (10-bit) video.
