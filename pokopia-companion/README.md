# Pokopia Companion Guide

An unofficial companion web app for **Pokémon Pokopia** (Nintendo Switch 2). Keep it open on your phone or laptop while you play to track your progress and look things up.

## What's inside

| Section | What it does |
| --- | --- |
| **Home** | A restoration map that turns from dry soil to green as you progress, progress bars, a "Next up" list and field tips. |
| **Pokédex** | Mark Pokémon as *Not met*, *Seen* or *Befriended*. Search and filter by specialty, and add Pokémon the guide doesn't list yet. |
| **Habitats** | Habitat recipes drawn as building blocks (e.g. Tall Grass ×4 + Water ×2), with the Pokémon each one attracts. Count how many copies you've built, and add habitats from your own Habitat Dex. |
| **Ditto's moves** | Every move Ditto copies, who teaches it, where you get it, and which meal powers it up. Tick them off as you learn them. |
| **Specialties** | What each specialty (Grow, Burn, Chop, Generate, Hype…) does, with example Pokémon linked to the Pokédex. |
| **Areas** | The story route (Withered Wasteland → Bleak Beach / Rocky Ridges → Sparkling Skylands → post-game), goals to tick off, status and notes for each area. |
| **Cooking & crafting** | Which meal powers up which move, cooking tools, crafting basics and multiplayer notes. |
| **Journal** | A request tracker, free-form notes, and a save code to move your progress between devices. |

Progress is saved in your browser (`localStorage`). Nothing is sent anywhere.

## Run it

It's plain HTML, CSS and JavaScript with no build step and no dependencies.

- Open `index.html` in any browser, **or**
- Serve the folder, for example `python3 -m http.server` from this directory, then visit `http://localhost:8000`, **or**
- Host it on GitHub Pages and use "Add to Home Screen" on your phone. It ships with a web manifest and icon.

## Accuracy

The game data comes from community guides (Serebii, Nintendo Life, Dexerto, GameSpot, Pocket Tactics, Bulbapedia and others) as of November 2026. Where those guides disagree, the app shows a **Check in-game** badge and explains the conflict.

The built-in lists are a starting point, not the full game. The Habitat Dex has 209 entries and the Pokédex has about 300, so the app lets you add Pokémon and habitats as you discover them.

## Editing the data

All reference data lives in [`data.js`](data.js) as plain objects. To correct or extend it, edit or append an entry. For example, a habitat:

```js
{
  id: 'h003', no: 3, name: 'Your Habitat Name',
  req: [['Tall Grass', 4], ['Wildflowers', 2]],
  residents: ['Oddish'],
  cond: 'Only at night'
}
```

Add `verify: true` and a `note` to flag details that still need checking.

## Files

```
index.html            App shell
styles.css            Styles, with light and dark themes
data.js               Game reference data
app.js                App logic: views, tracking, save codes
manifest.webmanifest  Install-to-home-screen metadata
icon.svg              App icon
```

---

Unofficial fan project. Not affiliated with or endorsed by Nintendo, The Pokémon Company or Game Freak. Pokémon and Pokopia are trademarks of their respective owners.
