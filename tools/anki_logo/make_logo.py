"""Generates the Anki Dungeon title logo (assets/ui/title.png).

Keeps the lower half of the original Remixed Dungeon logo (the "dungeon"
word, sword and tentacles) and replaces the upper word and the eyeball
with "Anki" drawn in the same stone-block style plus a flashcard emblem.

usage: python3 make_logo.py <original title.png> <output title.png>
"""
import sys
from PIL import Image

OUTLINE = (15, 12, 15, 255)
HILITE = (174, 173, 154, 255)
LIGHT = (154, 154, 132, 255)
FILL = (123, 122, 99, 255)
FILL_LOW = (99, 99, 76, 255)
SHADE = (87, 86, 67, 255)
DEEP = (46, 46, 29, 255)

CARD = (233, 218, 215, 255)
CARD_HI = (255, 255, 255, 255)
CARD_SHADE = (190, 170, 170, 255)
INK = (101, 29, 29, 255)
INK_DARK = (69, 26, 40, 255)


def rect(mask, x0, y0, x1, y1, v=True):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            mask.add((x, y)) if v else mask.discard((x, y))


def glyph_A():
    m = set()
    rect(m, 0, 0, 17, 23)
    rect(m, 5, 5, 12, 10, False)
    rect(m, 5, 16, 12, 23, False)
    for p in [(0, 0), (17, 0), (0, 1), (17, 1), (1, 0), (16, 0)]:
        m.discard(p)
    return m, 18


def glyph_n():
    m = set()
    rect(m, 0, 6, 16, 23)
    rect(m, 5, 11, 11, 23, False)
    for p in [(16, 6), (15, 6), (16, 7)]:
        m.discard(p)
    return m, 17


def glyph_k():
    m = set()
    rect(m, 0, 0, 4, 23)          # stem
    rect(m, 12, 6, 16, 9)         # upper arm
    rect(m, 9, 9, 13, 12)
    rect(m, 5, 12, 10, 14)        # joint
    rect(m, 9, 14, 13, 17)
    rect(m, 12, 17, 16, 23)       # lower leg
    return m, 17


def glyph_i():
    m = set()
    rect(m, 0, 0, 4, 3)
    rect(m, 0, 6, 4, 23)
    return m, 5


def shade_word(img, glyphs, ox, oy, gap=2):
    """Draws mask glyphs with the stone shading of the original logo."""
    mask = set()
    x = ox
    for g, w in glyphs:
        for (gx, gy) in g:
            mask.add((x + gx, oy + gy))
        x += w + gap
    h = 22
    # outline (2px wide like the original, which uses a heavy dark rim)
    rim = set()
    for (px, py) in mask:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1, 2):
                q = (px + dx, py + dy)
                if q not in mask:
                    rim.add(q)
    for q in rim:
        img.putpixel(q, OUTLINE)
    for (px, py) in mask:
        ry = py - oy
        up = (px, py - 1) not in mask
        left = (px - 1, py) not in mask
        down = (px, py + 1) not in mask
        right = (px + 1, py) not in mask
        if ry >= 15:
            c = SHADE if ry >= 19 else FILL_LOW
        else:
            c = FILL
        if up:
            c = HILITE
        elif left:
            c = LIGHT if ry < 15 else FILL
        if down or right:
            c = DEEP if ry >= 15 else SHADE
        img.putpixel((px, py), c)
    return x - gap - ox


def card(img, x0, y0, w, h, tilt=0):
    """Flashcard: cream card, dark outline, red question mark."""
    for y in range(y0 - 1, y0 + h + 1):
        for x in range(x0 - 1, x0 + w + 1):
            img.putpixel((x, y), OUTLINE)
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            c = CARD
            if y == y0 or x == x0:
                c = CARD_HI
            if y == y0 + h - 1 or x == x0 + w - 1:
                c = CARD_SHADE
            img.putpixel((x, y), c)


def question_mark(img, cx, cy, s=2):
    pat = [
        " ######  ",
        "##    ## ",
        "      ## ",
        "    ###  ",
        "   ##    ",
        "   ##    ",
        "         ",
        "   ##    ",
    ]
    for j, row in enumerate(pat):
        for i, ch in enumerate(row):
            if ch == '#':
                for a in range(s):
                    for b in range(s):
                        img.putpixel((cx + i * s + a, cy + j * s + b),
                                     INK if b == 0 or j >= 6 else INK_DARK)


def main(src, dst):
    orig = Image.open(src).convert('RGBA')
    W, H = orig.size
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))

    # keep the "dungeon" word with its tentacles, and both visible ends of
    # the sword that runs behind the letters
    cut = 74
    out.paste(orig.crop((0, cut, W, H)), (0, cut))
    for y in range(60, cut):
        for x in list(range(0, 27)) + list(range(122, W)):
            px = orig.getpixel((x, y))
            if px[3] >= 128:
                out.putpixel((x, y), px)
    # dark band where the sword passes behind the words
    for y in range(68, cut):
        for x in range(27, 122):
            out.putpixel((x, y), OUTLINE)

    glyphs = [glyph_A(), glyph_n(), glyph_k(), glyph_i()]
    gap = 3
    total = sum(w for _, w in glyphs) + gap * (len(glyphs) - 1)
    ox = (W - total) // 2

    # flashcard emblem where the eyeball sat: two stacked cards
    card(out, 54, 20, 34, 24)
    card(out, 60, 16, 34, 24)
    question_mark(out, 68, 20)

    shade_word(out, glyphs, ox, 46, gap)
    out.save(dst)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
