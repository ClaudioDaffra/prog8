#!/usr/bin/env python

from PIL import Image

def make_cx16_palette(palette: list[int]) -> bytes:
    cx16palette = bytearray()
    for pi in range(0, len(palette), 3):
        r, g, b = palette[pi] >> 4, palette[pi + 1] >> 4, palette[pi + 2] >> 4
        cx16palette.append(g << 4 | b)
        cx16palette.append(r)
    return cx16palette


img = Image.open("dragonsprite.png")
assert img.size == (64, 128) and img.mode == 'P'

data = bytearray()
for y in range(0, 128):
    for x in range(0, 64, 2):
        pix1 = img.getpixel((x, y))
        pix2 = img.getpixel((x + 1, y))
        data.append(pix1 << 4 | pix2)

palette = make_cx16_palette(img.getpalette())

assert len(data) == 64 * 128 / 2
assert len(palette) <= 16*2
open("DRAGONSPRITE.PAL", "wb").write(palette)
open("DRAGONSPRITE.BIN", "wb").write(data)
