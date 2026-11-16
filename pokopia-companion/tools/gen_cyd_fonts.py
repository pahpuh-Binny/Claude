#!/usr/bin/env python3
"""Build the CYD firmware's LVGL fonts from the web app's fonts.

Usage (from pokopia-companion/, after `pio pkg install` in cyd/):
    python3 tools/gen_cyd_fonts.py

Converts web/fonts/*.woff2 to TTF (fontTools), makes a bold instance of
Baloo 2, and runs lv_font_conv (via npx) to write cyd/src/gen/font_*.c.
♀ and ♂ (for Nidoran) come from DejaVu Sans and the UI symbols from
Font Awesome, both shipped with LVGL in scripts/built_in_font/.

Needs: Node.js (npx), fontTools and brotli (pip install fonttools brotli).
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = Path(__file__).resolve().parent.parent
WEB_FONTS = ROOT / "web" / "fonts"
OUT = ROOT / "cyd" / "src" / "gen"
LVGL_FONTS = ROOT / "cyd" / ".pio" / "libdeps" / "cyd" / "lvgl" / "scripts" / "built_in_font"
LV_FONT_CONV = os.environ.get("LV_FONT_CONV", "npx --yes lv_font_conv@1.5.3").split()

TEXT = "0x20-0x7E,0xA0-0xFF,0x2013-0x2014,0x2018-0x2019,0x201C-0x201D,0x2022,0x2026,0x2039-0x203A"
GENDER = "0x2640,0x2642"
# Font Awesome code points behind LV_SYMBOL_* used by the UI.
SYMBOLS = ",".join([
    "0xF00C",  # OK
    "0xF00D",  # CLOSE
    "0xF013",  # SETTINGS
    "0xF015",  # HOME
    "0xF019",  # DOWNLOAD
    "0xF021",  # REFRESH
    "0xF053",  # LEFT
    "0xF054",  # RIGHT
    "0xF067",  # PLUS
    "0xF068",  # MINUS
    "0xF093",  # UPLOAD
    "0xF0C7",  # SAVE
    "0xF2ED",  # TRASH
    "0xF7C2",  # SD_CARD
])

# name, ttf, size, extra (font, range) pairs
FONTS = [
    ("font_body_14", "AtkinsonHyperlegible-400", 14, [("dejavu", GENDER), ("fa", SYMBOLS)]),
    ("font_bold_14", "AtkinsonHyperlegible-700", 14, [("dejavu", GENDER), ("fa", SYMBOLS)]),
    ("font_small_12", "AtkinsonHyperlegible-400", 12, [("dejavu", GENDER), ("fa", SYMBOLS)]),
    ("font_title_20", "Baloo2-700", 20, [("dejavu", GENDER)]),
    ("font_hero_26", "Baloo2-700", 26, []),
    ("font_mono_12", "DMMono-500", 12, []),
]


def make_ttfs(tmp: Path) -> dict:
    paths = {}
    for name in ["AtkinsonHyperlegible-400", "AtkinsonHyperlegible-700", "Baloo2-500", "DMMono-500"]:
        font = TTFont(WEB_FONTS / f"{name}.woff2")
        font.flavor = None
        if "fvar" in font:  # Baloo 2 is a variable font: pin it to Bold.
            font = instancer.instantiateVariableFont(font, {"wght": 700})
            name = "Baloo2-700"
        path = tmp / f"{name}.ttf"
        font.save(path)
        paths[name] = path
    paths["dejavu"] = LVGL_FONTS / "DejaVuSans.ttf"
    paths["fa"] = LVGL_FONTS / "FontAwesome5-Solid+Brands+Regular.woff"
    return paths


def main():
    if not LVGL_FONTS.exists():
        sys.exit(f"{LVGL_FONTS} not found. Run `pio pkg install` in cyd/ first.")
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        ttf = make_ttfs(Path(tmp))
        for name, base, size, extras in FONTS:
            cmd = LV_FONT_CONV + [
                "--bpp", "4", "--size", str(size), "--no-compress", "--format", "lvgl",
                "--lv-include", "lvgl.h", "--lv-font-name", name, "-o", str(OUT / f"{name}.c"),
                "--font", str(ttf[base]), "-r", TEXT,
            ]
            for key, rng in extras:
                cmd += ["--font", str(ttf[key]), "-r", rng]
            subprocess.run(cmd, check=True)
            # Keep machine-specific paths out of the generated header comment.
            out_file = OUT / f"{name}.c"
            text = out_file.read_text(encoding="utf-8")
            for prefix in (str(OUT) + "/", str(tmp) + "/", str(LVGL_FONTS) + "/"):
                text = text.replace(prefix, "")
            out_file.write_text(text, encoding="utf-8")
            print(f"{name}.c  {(OUT / f'{name}.c').stat().st_size // 1024} KB source")


if __name__ == "__main__":
    main()
