#!/usr/bin/env python3
"""Anki Dungeon title logo generator.

Rebuilds the portrait (139x100 @ 0,0) and landscape (240x57 @ 0,100) title
logos in interfaces/banners.png plus their glow ("signs") layers at
(139,0) and (240,100).  Coordinates are those used by BannerSprites.java and
are left unchanged.

Every letter is cut from SPD's original logo so the bevel, gradient, moss and
cracks are native:
  A  <- 'A' of SHATTERED
  N  <- small 'N' of DUNGEON
  K  <- stem of PIXEL's 'I' + right half of PIXEL's 'X'
  I  <- 'I' of PIXEL
  DUNGEON line is kept untouched.
The glow layer pixels travel with their letters.  The soft drop shadow around
the new letters is regenerated with a model fitted to the original halo
(mask shifted 1px down, gaussian r=1.0, x1.5 -> mean error ~7/255).
A small stone flashcard emblem with a glowing hand-pixelled あ sits on top.

Usage: python3 logo.py --src ORIGINAL_banners.png [--out banners.png] [--preview DIR]
The original SPD banners.png is kept in tools/anki_art/spd_banners_orig.png so
the script stays reproducible after the repo file is replaced.
"""
import argparse
import os

import numpy as np
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))

PORT = (0, 0, 139, 100)
PORT_GLOW = (139, 0, 278, 100)
LAND = (0, 100, 240, 157)
LAND_GLOW = (240, 100, 480, 157)


# ------------------------------------------------------------------ helpers
def opaque_cut(img, glow, x0, y0, x1, y1, keep=lambda x, y: True):
    """Return {(dx,dy): (rgba, glow_rgba)} of fully opaque pixels in the box."""
    out = {}
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            p = img.getpixel((x, y))
            if p[3] == 255 and keep(x, y):
                out[(x - x0, y - y0)] = (p, glow.getpixel((x, y)))
    return out


def width(letter):
    return max(x for x, _ in letter) + 1


def letters(port, pglow):
    """Cut the source letters out of the original portrait logo."""
    L = {}
    # 'A' of SHATTERED: rows 15-32, x 32-51; the T's bar starts at x48 on top rows
    L['A'] = opaque_cut(port, pglow, 32, 15, 51, 32, lambda x, y: y >= 22 or x <= 47)
    # small 'N' of DUNGEON: x 44-62, rows 63-80 (U's right stem ends at x43)
    L['N'] = opaque_cut(port, pglow, 44, 63, 62, 80)
    # 'I' of PIXEL: x 50-57 rows 39-56
    L['I'] = opaque_cut(port, pglow, 50, 39, 57, 56)
    # 'K' = I stem + right half of X (x >= 67, rows 39-56; E starts at x78/79)
    k = dict(L['I'])
    xr = opaque_cut(port, pglow, 67, 39, 77, 56)
    off = 10  # X half starts right after the stem (stem body ends at dx 6)
    for (x, y), v in xr.items():
        k[(x + off, y)] = v
    # weld: where the arms meet the stem, turn the stem's dark right edge
    # (dx 6) into the stem's interior colour (dx 4) on those rows
    for y in range(18):
        if (off, y) in k and (6, y) in k:
            k[(6, y)] = (k[(4, y)][0], k[(6, y)][1])
            if (7, y) not in k:
                k[(7, y)] = (k[(4, y)][0], (0, 0, 0, 0))
            for x in range(7, off):
                if (x, y) not in k:
                    k[(x, y)] = (k[(4, y)][0], (0, 0, 0, 0))
    L['K'] = k
    return L


def shadow(mask_alpha):
    """Original-logo style soft shadow from a binary mask (uint8 0/255)."""
    h, w = mask_alpha.shape
    m = Image.new('L', (w + 20, h + 20), 0)
    m.paste(Image.fromarray(mask_alpha), (10, 11))  # shifted 1px down
    bl = np.array(m.filter(ImageFilter.GaussianBlur(1.0))).astype(float)[10:10 + h, 10:10 + w]
    return np.clip(bl * 1.5, 0, 255).astype(np.uint8)


