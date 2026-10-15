#!/usr/bin/env python3
"""Anki Dungeon launcher icon generator: an open SPD treasure chest with an
Isaac-style flashcard (bearing a bold hand-pixelled あ) rising out of it.

Everything is placed by hand on a low-res grid and integer-scaled with
nearest neighbour (no smoothing anywhere).  Colours are SPD's: the chest uses
the gold/green of SPD's original launcher chest (or the brown/gold of the
golden chest sprite in items.png), the card frame uses reds/blues sampled from
items.png, the backdrop the six stone colours of the original icon background.

Grids
  * art:       32x32 (ART), centred on the adaptive canvas
  * adaptive:  54x54 grid (= 108dp), x2/x3/x4/x6/x8 for mdpi..xxxhdpi,
               art at offset 11 -> art centre == canvas centre
  * legacy:    ic_launcher.png
               36-grid (art + 2px stone border): ldpi 36 (x1), hdpi 72 (x2), xxhdpi 144 (x4)
               32-grid (art on stone, no border): xhdpi 96 (x3), xxxhdpi 192 (x6)
               48-grid (art + 8px stone border): mdpi 48 (x1)

Usage: python3 icon.py [--out REPO_ROOT] [--preview DIR] [--tag NAME]
"""
import argparse
import os
import random

from PIL import Image, ImageDraw

# ---------------------------------------------------------------- palette
OUT = (24, 18, 6)               # outline: near-black warm brown
VOID = (41, 30, 0)              # chest interior (SPD icon keyhole colour)
WHITE = (255, 255, 255)
# gold (SPD original launcher chest)
G_HI, G_MID, G_LO = (255, 235, 123), (221, 152, 0), (167, 116, 0)
# chest wood variants
WOOD = {
    'green': [(10, 61, 10), (14, 79, 14), (19, 108, 19), (27, 156, 27)],   # SPD icon chest
    'brown': [(80, 26, 26), (108, 33, 33), (115, 52, 46), (150, 79, 73)],  # SPD golden chest
}
# card frame variants (items.png reds / blues / purples): hi, mid, lo
FRAME = {
    'red': [(236, 34, 8), (179, 0, 30), (128, 30, 10)],
    'blue': [(68, 124, 230), (55, 72, 182), (31, 40, 110)],
    'purple': [(152, 26, 171), (125, 22, 217), (65, 17, 144)],
}
FACE_HI, FACE, FACE_LO = (255, 248, 215), (255, 244, 208), (217, 201, 161)
INK = {'red': (179, 0, 30), 'black': (41, 30, 0)}
# background stone (original SPD ic_launcher_background colours)
BG = [(53, 52, 49), (59, 58, 54), (70, 69, 65), (76, 75, 71), (79, 78, 74), (83, 82, 78)]

STYLE = dict(wood='green', frame='red', ink='black')

