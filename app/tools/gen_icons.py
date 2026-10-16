#!/usr/bin/env python3
"""Generate app icons (legacy PNGs, adaptive foreground PNG, splash icon)."""
import os
from PIL import Image, ImageDraw

import os
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "src", "main", "res")

TOP = (0x4a, 0xde, 0xa0)
BOT = (0x0b, 0x6e, 0x4f)
GLYPH = (255, 255, 255)

def vgrad(size, c1, c2):
    w, h = size
    top = Image.new("RGB", (w, h), c1)
    bot = Image.new("RGB", (w, h), c2)
    ramp = Image.linear_gradient("L").resize((1, h), Image.BILINEAR).resize((w, h), Image.BILINEAR)
    return Image.composite(bot, top, ramp)

def glyph_mask(canvas, gv, gh):
    """Solid white glyph 'P' on transparent canvas, drawn at scale (gv, gh) of canvas size."""
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    gw = int(canvas * gv)
    ghpx = int(canvas * gh)
    x0 = (canvas - gw) // 2
    y0 = (canvas - ghpx) // 2
    stem_w = int(gw * 0.42)
    R = gw - stem_w  # bowl radius
    stem = [x0, y0, x0 + stem_w, y0 + ghpx]
    d.rounded_rectangle(stem, radius=stem_w // 2, fill=(255, 255, 255, 255))
    bowl = [x0 + stem_w, y0, x0 + stem_w + 2 * R, y0 + 2 * R]
    d.pieslice(bowl, 270, 90, fill=(255, 255, 255, 255))
    # smooth the bowl's inner corner against the stem (fill gap)
    d.rectangle([x0 + stem_w - 1, y0, x0 + stem_w + 2, y0 + R], fill=(255, 255, 255, 255))
    return img

def render_legacy(size, corner_ratio=0.24, ss=4):
    big = size * ss
    base = vgrad((big, big), TOP, BOT)
    mask = Image.new("L", (big, big), 0)
    dm = ImageDraw.Draw(mask)
    r = int(big * corner_ratio)
    dm.rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=255)
    bg = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    bg.paste(base, (0, 0), mask)
    g = glyph_mask(big, 0.50, 0.66)
    bg.alpha_composite(g)
    return bg.resize((size, size), Image.LANCZOS)

def render_foreground(size, ss=4):
    big = size * ss
    g = glyph_mask(big, 0.46, 0.62)
    return g.resize((size, size), Image.LANCZOS)

# legacy mipmaps
legacy_dpi = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
              "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
for folder, size in legacy_dpi.items():
    path = os.path.join(RES, folder, "ic_launcher.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    render_legacy(size).save(path, "PNG")
    print("wrote", path)

# splash app_icon (192px legacy-style, self-contained on gradient splash)
path = os.path.join(RES, "drawable", "app_icon.png")
os.makedirs(os.path.dirname(path), exist_ok=True)
render_legacy(192).save(path, "PNG")
print("wrote", path)

# adaptive foreground (432px canvas grid ~ xxxhdpi reference)
path = os.path.join(RES, "drawable", "ic_launcher_fg.png")
render_foreground(432).save(path, "PNG")
print("wrote", path)