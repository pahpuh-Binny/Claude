# Pokopia Companion Guide

An unofficial companion app for **Pokémon Pokopia** (Nintendo Switch 2). It runs as an Android app or in any web browser. Keep it open on your phone while you play to track your progress and look things up.

## What's inside

| Section | What it does |
| --- | --- |
| **Home** | A restoration map that turns from dry soil to green as you progress, your befriended Pokémon, progress bars, a "Next up" list and field tips. |
| **Pokédex** | Every Pokémon with its picture. Mark each one *Not met*, *Seen* or *Befriended*, search, and filter by specialty. Add any of the 1,025 species by name and its picture and Pokédex number fill in automatically. |
| **Habitats** | Habitat recipes drawn as building blocks (e.g. Tall Grass ×4 + Water ×2), the Pokémon each one attracts, and a counter for copies you've built. Add habitats from your own Habitat Dex. |
| **Ditto's moves** | Every move Ditto copies, the Pokémon that teaches it, where, and which meal powers it up. |
| **Specialties** | What each specialty (Grow, Burn, Chop, Generate, Hype…) does, with example Pokémon. |
| **Areas** | The story route (Withered Wasteland → Bleak Beach / Rocky Ridges → Sparkling Skylands → post-game), goals to tick off, status and notes for each area. |
| **Cooking & crafting** | Which meal powers up which move, cooking tools, crafting basics and multiplayer notes. |
| **Journal** | A request tracker, notes, and a save code to back up or move your progress. |

## Install on Android

1. Get `app-release.apk`. Either use the copy shared with you, or open this repository's **Actions** tab, pick the latest **Pokopia Companion APK** run, and download the `PokopiaCompanion-apk` artifact (it's a zip with the APK inside).
2. Open the APK on your phone. Android asks you to allow installs from that app (for example Chrome or Files). Allow it, then tap **Install**.
3. Open **Pokopia Companion** from your app drawer.

The app needs Android 8.0 or newer and works fully offline. Everything, including all 1,025 Pokémon pictures and the fonts, is inside the app.

**Your progress** is stored inside the app. Installing a newer APK over the old one keeps it, because every build is signed with the same key (see `android/keystore/`). Uninstalling the app deletes it, so use **Journal → Share save code** to back it up first. The share button can send the code to Google Drive, Keep or a message to yourself. Paste it into **Load save code** to restore.

## Use it in a browser

Open `web/index.html`, or serve the `web/` folder (`python3 -m http.server` inside it, then visit `http://localhost:8000`). On a phone you can host it on GitHub Pages and use **Add to Home Screen**.

## Build the APK yourself

You need JDK 17 or newer and the Android SDK (platform 35 and build-tools 35).

```sh
cd android
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/app-release.apk
```

The Android app is a thin native shell (`android/app/src/main/java/.../MainActivity.java`) around the web app. At build time Gradle copies `web/` into the APK, so `web/` is the only place to edit the app's content and design. Every push that changes this folder also builds the APK on GitHub Actions (`.github/workflows/pokopia-android.yml`).

## Pokémon pictures

`tools/fetch_sprites.py` downloads the Pokémon HOME renders for all 1,025 species from [Pokémon Database](https://pokemondb.net/sprites). It trims and shrinks them to small WebP files in `web/sprites/` and writes the name list `web/species.js`. Re-run it when new Pokémon are added:

```sh
python3 tools/fetch_sprites.py web/sprites web/species.js
```

Pokopia's own characters (Peakychu, Mosslax, Professor Tangrowth, Smearguru) don't have official sprites yet, so they show their base species with a "Shown as…" label.

## Accuracy

The game data comes from community guides (Serebii, Nintendo Life, Dexerto, GameSpot, Pocket Tactics, Bulbapedia and others) as of November 2026. Where those guides disagree, the app shows a **Check in-game** badge and explains the conflict. The built-in lists are a starting point: the Habitat Dex has 209 entries and the Pokédex about 300, so the app lets you add what you discover.

All reference data lives in [`web/data.js`](web/data.js) as plain objects. To fix or extend it, edit or append an entry, for example:

```js
{
  id: 'h003', no: 3, name: 'Your Habitat Name',
  req: [['Tall Grass', 4], ['Wildflowers', 2]],
  residents: ['Oddish'],
  cond: 'Only at night'
}
```

## Files

```
web/                     The app (HTML, CSS, JS), shared by the website and the Android app
  data.js                Game reference data
  species.js             All 1,025 Pokémon names (generated)
  sprites/               Pokémon pictures (generated)
  fonts/                 Bundled fonts (SIL Open Font License)
android/                 Android Studio / Gradle project that wraps web/
tools/fetch_sprites.py   Downloads sprites and builds species.js
tools/fetch_fonts.py     Downloads the fonts
```

## Credits and legal

Unofficial fan project for personal use. Not affiliated with or endorsed by Nintendo, The Pokémon Company or Game Freak. Pokémon, Pokopia and all Pokémon images are trademarks and © of Nintendo, Creatures Inc. and GAME FREAK inc. The sprites are bundled for personal, non-commercial use. Publishing the app on Google Play or another store with these images would likely get it taken down.

Fonts: Atkinson Hyperlegible, Baloo 2 and DM Mono, under the SIL Open Font License 1.1 (`web/fonts/OFL.txt`).