def place(canvas, glow, letter, x0, y0):
    for (x, y), (p, g) in letter.items():
        canvas.putpixel((x0 + x, y0 + y), p)
        if g[3]:
            glow.putpixel((x0 + x, y0 + y), g)


# ------------------------------------------------------------------ emblem
# Hand-pixelled hiragana A with 2px strokes (18x18), not rasterised from a font.
GLYPH18 = [
    ".......##.........",
    ".......##.........",
    ".......##.........",
    ".#############....",
    ".#############....",
    ".......##..##.....",
    ".......##..##.....",
    ".......##.##......",
    ".....########.....",
    "....##.####.###...",
    "...##..###....##..",
    "..##...##......##.",
    ".##...###......##.",
    ".##..####......##.",
    ".##.##.........##.",
    "..###.........##..",
    "............###...",
    "........#####.....",
]

# colours taken from the original logo letters
HILITE = (212, 219, 200)
HILITE_END = (133, 144, 113)
FILL_TOP, FILL_BOT = (132, 142, 111), (83, 91, 66)
EDGE_TOP, EDGE_BOT = (58, 66, 48), (33, 38, 23)
LAST_ROW = (88, 95, 70)
BOTTOM = (15, 18, 8)
GROOVE = (30, 33, 21)
LIP = (106, 113, 91)
MOSS = [(103, 151, 96), (95, 139, 88), (73, 119, 68), (69, 113, 63)]
GLOW_CORE = (48, 147, 65, 255)
GLOW_HALO = (48, 147, 65, 51)
GLOW_HI = (105, 187, 119, 255)

# 1px-stroke 13x12 version (same design as the launcher icon) for the
# small landscape emblem
GLYPH13 = [
    ".....#.......",
    ".#########...",
    ".....#.......",
    ".....#.......",
    ".....#..#....",
    "....######...",
    "...#.##...#..",
    "..#..#.....#.",
    ".#..##.....#.",
    ".#.#.#.....#.",
    ".#.#......#..",
    "..#.....##...",
]

BIG = dict(w=26, h=30, ear=5, glyph=GLYPH18, off=(4, 7),
           crack=[(1, 4), (2, 4), (3, 5), (4, 5), (5, 6)])
SMALL = dict(w=19, h=20, ear=4, glyph=GLYPH13, off=(3, 5), crack=[])


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def emblem(spec):
    """Stone flashcard (letter-style bevel) with an engraved, glowing あ.

    Returns (pixels {(x,y): rgb}, glow {(x,y): rgba}) relative to the card's
    top-left corner.
    """
    w, h, f = spec['w'], spec['h'], spec['ear']
    px = {}
    for y in range(h):
        t = max(0.0, min(1.0, (y - 1) / (h - 4)))
        fill, edge = lerp(FILL_TOP, FILL_BOT, t), lerp(EDGE_TOP, EDGE_BOT, t)
        for x in range(w):
            r = w - 1 - x
            if r + y < f:          # dog-ear cut
                continue
            if (x, y) in [(0, h - 1), (w - 1, h - 1)]:
                continue
            if y == h - 1:
                c = BOTTOM
            elif y == 0 or r + y == f:
                c = HILITE           # top edge and the fold line catch the light
            elif y == h - 2:
                c = LAST_ROW if 0 < x < w - 1 else edge
            elif x == 0 or x == w - 1:
                c = edge
            else:
                c = fill
            px[(x, y)] = c
    # the folded flap: darker triangle with an edge-coloured border
    for y in range(1, f + 1):
        for r in range(0, f + 1):
            if r + y <= f:
                continue
            x = w - 1 - r
            t = (y - 1) / (h - 4)
            px[(x, y)] = lerp(EDGE_TOP, EDGE_BOT, t) if (r == f or y == f) else lerp(FILL_TOP, FILL_BOT, 0.55)
    px[(w - 1 - f, 0)] = HILITE_END
    # engraved glyph: groove + lit lower lip (same treatment as the cracks)
    gx, gy = spec['off']
    glyph = {(gx + x, gy + y) for y, row in enumerate(spec['glyph']) for x, ch in enumerate(row) if ch == '#'}
    # a hairline crack like the ones running through the letters
    for (x, y) in spec['crack']:
        px[(x, y)] = GROOVE
        if (x, y + 1) not in spec['crack']:
            px[(x, y + 1)] = LIP
    for (x, y) in glyph:
        px[(x, y)] = GROOVE
    for (x, y) in glyph:
        if (x, y + 1) not in glyph and (x, y + 1) in px:
            px[(x, y + 1)] = LIP
    # a little moss creeping up the bottom-left corner
    for (x, y), c in {(1, h - 3): 0, (2, h - 3): 2, (1, h - 4): 1, (1, h - 5): 3, (3, h - 2): 2,
                      (2, h - 2): 0, (w - 3, h - 3): 3, (w - 2, h - 4): 2}.items():
        px[(x, y)] = MOSS[c]
    # glow: the glyph itself burns green, with the 1px soft halo the
    # original runes use; a few brighter pixels on top-left stroke ends
    glow = {}
    for (x, y) in glyph:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                p = (x + dx, y + dy)
                if p not in glyph and p in px:
                    glow[p] = GLOW_HALO
    for p in glyph:
        glow[p] = GLOW_CORE
    for (x, y) in glyph:
        if (x - 1, y) not in glyph and (x, y - 1) not in glyph:
            glow[(x, y)] = GLOW_HI
    return px, glow


