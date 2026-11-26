# Multiview Browser

A private Android browser built for watching videos. Videos keep playing when you switch tabs, leave the app or turn the screen off. You can watch up to four tabs side by side. Ads and trackers are blocked, and saved videos are rebuilt into new files and kept encrypted.

## What it does

| Feature | How it works |
| --- | --- |
| **Videos keep playing** | Other browsers pause a video when you leave its tab or the app. Multiview never pauses its tabs. Pages always see themselves as "visible", so sites can't detect that you've left. A notification with **Pause all / Play all** keeps the app running, and headphone and lock-screen buttons work. Unplugging headphones pauses everything. |
| **Multiview** | Tap the grid button to watch 2–4 tabs at once. The layout adapts to the screen and rotation so each video gets as much space as possible. Each tile has a sound button, a button to show it alone, and a button to remove it. By default the sound follows the tile you tap, so the videos don't talk over each other. |
| **Many tabs playing** | Android only lets one player have sound at a time. When a second tab starts playing with sound, the first tab keeps playing muted instead of stopping, and you can tap its speaker button to switch the sound back. |
| **Picture-in-picture** | Shrink the browser (including multiview) into a floating window, from the menu or automatically when you leave the app (Settings). |
| **Ad, tracker and pop-up blocking** | Requests to ad, tracker and malware servers are blocked, along with known ad boxes on pages. Skippable video ads are skipped automatically and the rest fast-forwarded. Pop-ups a page opens on its own are blocked, and a new tab opened by something you tap is closed if it leads to an ad server. A built-in list works offline. The app downloads larger lists weekly: StevenBlack, EasyList, EasyPrivacy, URLhaus malware and a phishing list. You can turn blocking off for a single site from the shield button. |
| **Protection from malicious sites** | Pages on the malware and phishing lists are stopped before anything loads, and a warning page is shown. Google Safe Browsing is on too. HTTPS-only mode upgrades every connection and warns you before opening a site that isn't encrypted. Pages with bad certificates are never opened. Sites can't open other apps unless you tap something, and Multiview asks first. Only certificates built into Android are trusted, not ones added to the phone. |
| **Tracker protection** | Third-party cookies are blocked. Tracking codes (`utm_`, `fbclid`, `gclid` and others) are removed from links. Sites receive Global Privacy Control and Do Not Track signals. Ad-attribution reporting is turned off, and pages get a generic Chrome user agent instead of the one that identifies your phone model and the app. |
| **Search, SafeSearch off** | Choose DuckDuckGo (default), Brave Search, Startpage, Qwant, Google or Bing. SafeSearch is always off. Nothing is sent while you type (no search suggestions), and searches aren't saved. |
| **Saving videos (the vault)** | When a page has a video file or stream, the download button appears in the address bar. See [How saved videos are kept safe](#how-saved-videos-are-kept-safe). |
| **Vault gallery** | Watch your saved videos together as a video wall: 2, 4, 8, 16, 24 or all of them at once, with pages when there are more. Pinch to zoom, drag or fling to move around, and double-tap a video to zoom in on it. Every video has its own controls: play/pause, sound on/off, volume, a seek bar, speed (0.5–2×), loop on/off and a menu. Drag a video's ◢ corner to resize it (1×1 up to 4×4 tiles), and the others flow around it. Tap a video to hear only that one. Sizes, order (newest first or shuffled) and crop-or-fit are remembered. |
| **Vault player (VLC-style)** | Your saved videos play as a playlist, with **previous / next**, back and forward 10 s, and a back button. Swipe gestures work like VLC: **left side up/down = brightness**, **right side up/down = volume** (past 100% it boosts up to 200%), **sideways = seek** with a preview, **double-tap left/right = back/forward 10 s** (keep tapping to go further), double-tap the middle = play/pause, **hold = 2× speed**, **pinch = crop/fit**. It also has a speed menu (0.25×–4×), **loop** (off, all videos, this video), an **A–B loop** for repeating a section, aspect modes (fit, crop, stretch), a rotation lock and a **controls lock**. Each video resumes where you left it. |
| **Cast** | Send a page's video to a Chromecast or Google TV, starting where you were. For sites that don't expose a video file (YouTube, Netflix…), **Mirror screen** opens Android's screen casting. |
| **Tab groups** | Put tabs in named, coloured groups, for example "Sports", "Music" or "Watch later". In the tab overview, groups appear as sections you can collapse. From a group's menu you can **watch it together** in multiview, pause, play, mute or unmute the whole group, open a new tab in it, rename or recolour it, save it as a session, ungroup it or close it. While you're in a grouped tab, a bar under the address bar lists the group's tabs so you can switch between them in one tap. Links that a grouped tab opens in a new tab join its group. |
| **Sessions** | Save your open tabs, including their groups, which ones were in multiview and where each video was, as a named session with a preview picture. Reopen it later, replacing or adding to your tabs. Your tabs are also reopened automatically when the app starts (you can turn this off). |
| **Bookmarks with video previews** | Bookmarking a page with a playing video records a few seconds of the video as a short moving preview. The bookmark also remembers your position in the video, and opening it later continues from there. Bookmarks show as animated cards in the Bookmarks screen and on the new-tab page. |

### How saved videos are kept safe

You asked that downloads never be the site's actual file. Saving a video works like this:

1. **Download into a locked temporary file.** The video is fetched with your login cookies for that site, but the bytes are encrypted as they arrive, with a key that exists only in memory for that one download. The site's file is never on the phone in readable form.
2. **Check that it's really a video.** Programs, APKs, archives, PDFs, scripts and web pages are rejected from their first bytes. Non-video downloads from pages (any `.apk`, `.exe`, `.zip`…) are blocked outright: Multiview only ever saves videos.
3. **Rebuild a new file.** Android's media parser reads the locked file, and only the encoded picture and sound frames are copied into a brand-new MP4 or WebM file. Everything else in the original is left behind: metadata, extra tracks, attachments, and anything hidden in or appended to the file. If the download isn't a real video, the parser refuses it and nothing is saved.
4. **Check that it plays.** The new file has to open as a playable video before it's kept.
5. **Encrypt into the vault.** The clean file is encrypted with AES-256-GCM, using a key protected by the phone's Android Keystore (secure hardware). All temporary files are deleted. The list of videos and their thumbnails are encrypted too.

Saved videos play inside the app's own player, which decrypts them as they play without writing anything to disk. The vault asks for your fingerprint, face or screen lock (Settings), and is hidden from screenshots and the recent-apps screen. If you ever need a copy outside the app, **Export decrypted copy** saves the clean rebuilt file wherever you choose, after a warning.

Supported: direct video links (MP4, WebM, MOV, M4V) and HLS streams (`.m3u8`, including standard AES-128 encrypted streams, picking the best quality and its separate sound track). Not supported, by design: DRM-protected video, live streams, and sites that stream in split-up pieces without a file or playlist (YouTube, Netflix, Disney+, etc.).

## Install

1. Get `app-release.apk`: open this repository's **Actions** tab, pick the latest **Multiview Browser APK** run, and download the `MultiviewBrowser-apk` artifact (a zip with the APK inside). Or use the copy shared with you.
2. Open the APK on your phone. Android asks you to allow installs from that app (for example Chrome or Files). Allow it, then tap **Install**.
3. Open **Multiview**. To make it your default browser: Android **Settings → Apps → Default apps → Browser app → Multiview**.

Needs Android 8.0 or newer. Casting needs Google Play services; everything else works without it.

## Using it

- **Address bar:** type an address or a search and press Go. The shield on the left shows how many things were blocked on the page; tap it to see details or turn blocking off for that site.
- **Watch several videos:** open the videos in separate tabs and tap the grid button. Multiview starts with the current tab and the others that are playing. To choose tabs yourself, open the tab overview (the number button) and tap **+ Multiview** on up to four tabs. In multiview, tap a tile to control it from the address bar (it gets a blue outline). Its speaker button mutes or unmutes it.
- **Tab groups:** in the tab overview, tap **New group**, name it, pick a colour and tick the tabs to include. Or long-press any tab (in the overview or the group bar), or use **Menu → Add tab to group**. Tap a group's header to collapse it; its **⋯** button has the group actions. The colour of the tab counter shows which group you're in.
- **Full screen:** a video's own full-screen button works in every tile. In multiview, **Menu → Hide browser bars** gives you a clean video wall.
- **Leaving the app:** press Home and everything keeps playing; use the notification to pause or resume. Pressing Back on the last page also leaves without closing anything. **Menu → Exit** closes everything.
- **Save a video:** tap the download button in the address bar, then **Save to vault**. Progress shows in the notification and in **Menu → Downloads vault**.
- **Gallery:** in **Menu → Downloads vault**, tap **Gallery** and pick how many videos to show. Use the controls along the bottom of each video, or drag its ◢ corner to resize it. Tap a video to listen to only that one (tap again to mute it). Turning up a video's volume adds it to what you hear. The sliders button in the top bar shows or hides the per-video controls. ⋮ → **Loop every video** sets looping for all of them. The ⛶ button hides the top bar; tap an empty spot to bring it back.
- **Player:** tap a saved video. Tap the screen to show the controls. For the **A–B loop**, press **A–B** at the start of the part to repeat, then again at its end; a third press turns it off. The **lock** button stops accidental touches; tap the screen, then the lock, to unlock.
- **Cast:** tap the download button and choose **Cast**, or use the cast icon (it appears when a TV is on your Wi-Fi).

## Good to know

- **Sound in several tabs at once:** Android gives sound to one player at a time, so when two tabs both want sound, the newest one gets it and the other keeps playing muted. Multiview's default (sound follows the tile you tap) avoids this.
- **YouTube and other big sites** stream videos in split-up pieces, so there's no file to save or send to a TV. They still play in the background and in multiview, and Mirror screen works for them.
- **Logins:** sites keep you signed in unless you turn on **Clear cookies and site data on exit**.
- **Many videos at once:** every phone has a limit on how many videos it can decode at the same time. The gallery plays the videos you can see most of, plus any you're listening to, live. The rest show a still frame until you zoom in on them. It estimates your phone's limit and lowers it automatically if the phone runs out. **⋮ → Play every video live** tries them all.
- **Battery:** videos playing with the screen off use the battery like any music or video app. Pause them from the notification when you're done.
- This was built and tested off the phone: the Android build, unit tests, and the page script tested in Chromium (the engine Android's WebView uses). It hasn't been tried on a real device yet, so please report anything that misbehaves.

## Build it yourself

You need JDK 17 or newer and the Android SDK (platform 35, build-tools 35).

```sh
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleRelease     # APK: android/app/build/outputs/apk/release/app-release.apk
```

The page-script tests run in headless Chromium:

```sh
cd tests/page-script
npm install && npx playwright install chromium && npm test
```

Every push that changes `multiview-browser/` builds the APK and runs both test suites on GitHub Actions (`.github/workflows/multiview-android.yml`). Builds are signed with the shared sideload key in `android/keystore/` so a newer APK installs over an older one and keeps your data; see that folder's README before publishing anywhere.

## Files

```
android/app/src/main/
  assets/js/inject.js        Script added to every page: background play, media tracking, mute/pause, ad skipping
  assets/css/cosmetic.css    Ad boxes hidden on pages
  assets/filters/            Built-in ad, tracker and malware domain lists
  java/.../browser/          Tabs, tab groups, multiview layout, WebView setup, navigation and blocking
  java/.../security/         Blocklists and their weekly updates
  java/.../media/            Background playback service and notification
  java/.../download/         Video saving: downloader, HLS, clean-file rebuild
  java/.../vault/            Encrypted storage, vault screen, gallery (video wall) and player
  java/.../cast/             Chromecast
  java/.../data/             Bookmarks, sessions and preview capture
  java/.../ui/               Screens: browser, tabs, bookmarks, sessions, settings
android/app/src/test/        Unit tests (blocklists, HLS, file checks, encryption, tab groups, sessions, gallery grid)
tests/page-script/           Chromium tests for inject.js
```

Blocklists downloaded at runtime belong to their authors: StevenBlack hosts (MIT), EasyList and EasyPrivacy (GPLv3 / CC BY-SA 3.0), URLhaus by abuse.ch (CC0), and malware-filter phishing list (CC0/MIT). They're fetched by the app on your phone, not bundled.
