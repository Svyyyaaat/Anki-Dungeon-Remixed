#!/usr/bin/env python3
"""Anki Dungeon launcher icon generator.

All art is hand-placed on a low-res pixel grid and integer-scaled with
nearest neighbour.  Palette is sampled from SPD's own sprites
(sprites/items.png scroll + sword tiles, and the original launcher background).

  * foreground art: hand-placed on a 28x28 grid (adaptive) and a 24x24 grid
                    (legacy) -- same design, see LAYOUT
  * adaptive icon:  54x54 grid (108dp), x2/x3/x4/x6/x8 for mdpi..xxxhdpi;
                    the 28px art sits at (14,14)
  * legacy icon:    24x24 grid, x2/x3/x4/x6/x8 for mdpi..xxxhdpi,
                    ldpi (36px) is a box-filtered downsample of hdpi (72px)

Usage: python3 icon.py [--out REPO_ROOT] [--preview DIR]
"""
import argparse
import os
import random

from PIL import Image

# ---------------------------------------------------------------- palette
# parchment (sampled from SPD scroll sprites)
P_HI = (255, 244, 208)
P_MID = (217, 201, 161)
P_LO = (179, 159, 114)
P_SH = (157, 136, 97)
P_DK = (113, 94, 71)
INK = (72, 58, 46)
# card paper ramp: lit edge / body / shaded edge / darkest corner
TOPLEFT, BODY, EDGE, SHADE = P_HI, P_HI, P_LO, P_SH
LOWC = P_MID
SHADOW_A = 80  # alpha of the 1px drop shadow the art casts on the wall
# steel (SPD sword blade / grip)
S_HI = (255, 255, 255)
S_MID = (201, 201, 201)
S_LO = (127, 127, 127)
# gold guard / pommel (SPD longsword hilt)
G_HI = (231, 211, 92)
G_MID = (199, 183, 49)
G_LO = (132, 132, 23)
# leather grip (SPD shortsword hilt browns)
L_HI = (153, 120, 56)
L_LO = (99, 75, 28)
# outline: SPD outlines are 40% black; on the icon we bake that into an
# opaque very dark brown so it survives on any launcher.
OUT = (30, 24, 20)
# background stone (original SPD ic_launcher_background colours)
BG = [(53, 52, 49), (59, 58, 54), (70, 69, 65), (76, 75, 71), (79, 78, 74), (83, 82, 78)]