# ------------------------------------------------------------------ build
def build_port(src):
    port = src.crop(PORT)
    pglow = src.crop(PORT_GLOW)
    L = letters(port, pglow)
    W, H = port.size

    keep_from = 61  # rows >= this come from the original (DUNGEON line)
    new = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    nglow = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    gap = 1
    word = ['A', 'N', 'K', 'I']
    total = sum(width(L[c]) for c in word) + gap * (len(word) - 1)
    x = (W - total) // 2
    y = LAYOUT['anki_y']
    for c in word:
        place(new, nglow, L[c], x, y)
        x += width(L[c]) + gap

    # emblem
    ex = (W - BIG['w']) // 2
    ey = LAYOUT['card_y']
    epx, eglow = emblem(BIG)
    for (x, y), c in epx.items():
        new.putpixel((ex + x, ey + y), c + (255,))
    for (x, y), c in eglow.items():
        nglow.putpixel((ex + x, ey + y), c)
    # keep the original floating rubble in the top corners
    for yy in range(0, 11):
        for xx in list(range(0, 22)) + list(range(112, W)):
            p = port.getpixel((xx, yy))
            if p[3] == 255:
                new.putpixel((xx, yy), p)

    # combine: original bottom part (opaque + its own halo), regenerated halo on top
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    glow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    a_new = np.array(new)[:, :, 3]
    orig = np.array(port)
    a_orig_op = np.where(orig[:, :, 3] == 255, 255, 0).astype(np.uint8)
    a_orig_op[:keep_from] = 0
    sh = shadow(np.maximum(a_new, a_orig_op))
    arr = np.zeros((H, W, 4), np.uint8)
    arr[:, :, 3] = sh
    # bottom rows: original pixels verbatim (incl. its shadow and rubble)
    arr[keep_from:] = orig[keep_from:]
    out = Image.fromarray(arr)
    out.alpha_composite(new)
    gl = np.array(pglow)
    garr = np.array(nglow)
    garr[keep_from:] = gl[keep_from:]
    glow = Image.fromarray(garr)
    return out, glow


LAYOUT = dict(anki_y=39, card_y=3)


