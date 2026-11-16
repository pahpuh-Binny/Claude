#!/usr/bin/env python3
"""Build the optional microSD card pack for the CYD firmware.

Usage (from pokopia-companion/):
    python3 tools/make_sd_card.py OUTPUT_FOLDER

Writes OUTPUT_FOLDER/pokopia/sprites/NNNN.bin (large) and NNNNs.bin (small)
for all 1,025 Pokémon, in LVGL's image format. Copy the `pokopia` folder to
the root of a FAT32 microSD card and the device shows every Pokémon's
picture in "All" mode. Without the card it still shows the guide's own
Pokémon, which are built into the firmware.

Needs Pillow and Node.js.
"""
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from PIL import Image  # noqa: E402

import lvgl_image  # noqa: E402
from gen_cyd_assets import SPRITE_L, SPRITE_S  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1]) / "pokopia" / "sprites"
    out.mkdir(parents=True, exist_ok=True)
    species = json.loads(subprocess.run(
        ["node", "-e", "global.window={};require(process.argv[1]);"
         "process.stdout.write(JSON.stringify(window.POKOPIA_SPECIES))", str(WEB / "species.js")],
        check=True, capture_output=True, text=True).stdout)
    total = 0
    for dex, (name, slug) in enumerate(species, start=1):
        base = Image.open(WEB / "sprites" / f"{slug}.webp")
        for suffix, box in (("", SPRITE_L), ("s", SPRITE_S)):
            data = lvgl_image.to_bin(lvgl_image.fit(base, *box))
            (out / f"{dex:04d}{suffix}.bin").write_bytes(data)
            total += len(data)
    (out.parent / "README.txt").write_text(
        "Pokopia Companion SD card pack.\n"
        "Keep this 'pokopia' folder at the root of the card.\n"
        "sprites/: pictures of all 1,025 Pokemon (LVGL image format).\n"
        "save.json: your progress, written by Settings > Save to SD card.\n"
        "Pokemon images (c) Nintendo, Creatures Inc. and GAME FREAK inc.\n",
        encoding="utf-8")
    print(f"Wrote {len(species) * 2} sprite files ({total / 1024 / 1024:.1f} MB) to {out}")


if __name__ == "__main__":
    main()
