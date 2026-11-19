"""Draws forum/join_discord_256x80.png, the "Join my Discord" forum signature banner.

It matches all_my_mods_banner_256x80.png and message_on_discord_banner_256x80.png:
256x80, dark starfield with a purple glow, a planet in the bottom-right corner,
a Discord-blue icon on the left, two lines of Bahnschrift Bold in pale blue,
a gold-and-blue underline and bright corner brackets.

Run: python forum/make_join_discord_banner.py   (needs Pillow and numpy, Windows fonts)
Not part of the mod or its release ZIP.
"""
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 256, 80
S = 4  # supersampling for the planet, icon and text
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "join_discord_256x80.png")
FONT = r"C:\Windows\Fonts\bahnschrift.ttf"

TEXT = (214, 243, 254)
OUTLINE = (6, 10, 32)
BLURPLE = (88, 101, 242)
GOLD = (213, 169, 62)
LINE_BLUE = (57, 67, 132)
FRAME_DIM = (32, 66, 100)
FRAME_BRIGHT = (120, 192, 222)
FRAME_MID = (60, 95, 125)


def background():
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    t = np.clip(x / (W - 1), 0, 1)
    t = t * t * (3 - 2 * t)
    left = np.array([12, 17, 48], np.float32)
    right = np.array([3, 5, 12], np.float32)
    img = left * (1 - t[..., None]) + right * t[..., None]
    # bluer haze bottom-left
    d = np.sqrt(((x - 60) / 90) ** 2 + ((y - 78) / 40) ** 2)
    img += np.clip(1 - d, 0, 1)[..., None] * np.array([8, 10, 26], np.float32)
    # purple glow top-middle
    d = np.sqrt(((x - 145) / 70) ** 2 + ((y - 0) / 32) ** 2)
    img += np.clip(1 - d, 0, 1)[..., None] ** 1.3 * np.array([34, 9, 30], np.float32)
    bg = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")

    rnd = random.Random(36175)
    px = bg.load()
    for _ in range(270):
        sx, sy = rnd.randrange(2, W - 2), rnd.randrange(2, H - 2)
        b = rnd.choice([80, 100, 120, 140, 170, 200, 230, 250])
        tint = rnd.choice([(1.0, 1.0, 1.0), (0.85, 0.92, 1.0), (1.0, 0.95, 0.85)])
        c = tuple(int(b * k) for k in tint)
        old = px[sx, sy]
        px[sx, sy] = tuple(max(o, n) for o, n in zip(old, c))
        if b >= 200 and rnd.random() < 0.4:  # a few soft larger stars
            for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                o = px[sx + ox, sy + oy]
                px[sx + ox, sy + oy] = tuple(max(a, int(n * 0.35)) for a, n in zip(o, c))
    return bg


