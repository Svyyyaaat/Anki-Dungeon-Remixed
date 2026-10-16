"""Themed (Material You) launcher icon as a vector drawable.

Line art in the style of Shattered's own themed icon: thick, even, rounded
strokes. An open chest: the lid swung up behind, a card with the あ standing
up out of the box, the box's rim split around a lock plate with a keyhole cut
out. Parts that sit behind something stop short of it instead of crossing it.
Run from the repo root: python3 tools/art/mono_vector.py
"""
import math
import os

# あ from M PLUS Rounded 1c ExtraBold (SIL Open Font License), outline only,
# fitted into (42, 26)-(66, 55) on the 108-unit grid
GLYPH = (
    'M48.64 42.18Q46.34 43.63 46.34 45.7Q46.34 46.8 46.87 47.41Q47.39 48.02 48.33 48.02Q48.38 48.02 4'
    '8.47 48.01Q48.55 48 48.58 48Q48.84 48 48.84 47.74V42.26Q48.84 42.04 48.64 42.18ZM55.65 40.45Q54.'
    '4 40.45 53.23 40.62Q53.01 40.68 53.01 40.87V45.36Q53.01 45.41 53.08 45.44Q53.15 45.47 53.18 45.4'
    '1Q55.02 43.48 56.3 40.68Q56.33 40.59 56.27 40.52Q56.21 40.45 56.13 40.45ZM47.84 51.88Q45.21 51.8'
    '8 43.6 50.35Q42 48.82 42 46.18Q42 43.31 43.73 41.09Q45.46 38.86 48.64 37.7Q48.84 37.61 48.84 37.'
    '39V34.92Q48.84 34.69 48.61 34.69H44.92Q44.27 34.69 43.79 34.21Q43.3 33.73 43.3 33.07V32.65Q43.3 '
    '32 43.79 31.51Q44.27 31.03 44.92 31.03H48.61Q48.84 31.03 48.84 30.8V29.61Q48.84 28.96 49.32 28.4'
    '9Q49.8 28.02 50.45 28.02H51.39Q52.04 28.02 52.52 28.49Q53.01 28.96 53.01 29.61V30.8Q53.01 31.03 '
    '53.26 31.03H63.08Q63.73 31.03 64.21 31.51Q64.7 32 64.7 32.65V33.07Q64.7 33.73 64.21 34.21Q63.73 '
    '34.69 63.08 34.69H53.26Q53.01 34.69 53.01 34.92V36.48Q53.01 36.68 53.23 36.68Q54.26 36.56 55.65 '
    '36.56Q60.64 36.56 63.32 38.73Q66 40.9 66 44.65Q66 47.57 64.23 49.74Q62.45 51.91 59.33 52.9Q58.71'
    ' 53.1 58.11 52.76Q57.52 52.42 57.32 51.77L57.06 50.97Q56.87 50.38 57.18 49.8Q57.49 49.22 58.11 4'
    '8.96Q59.73 48.28 60.7 47.12Q61.66 45.95 61.66 44.65Q61.66 42.78 60.47 41.73Q60.33 41.58 60.21 41'
    '.84Q58.2 46.63 54.87 49.26Q51.53 51.88 47.84 51.88Z'
)

def f(v):
    return ('%.2f' % v).rstrip('0').rstrip('.')

def rr(x0, y0, x1, y1, r, tl=True, tr=True, br=True, bl=True):
    """Clockwise rounded rectangle; corners can be square."""
    a = lambda c: r if c else 0
    p = 'M%s,%s' % (f(x0 + a(tl)), f(y0))
    p += 'H%s' % f(x1 - a(tr))
    if tr: p += 'A%s,%s 0 0 1 %s,%s' % (f(r), f(r), f(x1), f(y0 + r))
    p += 'V%s' % f(y1 - a(br))
    if br: p += 'A%s,%s 0 0 1 %s,%s' % (f(r), f(r), f(x1 - r), f(y1))
    p += 'H%s' % f(x0 + a(bl))
    if bl: p += 'A%s,%s 0 0 1 %s,%s' % (f(r), f(r), f(x0), f(y1 - r))
    p += 'V%s' % f(y0 + a(tl))
    if tl: p += 'A%s,%s 0 0 1 %s,%s' % (f(r), f(r), f(x0 + r), f(y0))
    return p + 'Z'

def keyhole(cx, top_cy, r, stem_w0, stem_w1, bottom):
    """Round head with a flared stem, one clockwise contour."""
    ang = math.radians(55)
    lx, ly = cx - r * math.sin(ang), top_cy + r * math.cos(ang)
    rx = cx + r * math.sin(ang)
    p = 'M%s,%s' % (f(lx), f(ly))
    p += 'A%s,%s 0 1 1 %s,%s' % (f(r), f(r), f(rx), f(ly))
    p += 'L%s,%s' % (f(cx + stem_w1 / 2), f(bottom - 0.8))
    p += 'Q%s,%s %s,%s' % (f(cx + stem_w1 / 2), f(bottom), f(cx + stem_w1 / 2 - 0.8), f(bottom))
    p += 'H%s' % f(cx - stem_w1 / 2 + 0.8)
    p += 'Q%s,%s %s,%s' % (f(cx - stem_w1 / 2), f(bottom), f(cx - stem_w1 / 2), f(bottom - 0.8))
    return p + 'Z'