def build_land(src):
    """Landscape: [card] ANKI on line 1, DUNGEON (moved over from the portrait
    logo, it has the big D and N plus underline) on line 2."""
    land = src.crop(LAND)
    port = src.crop(PORT)
    pglow = src.crop(PORT_GLOW)
    L = letters(port, pglow)
    W, H = land.size
    new = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    nglow = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    # line 2: DUNGEON block from the portrait logo (opaque rows 61-86)
    dx, dy = 50, 24 - 61
    for yy in range(61, 87):
        for xx in range(port.width):
            p = port.getpixel((xx, yy))
            if p[3] == 255:
                new.putpixel((xx + dx, yy + dy), p)
    for yy in range(58, 91):
        for xx in range(port.width):
            g = pglow.getpixel((xx, yy))
            if g[3] and 0 <= yy + dy < H:
                nglow.putpixel((xx + dx, yy + dy), g)

    # line 1: ANKI centred, small card emblem hanging to its left
    gap = 1
    word = ['A', 'N', 'K', 'I']
    total = sum(width(L[c]) for c in word) + gap * (len(word) - 1)
    x = (W - total) // 2
    x_word = x
    for c in word:
        place(new, nglow, L[c], x, LAYOUT_L['anki_y'])
        x += width(L[c]) + gap
    epx, eglow = emblem(SMALL)
    ex, ey = x_word - LAYOUT_L['card_gap'] - SMALL['w'], LAYOUT_L['card_y']
    for (x, y), c in epx.items():
        new.putpixel((ex + x, ey + y), c + (255,))
    for (x, y), c in eglow.items():
        nglow.putpixel((ex + x, ey + y), c)

    # rubble: bring the portrait's bottom-corner pebbles along with DUNGEON
    for yy in range(87, port.height):
        for xx in range(port.width):
            p = port.getpixel((xx, yy))
            if p[3] == 255 and 0 <= yy + dy < H - 1:
                new.putpixel((xx + dx, yy + dy), p)

    a_new = np.array(new)[:, :, 3]
    arr = np.zeros((H, W, 4), np.uint8)
    arr[:, :, 3] = shadow(np.where(a_new == 255, 255, 0).astype(np.uint8))
    out = Image.fromarray(arr)
    out.alpha_composite(new)
    return out, nglow


LAYOUT_L = dict(anki_y=2, card_y=1, card_gap=5)


def build(src):
    """Return a new full banners.png with both title logos replaced."""
    out = src.copy()
    port, pglow = build_port(src)
    land, lglow = build_land(src)
    for img, box in [(port, PORT), (pglow, PORT_GLOW), (land, LAND), (lglow, LAND_GLOW)]:
        out.paste(Image.new('RGBA', img.size, (0, 0, 0, 0)), box[:2])
        out.paste(img, box[:2])
    return out


def preview(img, glow, path, k=8, bg=(128, 128, 128)):
    b = Image.new('RGBA', img.size, bg + (255,))
    b.alpha_composite(img)
    b.alpha_composite(glow)
    b.resize((img.width * k, img.height * k), Image.NEAREST).save(path)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default=os.path.join(HERE, 'spd_banners_orig.png'))
    ap.add_argument('--out')
    ap.add_argument('--preview')
    ap.add_argument('--tag', default='logo')
    a = ap.parse_args()
    src = Image.open(a.src).convert('RGBA')
    banners = build(src)
    if a.out:
        banners.save(a.out)
    if a.preview:
        os.makedirs(a.preview, exist_ok=True)
        empty = lambda im: Image.new('RGBA', im.size, (0, 0, 0, 0))
        for name, box, gbox in [('port', PORT, PORT_GLOW), ('land', LAND, LAND_GLOW)]:
            img, glow = banners.crop(box), banners.crop(gbox)
            preview(img, empty(img), os.path.join(a.preview, f'{a.tag}_{name}8x_grey.png'))
            preview(img, glow, os.path.join(a.preview, f'{a.tag}_{name}8x_dark_glow.png'), bg=(24, 24, 24))
            preview(img, empty(img), os.path.join(a.preview, f'{a.tag}_{name}1x_dark.png'), k=1, bg=(24, 24, 24))
            preview(img, glow, os.path.join(a.preview, f'{a.tag}_{name}3x_dark_glow.png'), k=3, bg=(24, 24, 24))
        # side by side with the original at 3x
        for name, box in [('port', PORT), ('land', LAND)]:
            o, n = src.crop(box), banners.crop(box)
            sbs = Image.new('RGBA', (o.width * 2 + 4, o.height), (24, 24, 24, 255))
            sbs.alpha_composite(o, (0, 0)); sbs.alpha_composite(n, (o.width + 4, 0))
            sbs.resize((sbs.width * 3, sbs.height * 3), Image.NEAREST).save(os.path.join(a.preview, f'{a.tag}_{name}_vs_orig3x.png'))