def planet(layer):
    """Banded amber gas giant, lit from the upper left, cut off by the corner."""
    cx, cy, r = 263 * S, 103 * S, 75 * S
    y, x = np.mgrid[0:H * S, 0:W * S].astype(np.float32)
    nx, ny = (x - cx) / r, (y - cy) / r
    rr = nx * nx + ny * ny
    inside = rr <= 1.0
    nz = np.sqrt(np.clip(1 - rr, 0, 1))
    light = np.array([-0.62, -0.55, 0.56], np.float32)
    light /= np.linalg.norm(light)
    lam = np.clip(nx * light[0] + ny * light[1] + nz * light[2], 0, 1)
    shade = 0.16 + 0.95 * lam ** 0.85
    # bands follow latitude, tilted a little
    lat = ny * 0.96 + nx * 0.28
    bands = (0.82 + 0.10 * np.sin(lat * 23.0) + 0.06 * np.sin(lat * 61.0 + 1.3)
             + 0.04 * np.sin(lat * 9.0 + nx * 2.0))
    base = np.array([196, 132, 76], np.float32)
    col = base * (shade * bands)[..., None]
    # thin lit rim on the sunny side
    rim = np.clip((rr - 0.86) / 0.14, 0, 1) * lam
    col += rim[..., None] * np.array([70, 55, 35], np.float32)
    alpha = np.clip((1.0 - np.sqrt(rr)) * r / 1.5, 0, 1) * inside  # soft edge
    rgba = np.zeros((H * S, W * S, 4), np.float32)
    rgba[..., :3] = np.clip(col, 0, 255)
    rgba[..., 3] = alpha * 255
    p = Image.fromarray(rgba.astype(np.uint8), "RGBA")
    # faint atmosphere glow around the planet
    glow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((cx - r - 3 * S, cy - r - 3 * S, cx + r + 3 * S, cy + r + 3 * S),
                                 fill=(230, 160, 90, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(4 * S))
    layer.alpha_composite(glow)
    layer.alpha_composite(p)


def icon(layer):
    """Discord-blue rounded square with two people: a community, not Discord's logo."""
    d = ImageDraw.Draw(layer)
    x0, y0, size = 10 * S, 17 * S, 40 * S
    d.rounded_rectangle((x0, y0, x0 + size, y0 + size), radius=9 * S, fill=BLURPLE + (255,))

    def u(v):
        return v * S

    people = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    pd = ImageDraw.Draw(people)
    back = (196, 203, 255, 255)
    # back person, up and to the right
    pd.ellipse((x0 + u(21), y0 + u(8), x0 + u(31), y0 + u(18)), fill=back)
    pd.pieslice((x0 + u(16.5), y0 + u(20), x0 + u(35.5), y0 + u(42)), 180, 360, fill=back)
    # front person with a blue gap around it
    fh = (x0 + u(10), y0 + u(10), x0 + u(22), y0 + u(22))
    fb = (x0 + u(5), y0 + u(23.5), x0 + u(27), y0 + u(47))
    gap = u(1.6)
    pd.ellipse((fh[0] - gap, fh[1] - gap, fh[2] + gap, fh[3] + gap), fill=BLURPLE + (255,))
    pd.pieslice((fb[0] - gap, fb[1] - gap, fb[2] + gap, fb[3] + gap), 180, 360, fill=BLURPLE + (255,))
    pd.ellipse(fh, fill=(255, 255, 255, 255))
    pd.pieslice(fb, 180, 360, fill=(255, 255, 255, 255))
    # keep the people inside the square, with a flat base
    mask = Image.new("L", layer.size, 0)
    ImageDraw.Draw(mask).rectangle((x0 + u(3), y0 + u(3), x0 + size - u(3), y0 + u(34)), fill=255)
    people.putalpha(Image.composite(people.getchannel("A"), Image.new("L", layer.size, 0), mask))
    layer.alpha_composite(people)


def text(layer):
    font = ImageFont.truetype(FONT, 27.5 * S)
    font.set_variation_by_name("Bold")
    lines = [("JOIN MY", 14), ("DISCORD", 40)]  # cap top in banner pixels, as on the other banners
    shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    td = ImageDraw.Draw(layer)
    for s, top in lines:
        bx0, by0, _, _ = font.getbbox(s)
        pos = (59 * S - bx0, top * S - by0)
        sd.text((pos[0] + 2 * S, pos[1] + 2 * S), s, font=font, fill=(0, 0, 0, 170),
                stroke_width=int(1.5 * S), stroke_fill=(0, 0, 0, 170))
        td.text(pos, s, font=font, fill=TEXT + (255,), stroke_width=int(1.1 * S),
                stroke_fill=OUTLINE + (255,))
    shadow = shadow.filter(ImageFilter.GaussianBlur(1.5 * S))
    out = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    out.alpha_composite(shadow)
    out.alpha_composite(layer)
    layer.paste(out)


def underline(img):
    d = ImageDraw.Draw(img)
    d.rectangle((57, 65, 71, 66), fill=GOLD)
    for x in range(72, 229):
        fade = 1.0 if x < 200 else max(0.0, 1 - (x - 200) / 29)
        under = img.getpixel((x, 66))
        c = tuple(int(u * (1 - fade) + l * fade) for u, l in zip(under, LINE_BLUE))
        img.putpixel((x, 66), c)


def frame(img):
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W - 1, H - 1), outline=FRAME_DIM)
    n = 10
    for (cx, cy, sx, sy) in ((0, 0, 1, 1), (W - 1, 0, -1, 1), (0, H - 1, 1, -1), (W - 1, H - 1, -1, -1)):
        for i in range(n):
            img.putpixel((cx + sx * i, cy), FRAME_BRIGHT)
            img.putpixel((cx, cy + sy * i), FRAME_BRIGHT)
        for i in range(1, n - 1):
            img.putpixel((cx + sx * i, cy + sy), FRAME_MID)
            img.putpixel((cx + sx, cy + sy * i), FRAME_MID)


def main():
    bg = background().convert("RGBA")
    big = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    planet(big)
    icon(big)
    txt = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    text(txt)
    big.alpha_composite(txt)
    small = big.resize((W, H), Image.LANCZOS)
    bg.alpha_composite(small)
    img = bg.convert("RGB")
    underline(img)
    frame(img)
    img.save(OUT, optimize=True)
    print("wrote", OUT, img.size)


if __name__ == "__main__":
    main()
