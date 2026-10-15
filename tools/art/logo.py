#!/usr/bin/env python3
"""Cram Dungeon title logo generator.

Rebuilds the portrait (139x100 @ 0,0) and landscape (240x57 @ 0,100) title
logos in interfaces/banners.png plus their glow ("signs") layers at
(139,0) and (240,100).  Coordinates are those used by BannerSprites.java and
are left unchanged.

Every letter is cut from SPD's original logo so the bevel, gradient, moss and
cracks are native:
  C  <- small 'G' of DUNGEON without its bar/spur (see letters())
  R  <- 'R' of SHATTERED
  A  <- 'A' of SHATTERED
  M  <- left half of DUNGEON's small 'N' + its mirror image
  DUNGEON line is kept untouched.
The glow layer pixels travel with their letters.  The soft drop shadow around
the new letters is regenerated with a model fitted to the original halo
(mask shifted 1px down, gaussian r=1.0, x1.5 -> mean error ~7/255).
An Isaac-style flashcard (red frame, cream face, near-black hand-pixelled あ, in the
letters' bevel treatment) sits on top (portrait) / to the left (landscape);
its あ smoulders ember-red in the glow layer.

Usage: python3 logo.py [--src ORIGINAL_banners.png] [--out banners.png] [--preview DIR]
The original SPD banners.png is kept next to this script
(spd_banners_orig.png, the --src default) so the script stays reproducible
after the repo file is replaced.
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


M_CUT = 54   # half of DUNGEON's N up to here; M ends up 22px wide


OUTLINE = (35, 40, 25)


def round_c(c):
    """The G's lower arm runs flat towards its bar; rebuild it as a mirror of the
    C's upper hook (lit top surface, dark bottom outline) so both ends curl."""
    c = dict(c)
    h = 18
    top = {(x, y) for (x, y) in c if y <= 4 and x >= 6}
    for (x, y) in list(c):
        if y >= 13 and x >= 6:
            del c[(x, y)]
    none = (0, 0, 0, 0)
    mirrored = {(x, h - 1 - y) for (x, y) in top}
    for (x, y) in mirrored:
        col = sorted(yy for (xx, yy) in mirrored if xx == x)
        right = max(xx for (xx, yy) in mirrored if yy == y)
        if y == col[-1]:
            c[(x, y)] = (OUTLINE + (255,), none)
        elif y == col[0]:
            c[(x, y)] = ((212, 219, 200, 255), none)
        elif x == right:
            c[(x, y)] = ((70, 78, 53, 255), none)
        else:
            c[(x, y)] = ((101, 110, 83, 255), none)
    return c


def letters(port, pglow):
    """Cut the source letters out of the original portrait logo.

    All four come from SPD's own lettering (18 opaque rows each):
      A  'A' of SHATTERED
      R  'R' of SHATTERED
      C  small 'G' of DUNGEON with its bar/spur removed and the lower
         terminal re-capped with the bar's own top-edge highlight pixels
      M  left half of DUNGEON's small 'N' + its horizontal mirror
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
    L['C'] = round_c(c)
    # 'M' = left half of DUNGEON's small 'N' (left stem + the top of its
    # diagonal) plus its mirror image.  These letters are lit from the top
    # (light top edge, dark bottom) with equally dark left/right edges, so a
    # horizontal mirror keeps the lighting consistent.
    half = opaque_cut(port, pglow, 44, 63, M_CUT, 80)
    hw = M_CUT - 44 + 1
    m = dict(half)
    for (x, y), v in half.items():
        m[(2 * hw - 1 - x, y)] = v
    L['M'] = m
    return L


# Letter spacing for CRAM, as the gap between neighbouring letters' boxes.
# SPD's own words have no background between letters, and the narrow-topped
# A leaves wedges either side, so R and M tuck over its feet (R over A over M,
# like the T over the A in SHATTERED). The C keeps 2px off the R.
WORD = ['C', 'R', 'A', 'M']
KERN = {('C', 'R'): 2, ('R', 'A'): -3, ('A', 'M'): -3}
DRAW_ORDER = ['M', 'A', 'R', 'C']


def word_layout(L, x0, x1):
    """[(letter, x)] for CRAM centred on x0..x1, in drawing order."""
    xs, x = [], 0
    for i, c in enumerate(WORD):
        xs.append(x)
        if i + 1 < len(WORD):
            x += width(L[c]) + KERN[(c, WORD[i + 1])]
    lo = min(xs[i] + min(p[0] for p in L[c]) for i, c in enumerate(WORD))
    hi = max(xs[i] + max(p[0] for p in L[c]) for i, c in enumerate(WORD))
    shift = (x0 + x1 - (lo + hi) + 1) // 2
    pos = {c: xx + shift for c, xx in zip(WORD, xs)}
    return [(c, pos[c]) for c in DRAW_ORDER]


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
# Card frame in the letters' own stone (sampled row by row from SHATTERED's R:
# bright top highlight, darker band under it, lighter body further down),
# so the card reads as part of the carved title rather than a sticker.
FR_HI = (101, 110, 83)
FR_TOP, FR_BOT = (108, 118, 90), (132, 142, 111)
FR_EDGE = (70, 78, 53)
MOSS = (111, 162, 104)
# dark recessed face, like a slot carved into the stone
FACE_TOP, FACE_BOT = (40, 44, 30), (28, 31, 20)
FACE_RIM = (18, 20, 12)         # inner bevel: shadowed top/left of the recess
FACE_LIP = (70, 78, 53)         # lit bottom edge of the recess
# the あ is carved in the letters' stone and burns with their rune green
INK = (132, 142, 111)
INK_LIT = (158, 166, 142)       # top/left edges of the strokes
# glow layer: drawn ADDITIVELY with an animated alpha, like the letters' runes
# (same greens), but the whole glyph burns: green strokes, bright centre
# lines and a wider halo than the letters get
RUNE = (48, 147, 65)
RUNE_HI = (105, 187, 119)
GLOW_HALO_A = 90

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
                    # a few moss pixels along the bottom of the frame, like the letters
                    if y >= h - f - 1 and (x * 7 + y * 3) % 5 == 0:
                        c = MOSS
            px[(x, y)] = c
    gx, gy = spec['off']
    glyph = {(gx + x, gy + y) for y, row in enumerate(spec['glyph']) for x, ch in enumerate(row) if ch == '#'}
    centre = {p for p in glyph
              if all((p[0] + dx, p[1] + dy) in glyph for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))} or glyph
    for p in glyph:
        lit_edge = not all((p[0] + dx, p[1] + dy) in glyph for dx, dy in ((0, -1), (-1, 0)))
        px[p] = INK_LIT if lit_edge else INK
    glow = {}
    for (x, y) in glyph:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                q = (x + dx, y + dy)
                if q not in glyph:
                    glow[q] = RUNE + (GLOW_HALO_A,)
    for p in glyph:
        glow[p] = (RUNE_HI if p in centre else RUNE) + (255,)
    return px, glow


def stamp(new, nglow, x0, y0, spec=CARD, shade=('bottom', 9)):
    """Draw the card behind whatever is already drawn; {shade} darkens the side
    that goes behind the title. Returns the card's pixel positions."""
    px, gl = card_emblem(spec)
    side, n = shade
    covered = set()
    for (x, y), c in px.items():
        q = (x0 + x, y0 + y)
        if new.getpixel(q)[3] == 255:
            continue
        d = (y - (spec['h'] - n)) if side == 'bottom' else (x - (spec['w'] - n))
        k = 1.0 if d < 0 else 1.0 - 0.55 * (d + 1) / n
        new.putpixel(q, tuple(int(v * k) for v in c) + (255,))
        covered.add(q)
    for (x, y), c in gl.items():
        if (x0 + x, y0 + y) in covered or (x0 + x, y0 + y) not in {(x0 + a, y0 + b) for (a, b) in px}:
            nglow.putpixel((x0 + x, y0 + y), c)
    return covered


