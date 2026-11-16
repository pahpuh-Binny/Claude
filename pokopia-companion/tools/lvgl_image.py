"""Convert images to LVGL 9 RGB565A8 image data (shared by the CYD tools).

RGB565A8 layout: all pixels as little-endian RGB565 (w*h*2 bytes), followed by
an 8-bit alpha plane (w*h bytes). A .bin file for LVGL's file decoder is the
12-byte lv_image_header_t followed by that data.
"""
import struct

from PIL import Image

LV_IMAGE_HEADER_MAGIC = 0x19
LV_COLOR_FORMAT_RGB565A8 = 0x14


def fit(img: Image.Image, max_w: int, max_h: int) -> Image.Image:
    """Scale an RGBA image to fit inside max_w x max_h, keeping its aspect."""
    img = img.convert("RGBA")
    scale = min(max_w / img.width, max_h / img.height)
    size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
    return img.resize(size, Image.LANCZOS)


def rgb565a8(img: Image.Image) -> bytes:
    """Return RGB565A8 pixel data for an RGBA image."""
    img = img.convert("RGBA")
    colors = bytearray()
    alpha = bytearray()
    for r, g, b, a in img.getdata():
        if a == 0:
            r = g = b = 0
        value = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3)
        colors += struct.pack("<H", value)
        alpha.append(a)
    return bytes(colors + alpha)


def header(w: int, h: int) -> bytes:
    """lv_image_header_t for an RGB565A8 image (bitfields packed LSB first)."""
    word0 = LV_IMAGE_HEADER_MAGIC | (LV_COLOR_FORMAT_RGB565A8 << 8)  # flags = 0
    word1 = w | (h << 16)
    word2 = (w * 2)  # stride of the RGB565 plane; reserved = 0
    return struct.pack("<III", word0, word1, word2)


def to_bin(img: Image.Image) -> bytes:
    return header(img.width, img.height) + rgb565a8(img)