# ---------------------------------------------------------------- glyph
# hand-pixelled bold hiragana A, 2px strokes (12x13). Designed on the grid,
# not rasterised from a font.
GLYPH = [
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

# ---------------------------------------------------------------- layout
ART = 32
CARD = (7, 0, 18, 22)      # x, y, w, h of the card body (outline drawn around it)
FRAME_W = 2
GLYPH_POS = (10, 3)        # glyph top-left in art coordinates
LID = (3, 3, 26, 10)       # opened lid (inside face visible), behind the card; domed top
BACK = 14                  # row of the back rim of the box
RIM = 18                   # top row of the front rim (overlaps the card)
BODY_BOTTOM = 28


def rect(x, y, w, h):
    return {(i, j) for i in range(x, x + w) for j in range(y, y + h)}


def round_corners(s, r=1):
    """Remove corner pixels of a rectangle-ish set (radius 1 or 2)."""
    xs = [p[0] for p in s]; ys = [p[1] for p in s]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    cut = set()
    for cx, cy, dx, dy in [(x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)]:
        cut.add((cx, cy))
        if r >= 2:
            cut |= {(cx + dx, cy), (cx, cy + dy)}
    return s - cut


def card_layer(st):
    fr = FRAME[st['frame']]
    x, y, w, h = CARD
    shape = round_corners(rect(x, y, w, h), 2)
    lay = {}
    for (i, j) in shape:
        fx, fy = i - x, j - y
        inner = FRAME_W <= fx < w - FRAME_W and FRAME_W <= fy < h - FRAME_W
        if inner:
            # face: lit top-left edge, shaded right edge
            if fx == FRAME_W or fy == FRAME_W:
                c = FACE_HI
            elif fx == w - FRAME_W - 1:
                c = FACE_LO
            else:
                c = FACE
        else:
            # frame: highlight on top/left, shadow on right/bottom
            if fy == 0 or fx == 0:
                c = fr[0]
            elif fx == w - 1 or fy == h - 1:
                c = fr[2]
            else:
                c = fr[1]
        lay[(i, j)] = c
    # inner frame corners: round them too (the face is a rounded rect)
    for (i, j) in [(x + FRAME_W, y + FRAME_W), (x + w - FRAME_W - 1, y + FRAME_W)]:
        lay[(i, j)] = fr[1]
    gx, gy = GLYPH_POS
    ink = INK[st['ink']]
    for j, row in enumerate(GLYPH):
        for i, ch in enumerate(row):
            if ch == '#':
                lay[(gx + i, gy + j)] = ink
    return lay


def lid_layer(st):
    """Open lid seen from the front: its inside face, gold-rimmed."""
    wd = WOOD[st['wood']]
    x, y, w, h = LID
    shape = rect(x, y, w, h)
    # domed top: row 0 inset 5, row 1 inset 2, row 2 inset 1
    for j, inset in ((0, 5), (1, 2), (2, 1)):
        shape -= {(i, y + j) for i in range(x, x + inset)}
        shape -= {(i, y + j) for i in range(x + w - inset, x + w)}
    lay = {}
    for (i, j) in shape:
        fx, fy = i - x, j - y
        top = (fy == 0) or (fy == 1 and (fx < 5 or fx >= w - 5)) or (fy == 2 and (fx < 2 or fx >= w - 2))
        if top:
            c = G_HI
        elif fy <= 2 or fx <= 1 or fx >= w - 2:
            c = G_HI if fy == 0 else (G_LO if fx >= w - 2 else G_MID)
        elif fy >= h - 1:
            c = G_LO
        else:
            c = wd[0] if (fx in (2, w - 3) or fy == 2) else wd[1]
        lay[(i, j)] = c
    # the box's back rim and dark interior between lid and front rim,
    # with treasure-light spilling up next to the card
    for i in range(x, x + w):
        lay[(i, BACK)] = G_LO
        for j in range(BACK + 1, RIM):
            lay[(i, j)] = VOID
    cx0, cx1 = CARD[0] - 2, CARD[0] + CARD[2] + 1
    for (i, j, c) in [(cx0, RIM - 1, G_HI), (cx0 - 1, RIM - 1, G_MID), (cx0, RIM - 2, G_MID),
                      (cx1, RIM - 1, G_HI), (cx1 + 1, RIM - 1, G_MID), (cx1, RIM - 2, G_MID)]:
        lay[(i, j)] = c
    return lay


def body_layer(st):
    wd = WOOD[st['wood']]
    x0, x1 = LID[0], LID[0] + LID[2] - 1
    lay = {}
    # (body spans the lid's width)
    shape = rect(x0, RIM, x1 - x0 + 1, BODY_BOTTOM - RIM + 1)
    shape -= {(x0, BODY_BOTTOM), (x1, BODY_BOTTOM), (x0 + 1, BODY_BOTTOM), (x1 - 1, BODY_BOTTOM),
              (x0, BODY_BOTTOM - 1), (x1, BODY_BOTTOM - 1)}
    cx = (x0 + x1) / 2
    for (i, j) in shape:
        fx, fy = i - x0, j - RIM
        side = fx <= 2 or i >= x1 - 2
        if fy == 0:
            c = G_HI if not i >= x1 - 1 else G_MID          # lit top edge of the rim
        elif fy in (1, 2):
            c = G_MID if not i >= x1 - 1 else G_LO
        elif fy == 3:
            c = G_LO
        elif j >= BODY_BOTTOM - 1:
            c = G_MID if j == BODY_BOTTOM - 1 else G_LO      # bottom band
        elif side:
            c = G_MID if (fx == 1 or i == x1 - 1) else (G_HI if fx == 0 else G_LO)
        else:
            # planks: boards with dark seams, lit just under the rim
            if fy == 4:
                c = wd[3]
            elif (j - RIM) % 3 == 0:
                c = wd[0]
            else:
                c = wd[2] if fy == 5 or fy == 8 else wd[1]
            if fx == 3:
                c = wd[0]
        lay[(i, j)] = c
    # lock plate hanging from the rim
    px = int(cx) - 2
    for i in range(px, px + 6):
        for j in range(RIM + 2, RIM + 8):
            edge = i in (px, px + 5) or j == RIM + 7
            lay[(i, j)] = G_LO if edge else (G_HI if (i == px + 1 or j == RIM + 2) else G_MID)
    lay[(px + 2, RIM + 4)] = VOID
    lay[(px + 3, RIM + 4)] = VOID
    lay[(px + 2, RIM + 5)] = VOID
    lay[(px + 3, RIM + 5)] = VOID
    lay[(px + 3, RIM + 6)] = G_LO
    return lay


def dilate8(s):
    return {(x + dx, y + dy) for (x, y) in s for dx in (-1, 0, 1) for dy in (-1, 0, 1)}


def sparkles():
    """Four-point loot sparkles (white core, gold arms) around the card."""
    out = {}
    for (x, y, big) in SPARKS:
        out[(x, y)] = WHITE
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            out[(x + dx, y + dy)] = G_HI
        if big:
            for dx, dy in ((2, 0), (-2, 0), (0, 2), (0, -2)):
                out[(x + dx, y + dy)] = G_MID
    return out


SPARKS = [(26, 1, False), (5, 2, False)]


def art(st=None):
    st = st or STYLE
    lid, card, body = lid_layer(st), card_layer(st), body_layer(st)
    img = Image.new('RGBA', (ART, ART), (0, 0, 0, 0))
    # back to front: lid/interior, card (with its own outline), front of chest
    union = set(lid) | set(card) | set(body)
    layers = [
        {p: OUT for p in dilate8(union) - union},          # one continuous outer outline
        lid,
        {p: OUT for p in dilate8(set(card)) if p in lid},  # card separated from lid
        card,
        {p: OUT for p in rect(LID[0], RIM - 1, LID[2], 1)},  # rim top edge line
        body,
        sparkles(),
    ]
    for lay in layers:
        for (x, y), c in lay.items():
            if 0 <= x < ART and 0 <= y < ART:
                img.putpixel((x, y), c + (255,))
    return img


# ---------------------------------------------------------------- background
def stone(size, seed=7, vignette=True, dark=1):
    """Dungeon brick wall on a `size` grid using only SPD's six stone colours.

    Works on palette indices (0 = mortar ... 5 = brightest face) so that the
    vignette / darkening can step colours down without leaving the palette.
    """
    rnd = random.Random(seed)
    idx = [[0] * size for _ in range(size)]
    row_h = 8
    y = -3
    while y < size:
        x = -rnd.randint(2, 10)
        while x < size:
            w = rnd.choice([10, 11, 12, 13, 14, 16])
            face = rnd.choice([3, 4, 4, 5])
            for yy in range(y + 1, y + row_h):
                for xx in range(x + 1, x + w):
                    if not (0 <= xx < size and 0 <= yy < size):
                        continue
                    c = face
                    if yy == y + 1 or xx == x + 1:
                        c = min(5, face + 1)          # lit top/left edge
                    elif yy == y + row_h - 1 or xx == x + w - 1:
                        c = 2                         # shaded bottom/right edge
                    elif rnd.random() < 0.10:
                        c = max(2, face - 1)          # pitting
                    idx[yy][xx] = c
            x += w
        y += row_h
    img = Image.new('RGB', (size, size))
    cx = cy = (size - 1) / 2
    for yy in range(size):
        for xx in range(size):
            c = idx[yy][xx]
            if c == 0 and (xx + yy) % 5 == 0:
                c = 1
            if c > 0:
                c = max(1, c - dark)
            if vignette:
                d = max(abs(xx - cx), abs(yy - cy)) / (size / 2)
                if d > 0.80 and c > 0:
                    c -= 1
            img.putpixel((xx, yy), BG[c])
    return img


# ---------------------------------------------------------------- composition
ADAPT = 54
ART_OFF = (ADAPT - ART) // 2


def adaptive_fg(st=None):
    fg = Image.new('RGBA', (ADAPT, ADAPT), (0, 0, 0, 0))
    fg.alpha_composite(art(st), (ART_OFF, ART_OFF))
    return fg


def adaptive_bg():
    return stone(ADAPT).convert('RGBA')


def monochrome(st=None):
    """Silhouette for themed icons: outline pixels and the glyph become holes,
    so card and chest separate and the あ is cut out."""
    fg = adaptive_fg(st)
    ink = INK[(st or STYLE)['ink']]
    mono = Image.new('RGBA', fg.size, (0, 0, 0, 0))
    gx, gy = GLYPH_POS
    glyph = {(ART_OFF + gx + i, ART_OFF + gy + j) for j, r in enumerate(GLYPH) for i, ch in enumerate(r) if ch == '#'}
    for y in range(fg.height):
        for x in range(fg.width):
            p = fg.getpixel((x, y))
            if p[3] == 255 and p[:3] != OUT and (x, y) not in glyph and p[:3] != VOID:
                mono.putpixel((x, y), (0, 0, 0, 255))
    return mono


def legacy(grid, st=None):
    """Square legacy icon: stone tile with 2-grid-px rounded corners + art."""
    bg = stone(grid, seed=3).convert('RGBA')
    off = (grid - ART) // 2
    bg.alpha_composite(art(st), (off, off))
    mask = Image.new('L', (grid, grid), 255)
    for (x, y) in [(0, 0), (1, 0), (0, 1)]:
        for fx, fy in [(x, y), (grid - 1 - x, y), (x, grid - 1 - y), (grid - 1 - x, grid - 1 - y)]:
            mask.putpixel((fx, fy), 0)
    bg.putalpha(mask)
    return bg


DENS = {'mdpi': 2, 'hdpi': 3, 'xhdpi': 4, 'xxhdpi': 6, 'xxxhdpi': 8}
LEGACY = {'ldpi': (36, 1), 'hdpi': (36, 2), 'xxhdpi': (36, 4),
          'xhdpi': (32, 3), 'xxxhdpi': (32, 6), 'mdpi': (48, 1)}


def up(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def write_repo(root):
    res = os.path.join(root, 'android', 'src', 'main', 'res')
    fg, bg, mono = adaptive_fg(), adaptive_bg(), monochrome()
    for d, k in DENS.items():
        p = os.path.join(res, 'mipmap-' + d)
        up(fg, k).save(os.path.join(p, 'ic_launcher_foreground.png'))
        up(bg, k).save(os.path.join(p, 'ic_launcher_background.png'))
        up(mono, k).save(os.path.join(p, 'ic_launcher_monochrome.png'))
    for d, (g, k) in LEGACY.items():
        up(legacy(g), k).save(os.path.join(res, 'mipmap-' + d, 'ic_launcher.png'))


# ---------------------------------------------------------------- previews
def on(img, color):
    b = Image.new('RGBA', img.size, color + (255,))
    b.alpha_composite(img.convert('RGBA'))
    return b


def composite(st=None):
    c = adaptive_bg()
    c.alpha_composite(adaptive_fg(st))
    return c


def circle_crop(img, guides=False):
    """Crop to the visible 72dp area with a circular launcher mask."""
    w = img.width
    r = w * 36 / 108
    m = Image.new('L', img.size, 0)
    ImageDraw.Draw(m).ellipse((w / 2 - r, w / 2 - r, w / 2 + r, w / 2 + r), fill=255)
    out = Image.new('RGBA', img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    if guides:
        rs = w * 33 / 108
        ImageDraw.Draw(out).ellipse((w / 2 - rs, w / 2 - rs, w / 2 + rs, w / 2 + rs), outline=(255, 0, 255, 255))
    return out.crop((int(w / 2 - r), int(w / 2 - r), int(w / 2 + r), int(w / 2 + r)))


def previews(d, tag, st=None):
    os.makedirs(d, exist_ok=True)
    a = art(st)
    up(on(a, (128, 128, 128)), 8).save(os.path.join(d, f'{tag}_art8x_grey.png'))
    up(on(a, (24, 22, 20)), 8).save(os.path.join(d, f'{tag}_art8x_dark.png'))
    comp = composite(st)
    up(comp, 8).save(os.path.join(d, f'{tag}_adaptive8x.png'))
    circle_crop(up(comp, 8), guides=True).save(os.path.join(d, f'{tag}_adaptive8x_safezone.png'))
    up(on(legacy(36, st), (128, 128, 128)), 8).save(os.path.join(d, f'{tag}_legacy8x.png'))
    up(on(monochrome(st), (200, 200, 200)), 8).save(os.path.join(d, f'{tag}_mono8x.png'))
    actual_sheet(st).save(os.path.join(d, f'{tag}_actual.png'))


def actual_sheet(st=None):
    """Real pixel sizes: legacy 48/96/192 (top), adaptive circle 48/96/192 (bottom)."""
    comp = composite(st)
    sheet = Image.new('RGBA', (48 + 96 + 192 + 40, 2 * 192 + 30), (40, 44, 52, 255))
    x = 10
    for g, k, s in [(48, 1, 48), (32, 3, 96), (32, 6, 192)]:
        sheet.alpha_composite(up(legacy(g, st), k), (x, 10))
        # adaptive at mdpi/xhdpi/xxxhdpi-ish sizes: visible 72/108 of the canvas
        # (preview only: the launcher itself rescales the 72dp viewport)
        c = circle_crop(up(comp, 8)).resize((s, s), Image.BOX)
        sheet.alpha_composite(c, (x, 202 + 10))
        x += s + 10
    return sheet


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out')
    ap.add_argument('--preview')
    ap.add_argument('--tag', default='icon')
    a = ap.parse_args()
    if a.preview:
        previews(a.preview, a.tag)
    if a.out:
        write_repo(a.out)