def cast_shadow(new, occ, onto):
    """Pixels in {occ} (letters over the card) cast a hard 1px shadow
    down-right onto the card pixels {onto}."""
    for (x, y) in occ:
        for dx, dy in ((1, 1), (0, 1), (1, 0)):
            q = (x + dx, y + dy)
            if q not in occ and q in onto:
                p = new.getpixel(q)
                new.putpixel(q, tuple(int(v * 0.45) for v in p[:3]) + (255,))


def letter_pixels(L, word, y0):
    return {(x + lx, y0 + ly) for c, x in word for (lx, ly) in L[c]}


# ------------------------------------------------------------------ build
def build_port(src):
    port = src.crop(PORT)
    pglow = src.crop(PORT_GLOW)
    L = letters(port, pglow)
    W, H = port.size

    keep_from = 61  # rows >= this come from the original (DUNGEON line)
    new = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    nglow = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    # card first, so CRAM overlaps its lower edge and it sits behind the title
    card = stamp(new, nglow, (W - CARD['w']) // 2, LAYOUT['card_y'])

    # CRAM centred over DUNGEON (opaque extent x 3..135)
    word = word_layout(L, 3, 135)
    cast_shadow(new, letter_pixels(L, word, LAYOUT['word_y']), card)
    for c, x in word:
        place(new, nglow, L[c], x, LAYOUT['word_y'])
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


# card_y: the card's bottom 6 rows go behind CRAM
LAYOUT = dict(word_y=39, card_y=11)


def build_land(src):
    """Landscape: [card] CRAM on line 1, DUNGEON (moved over from the portrait
    logo, it has the big D and N plus underline) on line 2."""
    land = src.crop(LAND)
    port = src.crop(PORT)
    pglow = src.crop(PORT_GLOW)
    L = letters(port, pglow)
    W, H = land.size
    new = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    nglow = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    # line 2: DUNGEON block from the portrait logo (opaque rows 61-86);
    # the whole [card | CRAM/DUNGEON] group is centred
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

    # line 1: CRAM centred over DUNGEON
    dun_x0, dun_x1 = 3 + dx, 135 + dx       # DUNGEON's opaque extent (portrait x 3..135)
    # the card stands to the left of both lines; drawn first so the words overlap it
    dungeon = {(x, y) for y in range(H) for x in range(W) if new.getpixel((x, y))[3] == 255}
    card = stamp(new, nglow, dun_x0 - LAYOUT_L['card_gap'] - CARD_L['w'], LAYOUT_L['card_y'], CARD_L,
                 shade=('right', 8))
    word = word_layout(L, dun_x0, dun_x1)
    cast_shadow(new, dungeon | letter_pixels(L, word, LAYOUT_L['word_y']), card)
    for c, x in word:
        place(new, nglow, L[c], x, LAYOUT_L['word_y'])

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


# card_gap < 0: the card tucks behind the start of both lines
LAYOUT_L = dict(word_y=2, card_y=2, card_gap=-4)


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
