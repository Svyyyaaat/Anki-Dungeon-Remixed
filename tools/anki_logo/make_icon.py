"""Generates the Anki Dungeon launcher icon in all densities.

A 32x32 pixel-art design (flashcard over a sword on the original icon's
cream tile), scaled with nearest-neighbour to each drawable density.

usage: python3 make_icon.py <res dir>
"""
import sys
from PIL import Image

O = (254, 209, 142, 255)   # tile border (from the original icon)
C = (255, 240, 218, 255)   # tile fill
K = (26, 17, 16, 255)      # outline
W = (255, 255, 255, 255)
P = (240, 226, 222, 255)   # card
S = (196, 176, 172, 255)   # card shade
R = (162, 38, 38, 255)     # ink
D = (101, 29, 29, 255)
B = (206, 214, 222, 255)   # blade
G = (140, 150, 164, 255)   # blade shade
H = (155, 82, 22, 255)     # hilt
Y = (232, 166, 60, 255)    # hilt light


def build():
    im = Image.new('RGBA', (32, 32), O)
    for y in range(2, 30):
        for x in range(2, 30):
            im.putpixel((x, y), C)
    for (x, y) in [(2, 2), (29, 2), (2, 29), (29, 29)]:
        im.putpixel((x, y), O)

    # sword, bottom-left to top-right, drawn first so the card covers it
    for i in range(0, 20):
        x, y = 7 + i, 25 - i
        for (dx, dy, c) in [(0, 0, B), (1, 0, G), (0, -1, W), (1, -1, B),
                            (-1, 0, K), (2, 0, K), (0, -2, K), (1, 1, K), (2, -1, K), (-1, -1, K)]:
            px, py = x + dx, y + dy
            if 2 <= px < 30 and 2 <= py < 30:
                if c == K and im.getpixel((px, py)) in (B, G, W):
                    continue
                im.putpixel((px, py), c)
    im.putpixel((27, 4), K)
    # crossguard (perpendicular to the blade), grip and pommel
    for (x, y) in [(3, 21), (4, 22), (5, 23), (6, 24), (7, 25), (8, 26), (9, 27)]:
        for (dx, dy) in [(0, -1), (-1, 0), (1, 0), (0, 1)]:
            if im.getpixel((x + dx, y + dy)) == C:
                im.putpixel((x + dx, y + dy), K)
    for (x, y) in [(4, 22), (5, 23), (6, 24), (7, 25), (8, 26)]:
        im.putpixel((x, y), Y)
    for (x, y, c) in [(5, 25, H), (4, 26, H), (3, 27, Y), (5, 26, K), (4, 25, K), (3, 26, K), (2, 27, K), (4, 27, K), (3, 28, K), (6, 26, K), (4, 24, K), (6, 25, K)]:
        if c != K or im.getpixel((x, y)) == C:
            im.putpixel((x, y), c)

    # flashcard
    x0, y0, w, h = 8, 6, 17, 13
    for y in range(y0 - 1, y0 + h + 1):
        for x in range(x0 - 1, x0 + w + 1):
            im.putpixel((x, y), K)
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            c = P
            if y == y0 or x == x0:
                c = W
            if y == y0 + h - 1 or x == x0 + w - 1:
                c = S
            im.putpixel((x, y), c)
    q = ["####.",
         "#..##",
         "...##",
         "..##.",
         "..#..",
         ".....",
         "..#.."]
    for j, row in enumerate(q):
        for i, ch in enumerate(row):
            if ch == '#':
                im.putpixel((x0 + 6 + i, y0 + 3 + j), R if j < 5 else D)
    return im


def main(res):
    im = build()
    for d, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        im.resize((size, size), Image.NEAREST).save(f'{res}/drawable-{d}/ic_launcher.png')


if __name__ == '__main__':
    main(sys.argv[1])