S = 5.5               # stroke weight of the line art
GLYPH_PIVOT = (54, 40.5)
GLYPH_SCALE = 0.85
GLYPH_SHIFT = (0, -2.5)   # the あ sits at (54, 38) on the card

def shapes():
    """('stroke', path) line art, ('fill', path, rule) solid shapes; drawn in order."""
    return [
        # lid swung open behind the card: its sides and the corners either side of the card
        ('stroke', 'M29,51.2 L24,34 Q23,30 27,30 H29.2'),
        ('stroke', 'M79,51.2 L84,34 Q85,30 81,30 H78.8'),
        # the card, its bottom inside the box
        ('stroke', 'M38,51.25 V22 Q38,18 42,18 H66 Q70,18 70,22 V51.25'),
        # box: rim split around the lock, sides and base
        ('stroke', 'M28,60 H46 M62,60 H80 M28,60 V84 H80 V60'),
        # lock plate with a keyhole cut out
        ('fill', rr(48.5, 56, 59.5, 73, 3.5) + keyhole(54, 63, 2.4, 2, 3, 69.5), 'evenOdd'),
    ]

# keep the drawing inside the themed icon's circle
SCALE = 0.78
PIVOT = (54, 53)

def svg(glyph, bg=None, fg='#000', size=432, circle=False):
    body = ''
    for sh in shapes():
        if sh[0] == 'stroke':
            body += ('<path d="%s" fill="none" stroke="%s" stroke-width="%s" stroke-linecap="round" '
                     'stroke-linejoin="round"/>' % (sh[1], fg, f(S)))
        else:
            body += '<path fill-rule="%s" d="%s"/>' % ('evenodd' if sh[2] == 'evenOdd' else 'nonzero', sh[1])
    body += ('<path d="%s" transform="translate(%s %s) translate(%s %s) scale(%s) translate(%s %s)"/>'
             % (glyph, f(GLYPH_SHIFT[0]), f(GLYPH_SHIFT[1]), f(GLYPH_PIVOT[0]), f(GLYPH_PIVOT[1]),
                f(GLYPH_SCALE), f(-GLYPH_PIVOT[0]), f(-GLYPH_PIVOT[1])))
    defs = ''
    group_attr = ''
    if circle:
        defs = '<defs><clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
        group_attr = ' clip-path="url(#c)"'
    back = '<circle cx="54" cy="54" r="36" fill="%s"/>' % bg if bg else ''
    vb = '18 18 72 72' if circle else '0 0 108 108'
    tf = ' transform="translate(%s %s) scale(%s) translate(%s %s)"' % (f(PIVOT[0]), f(PIVOT[1]), f(SCALE), f(-PIVOT[0]), f(-PIVOT[1]))
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="%s">%s%s<g fill="%s"%s><g%s>%s</g></g></svg>'
            % (size, size, vb, defs, back, fg, group_attr, tf, body))

def vector_drawable(glyph):
    items = ''
    for sh in shapes():
        if sh[0] == 'stroke':
            items += ('\n    <path\n        android:strokeColor="#FF000000"\n        android:strokeWidth="%s"\n'
                      '        android:strokeLineCap="round"\n        android:strokeLineJoin="round"\n'
                      '        android:pathData="%s" />' % (f(S), sh[1]))
        else:
            items += ('\n    <path\n        android:fillColor="#FF000000"\n        android:fillType="%s"\n'
                      '        android:pathData="%s" />' % (sh[2], sh[1]))
    items += ('\n    <group\n        android:pivotX="%s"\n        android:pivotY="%s"\n        android:scaleX="%s"\n'
              '        android:scaleY="%s"\n        android:translateX="%s"\n        android:translateY="%s">\n'
              '      <path\n          android:fillColor="#FF000000"\n          android:pathData="%s" />\n    </group>'
              % (f(GLYPH_PIVOT[0]), f(GLYPH_PIVOT[1]), f(GLYPH_SCALE), f(GLYPH_SCALE),
                 f(GLYPH_SHIFT[0]), f(GLYPH_SHIFT[1]), glyph))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Themed (Material You) launcher icon. Generated by tools/art/mono_vector.py -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            '  <group\n      android:pivotX="%s"\n      android:pivotY="%s"\n      android:scaleX="%s"\n      android:scaleY="%s">%s\n  </group>\n</vector>\n'
            % (f(PIVOT[0]), f(PIVOT[1]), f(SCALE), f(SCALE), items.replace('\n    ', '\n      ')))

def write_repo(root):
    xml = vector_drawable(GLYPH)
    for flavour in ('main', 'debug'):
        d = os.path.join(root, 'android', 'src', flavour, 'res', 'drawable')
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, 'ic_launcher_monochrome.xml'), 'w') as out:
            out.write(xml)


if __name__ == '__main__':
    write_repo(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
