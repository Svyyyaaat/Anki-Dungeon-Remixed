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
An Isaac-style flashcard (red frame, cream face, red hand-pixelled あ, in the
letters' bevel treatment) sits on top (portrait) / to the left (landscape);
its あ pulses red in the glow layer.

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


def weld(letter, col, src_col, rows, fill_cols=()):
    """Turn a stem's dark inner edge into its interior colour on the given
    rows, so an arm can join it without a seam (keeps the glow pixel)."""
    for y in rows:
        if (col, y) in letter and (src_col, y) in letter:
            letter[(col, y)] = (letter[(src_col, y)][0], letter[(col, y)][1])
            for x in fill_cols:
                if (x, y) not in letter or True:
                    letter[(x, y)] = (letter[(src_col, y)][0], letter.get((x, y), (None, (0, 0, 0, 0)))[1])


def letters(port, pglow):
    """Cut the source letters out of the original portrait logo.

    All four come from SPD's own lettering (18 opaque rows each):
      A  'A' of SHATTERED
      R  'R' of SHATTERED
      C  small 'G' of DUNGEON with its bar/spur removed and the lower
         terminal re-capped with the bar's own top-edge highlight pixels
      M  two PIXEL 'I' stems with the upper (V) half of PIXEL's 'X' between
         them; the stems' inner edges are welded to the arms and the V's cut
         bottom gets the letters' dark bottom-edge colour
    """
    L = {}
    # 'A' of SHATTERED: rows 15-32, x 32-51; the T's bar starts at x48 on top rows
    L['A'] = opaque_cut(port, pglow, 32, 15, 51, 32, lambda x, y: y >= 22 or x <= 47)
    # 'R' of SHATTERED: x 92-106 rows 15-32 (T ends at x91, E starts at x107)
    L['R'] = opaque_cut(port, pglow, 92, 15, 106, 32)
    # 'C' from DUNGEON's small 'G' (x 63-79, rows 63-80): drop the bar
    c = opaque_cut(port, pglow, 63, 63, 79, 80, lambda x, y: not (x >= 72 and 70 <= y <= 76))
    for x in range(73, 79):
        if (x - 63, 77 - 63) in c:
            c[(x - 63, 77 - 63)] = (port.getpixel((x, 71)), (0, 0, 0, 0))
    if (72 - 63, 77 - 63) in c:
        c[(72 - 63, 77 - 63)] = (port.getpixel((72, 71)), (0, 0, 0, 0))
    L['C'] = c
    # 'M' = I stem + upper half of X (V) + I stem
    m = {}
    for (x, y), v in opaque_cut(port, pglow, 58, 39, 77, 47).items():
        m[(x, y)] = v
    for (x, y), v in opaque_cut(port, pglow, 50, 39, 57, 56).items():
        m[(x, y)] = v                # left stem at dx 0..7
        m[(x + 11, y)] = v           # right stem at dx 11..18
    rows_l = [y for y in range(18) if (8, y) in m or (7, y) in m and y not in (0, 1, 16, 17)]
    rows_r = [y for y in range(18) if (10, y) in m or (11, y) in m and y not in (0, 1, 16, 17)]
    weld(m, 6, 4, [y for y in range(2, 16) if (8, y) in m], fill_cols=(7,))
    weld(m, 12, 14, [y for y in range(2, 16) if (10, y) in m], fill_cols=(11,))
    BOTTOM_EDGE = (15, 18, 8, 255)
    for x in range(7, 12):
        if (x, 8) in m and x not in (0,):
            m[(x, 8)] = (BOTTOM_EDGE, m[(x, 8)][1])
    L['M'] = m
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
# Hand-pixelled hiragana A with 2px strokes (18x18 cell, 16 columns used),
# not rasterised from a font.
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

# letter treatment colours, taken from the original logo letters
HILITE = (212, 219, 200)
BOTTOM = (15, 18, 8)
EDGE_DARK = (33, 38, 23)
# Isaac-style card: red frame (items.png reds) + cream face (scroll parchment)
FR_HI = (224, 73, 73)
FR_TOP, FR_BOT = (179, 0, 30), (128, 30, 10)
FR_EDGE = (80, 26, 26)
FACE_TOP, FACE_BOT = (255, 244, 208), (217, 201, 161)
FACE_RIM = (157, 136, 97)       # inner bevel: shadowed top/left of the recessed face
FACE_LIP = (255, 248, 215)      # lit bottom edge of the recess
INK = (41, 30, 0)               # same near-black ink as the launcher icon
# glow layer: the あ pulses red-hot (the "signs" layer is an alpha-animated image)
GLOW_CORE = (210, 70, 30, 255)
GLOW_HALO = (236, 34, 8, 60)

# bold 12x13 あ (same design as the launcher icon card), drawn at 2x for the
# big landscape card so it reads like a card-suit symbol
GLYPH12 = [
    "....##......",
    ".##########.",
    ".##########.",
    "....##......",
    "....##.##...",
    "...#######..",
    "..##.##..##.",
    ".##..##...##",
    "##..###...##",
    "##.###....##",
    "####.....##.",
    ".##.....##..",
    ".....####...",
]
GLYPH12_2X = [''.join(ch * 2 for ch in row) for row in GLYPH12 for _ in (0, 1)]


def nn_scale(rows, f):
    """Nearest-neighbour scale of a glyph bitmap; at 1.5x every 2px stroke
    becomes an even 3px."""
    h, w = len(rows), len(rows[0])
    H, W = int(round(h * f)), int(round(w * f))
    return [''.join(rows[min(h - 1, int(y / f))][min(w - 1, int(x / f))] for x in range(W)) for y in range(H)]


GLYPH12_15X = nn_scale(GLYPH12, 1.5)

# portrait-format (3:4) cards like the icon's
CARD = dict(w=26, h=34, frame=3, glyph=GLYPH12_15X, off=(4, 7))
CARD_L = dict(w=36, h=48, frame=3, glyph=GLYPH12_2X, off=(6, 11))


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def card_emblem(spec=CARD):
    """Isaac-style card in the logo's bevel treatment.

    Returns (pixels {(x,y): rgb}, glow {(x,y): rgba}) relative to the card's
    top-left corner.  The soft drop shadow is added later with the rest.
    """
    w, h, f = spec['w'], spec['h'], spec['frame']
    px = {}
    corners = {(0, 0), (1, 0), (0, 1), (w - 1, 0), (w - 2, 0), (w - 1, 1),
               (0, h - 1), (1, h - 1), (0, h - 2), (w - 1, h - 1), (w - 2, h - 1), (w - 1, h - 2)}
    for y in range(h):
        t = y / (h - 1)
        for x in range(w):
            if (x, y) in corners:
                continue
            inner = f <= x < w - f and f <= y < h - f
            if inner:
                fx, fy = x - f, y - f
                iw, ih = w - 2 * f, h - 2 * f
                if (fx, fy) in {(0, 0), (iw - 1, 0), (0, ih - 1), (iw - 1, ih - 1)}:
                    c = lerp(FR_TOP, FR_BOT, t)       # rounded inner corners
                elif fy == 0 or fx == 0:
                    c = FACE_RIM                      # recess shadow (top/left)
                elif fy == ih - 1:
                    c = FACE_LIP                      # recess lit lip (bottom)
                else:
                    c = lerp(FACE_TOP, FACE_BOT, (fy - 1) / max(1, ih - 3))
            else:
                if y == h - 1:
                    c = BOTTOM
                elif y == 0 or (y == 1 and (x <= 1 or x >= w - 2)):
                    c = HILITE if 1 < x < w - 2 or y == 1 else HILITE
                elif x == 0 or x == w - 1:
                    c = EDGE_DARK if x == w - 1 else FR_EDGE
                elif y == 1:
                    c = FR_HI
                else:
                    c = lerp(FR_TOP, FR_BOT, t)
            px[(x, y)] = c
    gx, gy = spec['off']
    glyph = {(gx + x, gy + y) for y, row in enumerate(spec['glyph']) for x, ch in enumerate(row) if ch == '#'}
    for (x, y) in glyph:
        px[(x, y)] = INK
    glow = {}
    for (x, y) in glyph:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                p = (x + dx, y + dy)
                if p not in glyph:
                    glow[p] = GLOW_HALO
    for p in glyph:
        glow[p] = GLOW_CORE
    return px, glow


def stamp(new, nglow, x0, y0, spec=CARD):
    px, gl = card_emblem(spec)
    for (x, y), c in px.items():
        new.putpixel((x0 + x, y0 + y), c + (255,))
    for (x, y), c in gl.items():
        nglow.putpixel((x0 + x, y0 + y), c)


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
    word = ['C', 'R', 'A', 'M']
    total = sum(width(L[c]) for c in word) + gap * (len(word) - 1)
    x = (W - total) // 2
    y = LAYOUT['anki_y']
    for c in word:
        place(new, nglow, L[c], x, y)
        x += width(L[c]) + gap

    # emblem
    stamp(new, nglow, (W - CARD['w']) // 2, LAYOUT['card_y'])
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


LAYOUT = dict(anki_y=39, card_y=1)


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

    # line 2: DUNGEON block from the portrait logo (opaque rows 61-86);
    # the whole [card | ANKI/DUNGEON] group is centred
    group = CARD_L['w'] + LAYOUT_L['card_gap'] + 133
    dx, dy = (W - group) // 2 + CARD_L['w'] + LAYOUT_L['card_gap'] - 3, 24 - 61
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

    # line 1: ANKI centred over DUNGEON
    gap = 1
    word = ['C', 'R', 'A', 'M']
    total = sum(width(L[c]) for c in word) + gap * (len(word) - 1)
    dun_x0, dun_x1 = 3 + dx, 135 + dx       # DUNGEON's opaque extent (portrait x 3..135)
    x = (dun_x0 + dun_x1 + 1 - total) // 2
    for c in word:
        place(new, nglow, L[c], x, LAYOUT_L['anki_y'])
        x += width(L[c]) + gap
    # the card stands to the left of both lines, vertically centred on them
    stamp(new, nglow, dun_x0 - LAYOUT_L['card_gap'] - CARD_L['w'], LAYOUT_L['card_y'], CARD_L)

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


LAYOUT_L = dict(anki_y=2, card_y=2, card_gap=3)


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
    """Glow is added the way TitleScene draws it: additive ("light") blending
    at full strength, i.e. the peak of the pulse."""
    b = Image.new('RGBA', img.size, bg + (255,))
    b.alpha_composite(img)
    base = np.array(b).astype(int)
    g = np.array(glow).astype(int)
    base[:, :, :3] = np.minimum(255, base[:, :, :3] + g[:, :, :3] * g[:, :, 3:4] // 255)
    b = Image.fromarray(base.astype(np.uint8))
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