# ---------------------------------------------------------------- the card
# hand-pixelled hiragana A -- designed on the grid, not rasterised from a font
GLYPH_SMALL = [
    "....#......",
    ".#######...",
    "....#......",
    "....#.#....",
    "...#####...",
    "..#.##..#..",
    ".#..#....#.",
    ".#.##....#.",
    ".#.#.....#.",
    "..#.....#..",
    "......##...",
]
GLYPH_BIG = [
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

# per master-size layout: card rect, glyph offset, sword geometry
LAYOUT = {
    24: dict(card=(1, 1, 15, 17), glyph=(2, 2), glyph_px=GLYPH_SMALL, C=28, tip=7, guard=17, guard_len=2, ear=3,
             wear=[(3, 15), (9, 15), (6, 1), (13, 9)]),
    28: dict(card=(1, 1, 17, 20), glyph=(2, 3), glyph_px=GLYPH_BIG, C=32, tip=7, guard=21, guard_len=2, ear=4,
             wear=[(3, 17), (10, 17), (7, 1), (15, 12), (1, 9)]),
}



def card_layer(n):
    L = LAYOUT[n]
    cx, cy, w, h = L['card']
    f = L['ear']
    lay = {}
    for y in range(h):
        for x in range(w):
            r = w - 1 - x  # distance from right edge
            # dog-ear: cut the top-right triangle
            if r + y < f:
                continue
            if r + y == f:
                lay[(cx + x, cy + y)] = OUT
                continue
            # rounded corners
            if (x, y) in [(0, 0), (0, h - 1), (w - 1, h - 1)]:
                continue
            if x == 0 or y == 0 or x == w - 1 or y == h - 1:
                c = OUT
            elif y == 1 or x == 1:
                c = TOPLEFT
            elif x == w - 2 or y == h - 2:
                c = EDGE
            elif y > L['glyph'][1] + len(L['glyph_px']):
                c = LOWC   # lower paper below the glyph is a shade darker
            else:
                c = BODY
            lay[(cx + x, cy + y)] = c
    # bottom-right inner corner is the darkest paper
    lay[(cx + w - 2, cy + h - 2)] = SHADE
    # folded flap: right triangle with legs on column r=f and row y=f,
    # hypotenuse on the cut line.  Paper back side = darker; legs a shade darker
    # still; the card gets a 1px shadow left of / below the flap.
    for y in range(1, f + 1):
        for r in range(0, f + 1):
            if r + y <= f:
                continue
            p = (cx + w - 1 - r, cy + y)
            lay[p] = SHADE if (r == f or y == f) else EDGE
    for y in range(1, f + 1):
        p = (cx + w - 2 - f, cy + y)
        if lay.get(p) in (BODY, TOPLEFT):
            lay[p] = EDGE
    for r in range(1, f + 1):
        p = (cx + w - 1 - r, cy + f + 1)
        if lay.get(p) in (BODY, TOPLEFT):
            lay[p] = EDGE
    # wear speckles
    for wx, wy in L['wear']:
        p = (cx + wx, cy + wy)
        lay[p] = {BODY: EDGE, P_HI: BODY, EDGE: SHADE}.get(lay.get(p), lay.get(p))
    gx, gy = cx + L['glyph'][0], cy + L['glyph'][1]
    for y, row in enumerate(L['glyph_px']):
        for x, ch in enumerate(row):
            if ch == '#':
                lay[(gx + x, gy + y)] = INK
    return lay


# ---------------------------------------------------------------- the sword
# Exact 45 degree sword, tip top-right, hilt bottom-left (SPD orientation).
# Blade = three parallel diagonals x+y = C (highlight), C+1 (mid), C+2 (shade).
def sword_layer(n):
    L = LAYOUT[n]
    C, TIP_Y, g, gl = L['C'], L['tip'], L['guard'], L['guard_len']
    lay = {}
    for y in range(TIP_Y, g):
        lay[(C - y, y)] = S_HI
        lay[(C + 1 - y, y)] = S_MID
        if y > TIP_Y:
            lay[(C + 2 - y, y)] = S_LO
    # crossguard: perpendicular diagonal (x-y const) centred on the blade,
    # 2px staircase like SPD's swords (light upper row, dark lower row)
    gx = C + 1 - g
    for k in range(-gl, gl + 1):
        lay[(gx + k, g + k)] = G_HI if k <= 0 else G_MID
        lay[(gx + k, g + k + 1)] = G_LO
    # grip: two diagonals (light upper-left, dark lower-right), 3px long
    for y in range(g + 1, g + 4):
        lay.setdefault((C - y, y), L_HI)
        lay.setdefault((C + 1 - y, y), L_LO)
    # pommel: 2x2 gold knob
    y = g + 4
    lay[(C - y, y)] = G_HI
    lay[(C - y + 1, y)] = G_MID
    lay[(C - y, y + 1)] = G_MID
    lay[(C - y + 1, y + 1)] = G_LO
    return lay


def outline(lay, color=OUT):
    """8-neighbour dilation outline, like SPD's item sprites."""
    out = {}
    for (x, y) in lay:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                p = (x + dx, y + dy)
                if p not in lay:
                    out[p] = color
    return out


def art(n=24):
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    layers = [card_layer(n)]
    sw = sword_layer(n)
    layers.append(outline(sw))
    layers.append(sw)
    for lay in layers:
        for (x, y), c in lay.items():
            if 0 <= x < n and 0 <= y < n:
                img.putpixel((x, y), c + (255,))
    return img


# ---------------------------------------------------------------- background
def stone(size, seed=7, vignette=True):
    """Dungeon brick wall on a `size` grid using only SPD's six stone colours.

    Works on palette indices (0 = mortar ... 5 = brightest face) so that the
    vignette can step colours down without leaving the palette.
    """
    rnd = random.Random(seed)
    idx = [[0] * size for _ in range(size)]
    row_h = 8
    y = -3
    r = 0
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
            # mortar between bricks: alternate the two dark tones
            x += w
        y += row_h
        r += 1
    img = Image.new('RGB', (size, size))
    cx = cy = (size - 1) / 2
    for yy in range(size):
        for xx in range(size):
            c = idx[yy][xx]
            if c == 0 and (xx + yy) % 5 == 0:
                c = 1
            if vignette:
                d = max(abs(xx - cx), abs(yy - cy)) / (size / 2)
                if d > 0.80 and c > 0:
                    c -= 1
            img.putpixel((xx, yy), BG[c])
    return img


# ---------------------------------------------------------------- composition
ADAPT = 54
ART_N = 28
ART_OFF = (ADAPT - ART_N) // 2 + 1  # +1: card mass is top-left heavy


def with_shadow(img):
    """Add a 1px down-right drop shadow (translucent black) behind opaque art."""
    out = Image.new('RGBA', img.size, (0, 0, 0, 0))
    a = img.split()[3]
    for y in range(img.height):
        for x in range(img.width):
            if a.getpixel((x, y)) == 0 and x > 0 and y > 0 and a.getpixel((x - 1, y - 1)) == 255:
                out.putpixel((x, y), (0, 0, 0, SHADOW_A))
    out.alpha_composite(img)
    return out


def adaptive_fg(shadow=True):
    fg = Image.new('RGBA', (ADAPT, ADAPT), (0, 0, 0, 0))
    fg.alpha_composite(art(ART_N), (ART_OFF, ART_OFF))
    return with_shadow(fg) if shadow else fg


def adaptive_bg():
    return stone(ADAPT).convert('RGBA')


def monochrome():
    """Single-colour version for Android 13 themed icons.

    Outline pixels and the glyph's ink become holes, so the card reads as a
    card with an あ cut out of it and the sword is separated from the card by
    a 1px gap instead of merging into one blob.
    """
    fg = adaptive_fg(shadow=False)
    mono = Image.new('RGBA', fg.size, (0, 0, 0, 0))
    for y in range(fg.height):
        for x in range(fg.width):
            p = fg.getpixel((x, y))
            if p[3] == 255 and p[:3] not in (OUT, INK):
                mono.putpixel((x, y), (0, 0, 0, 255))
    return mono


def legacy24():
    bg = stone(24, seed=3).convert('RGBA')
    bg.alpha_composite(with_shadow(art(24)))
    # rounded corners (radius 2 grid px)
    mask = Image.new('L', (24, 24), 255)
    for (x, y) in [(0, 0), (1, 0), (0, 1)]:
        for fx, fy in [(x, y), (23 - x, y), (x, 23 - y), (23 - x, 23 - y)]:
            mask.putpixel((fx, fy), 0)
    bg.putalpha(mask)
    return bg


DENS = {'mdpi': 2, 'hdpi': 3, 'xhdpi': 4, 'xxhdpi': 6, 'xxxhdpi': 8}


def up(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def write_repo(root):
    res = os.path.join(root, 'android', 'src', 'main', 'res')
    fg, bg, mono, leg = adaptive_fg(), adaptive_bg(), monochrome(), legacy24()
    for d, k in DENS.items():
        p = os.path.join(res, 'mipmap-' + d)
        up(fg, k).save(os.path.join(p, 'ic_launcher_foreground.png'))
        up(bg, k).save(os.path.join(p, 'ic_launcher_background.png'))
        up(mono, k).save(os.path.join(p, 'ic_launcher_monochrome.png'))
        up(leg, k).save(os.path.join(p, 'ic_launcher.png'))
    # ldpi legacy: 36px, box-filtered from the 72px hdpi version
    up(leg, 3).resize((36, 36), Image.BOX).save(os.path.join(res, 'mipmap-ldpi', 'ic_launcher.png'))


# ---------------------------------------------------------------- previews
def on(img, color):
    b = Image.new('RGBA', img.size, color + (255,))
    b.alpha_composite(img.convert('RGBA'))
    return b


def circle_mask(img):
    w = img.width
    m = Image.new('L', img.size, 0)
    from PIL import ImageDraw
    # launchers show the center 72/108 of the adaptive icon, typically masked to a circle
    r = w * 36 / 108
    ImageDraw.Draw(m).ellipse((w / 2 - r, w / 2 - r, w / 2 + r, w / 2 + r), fill=255)
    out = Image.new('RGBA', img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out.crop((int(w / 2 - r), int(w / 2 - r), int(w / 2 + r), int(w / 2 + r)))


def previews(d, tag):
    os.makedirs(d, exist_ok=True)
    fg, bg, mono, leg = adaptive_fg(), adaptive_bg(), monochrome(), legacy24()
    a = art(ART_N)
    up(on(art(24), (128, 128, 128)), 8).save(os.path.join(d, f'{tag}_art24_8x_grey.png'))
    up(on(a, (128, 128, 128)), 8).save(os.path.join(d, f'{tag}_art8x_grey.png'))
    up(on(a, (24, 22, 20)), 8).save(os.path.join(d, f'{tag}_art8x_dark.png'))
    comp = bg.copy(); comp.alpha_composite(fg)
    up(comp, 8).save(os.path.join(d, f'{tag}_adaptive8x.png'))
    up(on(leg, (128, 128, 128)), 8).save(os.path.join(d, f'{tag}_legacy8x.png'))
    up(on(mono, (200, 200, 200)), 8).save(os.path.join(d, f'{tag}_mono8x.png'))
    # actual-size sheet: legacy 48/96/192, adaptive circle 48/96/192 equivalents
    sheet = Image.new('RGBA', (48 + 96 + 192 + 40, 2 * 192 + 30), (40, 44, 52, 255))
    x = 10
    for k, s in [(2, 48), (4, 96), (8, 192)]:
        sheet.alpha_composite(up(leg, k), (x, 10))
        c = circle_mask(up(comp, k * 2)).resize((s, s), Image.NEAREST) if False else circle_mask(up(comp, k * 2))
        c = c.resize((s, s), Image.BOX)
        sheet.alpha_composite(c, (x, 202 + 10))
        x += s + 10
    sheet.save(os.path.join(d, f'{tag}_actual.png'))


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
