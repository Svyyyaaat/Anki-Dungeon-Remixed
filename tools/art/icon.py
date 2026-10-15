#!/usr/bin/env python3
"""Cram Dungeon launcher icon generator: an open SPD treasure chest with an
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
               mdpi 48: the 32-grid tile at 1.5x nearest neighbour (see legacy_mdpi)

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
    # greens: logo glow/moss tint (105,187,119)/(48,147,65) and SPD chest greens
    'green': [(105, 187, 119), (48, 147, 65), (19, 108, 19)],
    'deepgreen': [(27, 156, 27), (14, 79, 14), (10, 61, 10)],
    'brightgreen': [(105, 187, 119), (27, 156, 27), (14, 79, 14)],
    # the title logo's carved stone (SHATTERED's letters)
    'stone': [(212, 219, 200), (132, 142, 111), (70, 78, 53)],
}
# rune card (title logo): dark carved face, stone あ burning rune green
DARK_FACE_HI, DARK_FACE, DARK_FACE_LO = (18, 20, 12), (36, 40, 27), (28, 31, 20)
# neon greens sampled from the title logo's あ at the peak of its glow
NEON_BODY, NEON_CORE, NEON_HOT = (180, 255, 176), (206, 255, 207), (237, 255, 230)
NEON_HALO = (52, 91, 49)
# a more saturated take for small sizes, where the pale mint reads as white
VIVID_BODY, VIVID_CORE, VIVID_HOT = (64, 230, 92), (120, 255, 128), (190, 255, 180)
FACE_HI, FACE, FACE_LO = (255, 248, 215), (255, 244, 208), (217, 201, 161)
INK = {'red': (179, 0, 30), 'black': (41, 30, 0)}
# background stone (original SPD ic_launcher_background colours)
BG = [(53, 52, 49), (59, 58, 54), (70, 69, 65), (76, 75, 71), (79, 78, 74), (83, 82, 78)]

STYLE = dict(wood='green', frame='red', ink='black', trim=False)

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
# 32x32 art grid (outline included).  Rows, top to bottom:
#   0-15   open lid, tilted back (domed top edge, green panels, gold edge,
#          gold hinge band), seen behind the card
#   16     back rim of the box, 17-18 dark interior the card rises out of
#   19     outline on top of the front rim
#   20-30  chest front (gold rim with white glints, green panels, gold side
#          straps, lock plate, gold base band); 31 outline
ART = 32
BODY_X = (2, 29)           # chest / lid horizontal extent
CARD = (7, 3, 18, 20)      # x, y, w, h of the card body (outline drawn around it)
FRAME_W = 2
GLYPH_POS = (10, 6)        # glyph top-left in art coordinates
LID_TOP = 1
LID_INSETS = {0: 6, 1: 3, 2: 1}   # domed / receding top edge of the lid
HINGE = 14                 # lid hinge band rows HINGE..HINGE+1
BACK = 16                  # back rim of the box
RIM = 20                   # top row of the front rim (overlaps the card)
BODY_BOTTOM = 30


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
            dark = st.get('face') == 'dark'
            if fx == FRAME_W or fy == FRAME_W:
                c = DARK_FACE_HI if dark else FACE_HI
            elif fx == w - FRAME_W - 1:
                c = DARK_FACE_LO if dark else FACE_LO
            else:
                c = DARK_FACE if dark else FACE
        else:
            ring = min(fx, fy, w - 1 - fx, h - 1 - fy)
            if st.get('trim') and ring == FRAME_W - 1:
                # 1px gold trim between the frame and the face
                c = G_HI if (fx == ring or fy == ring) else G_MID
            elif fy == 0 or fx == 0:
                c = fr[0]
            elif fx == w - 1 or fy == h - 1:
                c = fr[2]
            else:
                c = fr[1]
        lay[(i, j)] = c
    for (i, j) in [(x + FRAME_W, y + FRAME_W), (x + w - FRAME_W - 1, y + FRAME_W)]:
        lay[(i, j)] = (G_MID if st.get('trim') else fr[1])
    gx, gy = GLYPH_POS
    glyph = {(gx + i, gy + j) for j, row in enumerate(GLYPH) for i, ch in enumerate(row) if ch == '#'}
    if st['ink'] == 'rune':
        # no glow layer on an icon: the burn is painted in. Green strokes with
        # a bright line along their lit edge and a dim green halo on the face.
        face = {p for p, c in lay.items() if c in (DARK_FACE, DARK_FACE_HI, DARK_FACE_LO)}
        if st.get('halo'):
            # faint glow only where it doesn't crowd the strokes: pixels touching
            # the glyph diagonally, never between two strokes
            for (i, j) in glyph:
                for di, dj in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
                    q = (i + di, j + dj)
                    if q in face and q not in glyph and not any(
                            (q[0] + a, q[1] + b) in glyph for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        lay[q] = NEON_HALO
        for (i, j) in glyph:
            # neon strokes like the title's at full glow: hottest on the lit
            # top/left edge, core-bright inside, body on the lower/right edge
            body, core, hot = ((VIVID_BODY, VIVID_CORE, VIVID_HOT) if st.get('neon') == 'vivid'
                               else (NEON_BODY, NEON_CORE, NEON_HOT))
            if (i, j - 1) not in glyph or (i - 1, j) not in glyph:
                lay[(i, j)] = hot
            elif (i, j + 1) not in glyph or (i + 1, j) not in glyph:
                lay[(i, j)] = body
            else:
                lay[(i, j)] = core
    else:
        for p in glyph:
            lay[p] = INK[st['ink']]
    return lay


def lid_layer(st):
    """The open lid behind the card + the dark interior of the box."""
    wd = WOOD[st['wood']]
    x0, x1 = BODY_X
    lay = {}
    for j in range(LID_TOP, HINGE + 2):
        r = j - LID_TOP
        inset = LID_INSETS.get(r, 0)
        for i in range(x0 + inset, x1 - inset + 1):
            edge_l, edge_r = i == x0 + inset, i == x1 - inset
            if r == 0 or (r in LID_INSETS and (edge_l or edge_r or i <= x0 + LID_INSETS.get(r - 1, inset) or
                                                i >= x1 - LID_INSETS.get(r - 1, inset))):
                c = G_HI if r == 0 or edge_l or i < (x0 + x1) / 2 else G_MID   # lit domed rim
            elif j > HINGE:
                c = G_LO                                                       # hinge band
            elif edge_l:
                c = G_MID
            elif edge_r:
                c = G_LO
            else:
                # green panel; glossy highlight band like SPD's chest lid
                k = j - LID_TOP
                if k in (4, 5):
                    c = WHITE if (i in (x0 + 1, x1 - 1)) else wd[3]
                elif k == 3 or k == 6:
                    c = wd[2]
                elif k >= 10:
                    c = wd[0]         # lid's lower part falls into shadow
                else:
                    c = wd[1]
            lay[(i, j)] = c
    for i in range(x0, x1 + 1):
        lay[(i, BACK)] = G_LO
        for j in range(BACK + 1, RIM - 1):
            lay[(i, j)] = VOID
    # a little treasure light spilling up beside the card
    cx0, cx1 = CARD[0] - 2, CARD[0] + CARD[2] + 1
    for (i, j, c) in [(cx0, RIM - 2, G_HI), (cx0 - 1, RIM - 2, G_MID),
                      (cx1, RIM - 2, G_HI), (cx1 + 1, RIM - 2, G_MID)]:
        lay[(i, j)] = c
    return lay


def body_layer(st):
    """Chest front, after the original SPD launcher chest: gold rim with white
    glints, green panels with a checker texture, gold side straps, lock plate."""
    wd = WOOD[st['wood']]
    x0, x1 = BODY_X
    lay = {}
    shape = rect(x0, RIM, x1 - x0 + 1, BODY_BOTTOM - RIM + 1)
    shape -= {(x0, BODY_BOTTOM), (x1, BODY_BOTTOM)}
    for (i, j) in shape:
        fx, fy = i - x0, j - RIM
        rx = x1 - i
        strap = fx <= 1 or rx <= 1
        if fy == 0:
            c = WHITE if (fx in (0, 1) or rx == 1) else (G_HI if rx > 0 else G_MID)
        elif fy == 1:
            c = G_LO
        elif j == BODY_BOTTOM:
            c = G_LO
        elif j == BODY_BOTTOM - 1:
            c = G_MID
        elif strap:
            c = (G_HI, G_MID)[fx] if fx <= 1 else (G_LO, G_MID)[rx]
        else:
            if fy == 2:
                c = wd[2]                          # lit top of the panel
            elif (i + j) % 2 == 0 and fy >= 4:
                c = wd[0]                          # checker texture (as in the SPD icon)
            else:
                c = wd[1]
        lay[(i, j)] = c
    # lock plate on the front, hanging from the rim
    px0 = (x0 + x1) // 2 - 2
    for i in range(px0, px0 + 6):
        for j in range(RIM + 1, RIM + 8):
            if j == RIM + 1:
                c = WHITE if i < px0 + 5 else G_HI
            elif i == px0:
                c = G_HI
            elif i == px0 + 5 or j == RIM + 7:
                c = G_LO
            else:
                c = G_MID
            lay[(i, j)] = c
    for (i, j) in [(px0 + 2, RIM + 3), (px0 + 3, RIM + 3), (px0 + 2, RIM + 4), (px0 + 3, RIM + 4),
                   (px0 + 2, RIM + 5)]:
        lay[(i, j)] = VOID
    lay[(px0 + 3, RIM + 5)] = G_LO
    return lay


def dilate8(s):
    return {(x + dx, y + dy) for (x, y) in s for dx in (-1, 0, 1) for dy in (-1, 0, 1)}


def sparkles():
    """Four-point loot sparkles (white core, gold arms)."""
    out = {}
    for (x, y, big) in SPARKS:
        out[(x, y)] = WHITE
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            out[(x + dx, y + dy)] = G_HI
    return out


SPARKS = [(27, 2, False), (4, 2, False)]


def art(st=None):
    st = st or STYLE
    lid, card, body = lid_layer(st), card_layer(st), body_layer(st)
    img = Image.new('RGBA', (ART, ART), (0, 0, 0, 0))
    union = set(lid) | set(card) | set(body)
    x0, x1 = BODY_X
    layers = [
        {p: OUT for p in dilate8(union) - union},          # one continuous outer outline
        lid,
        {p: OUT for p in dilate8(set(card)) if p in lid},  # card separated from the lid
        card,
        {p: OUT for p in rect(x0, RIM - 1, x1 - x0 + 1, 1)},  # edge line on top of the front rim
        body,
        sparkles(),
    ]
    for lay in layers:
        for (x, y), c in lay.items():
            if 0 <= x < ART and 0 <= y < ART:
                img.putpixel((x, y), c + (255,))
    return img


def about_icon(st=None):
    """Just the card (with its outline), for the Cram Dungeon block on the About screen."""
    st = st or STYLE
    card = card_layer(st)
    edge = dilate8(set(card)) - set(card)
    xs = [x for x, _ in edge]
    ys = [y for _, y in edge]
    x0, y0 = min(xs), min(ys)
    img = Image.new('RGBA', (max(xs) - x0 + 1, max(ys) - y0 + 1), (0, 0, 0, 0))
    for (x, y) in edge:
        img.putpixel((x - x0, y - y0), OUT + (255,))
    for (x, y), c in card.items():
        img.putpixel((x - x0, y - y0), c + (255,))
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
          'xhdpi': (32, 3), 'xxxhdpi': (32, 6)}


def legacy_mdpi(st=None):
    """48px legacy icon: the 32-grid tile at 1.5x nearest neighbour.  Pixels
    alternate 1px/2px, so every 2px stroke of the あ becomes an even 3px."""
    return legacy(32, st).resize((48, 48), Image.NEAREST)


def up(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def write_about(root):
    about_icon().save(os.path.join(root, 'core', 'src', 'main', 'assets', 'anki', 'about_icon.png'))


def write_repo(root):
    # release (main) and debug builds ship their own copies of the icon set
    for flavour in ('main', 'debug'):
        write_res(os.path.join(root, 'android', 'src', flavour, 'res'))
    # the themed (monochrome) icon is a vector drawable: tools/art/mono_vector.py
    write_about(root)


def write_res(res):
    fg, bg = adaptive_fg(), adaptive_bg()
    for d, k in DENS.items():
        p = os.path.join(res, 'mipmap-' + d)
        up(fg, k).save(os.path.join(p, 'ic_launcher_foreground.png'))
        up(bg, k).save(os.path.join(p, 'ic_launcher_background.png'))
    for d, (g, k) in LEGACY.items():
        up(legacy(g), k).save(os.path.join(res, 'mipmap-' + d, 'ic_launcher.png'))
    legacy_mdpi().save(os.path.join(res, 'mipmap-mdpi', 'ic_launcher.png'))


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
    actual_sheet(st).save(os.path.join(d, f'{tag}_actual.png'))


def actual_sheet(st=None):
    """Real pixel sizes.  Top: legacy mdpi 48 (1x), xhdpi 96, xxxhdpi 192, and
    mdpi again at 4x to inspect its pixels.  Bottom: adaptive (circle mask)
    at 48/96/192 as a launcher would show it."""
    comp = composite(st)
    sheet = Image.new('RGBA', (48 + 96 + 192 + 192 + 50, 2 * 192 + 30), (40, 44, 52, 255))
    x = 10
    for img in [legacy_mdpi(st), up(legacy(32, st), 3), up(legacy(32, st), 6), up(legacy_mdpi(st), 4)]:
        sheet.alpha_composite(img, (x, 10))
        x += img.width + 10
    x = 10
    for s in (48, 96, 192):
        # (preview only: the launcher itself rescales the 72dp viewport)
        c = circle_crop(up(comp, 8)).resize((s, s), Image.BOX)
        sheet.alpha_composite(c, (x, 202 + 10))
        x += s + 10
    return sheet


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', help='repo root to write the PNGs into')
    ap.add_argument('--preview')
    ap.add_argument('--tag', default='icon')
    a = ap.parse_args()
    if a.preview:
        previews(a.preview, a.tag)
    if a.out:
        write_repo(a.out)
