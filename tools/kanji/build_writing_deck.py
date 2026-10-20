"""Data for Cram Dungeon's own kanji writing deck.

Builds core/src/main/assets/anki/kanji_writing.json.gz: the 2,136 Jouyou kanji in
school-grade order (then by frequency), each with meanings and readings from
KANJIDIC2, its strokes from KanjiVG (SVG paths in stroke order) and up to three
common example words from JMdict, used to prompt the kanji on the writing card.

Sources (downloaded into the work directory given as the only argument):
  KANJIDIC2  https://www.edrdg.org/kanjidic/kanjidic2.xml.gz      (EDRDG licence, CC BY-SA)
  JMdict     http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz          (EDRDG licence, CC BY-SA)
  KanjiVG    https://raw.githubusercontent.com/KanjiVG/kanjivg/master/kanji/  (CC BY-SA 3.0)

Run from the repo root: python3 tools/kanji/build_writing_deck.py /tmp/kanji-src
"""
import concurrent.futures
import gzip
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

OUT = os.path.join('core', 'src', 'main', 'assets', 'anki', 'kanji_writing.json.gz')
WORDS_PER_KANJI = 3
COMMON = ('news1', 'ichi1', 'spec1', 'spec2', 'gai1')


def fetch(url, path):
    if not os.path.exists(path) or os.path.getsize(path) < 200:
        subprocess.run(['curl', '-sS', '-f', '-L', '-m', '600', '-o', path, url], check=True)


def kanjidic(work):
    path = os.path.join(work, 'kanjidic2.xml.gz')
    fetch('https://www.edrdg.org/kanjidic/kanjidic2.xml.gz', path)
    out = []
    for ch in ET.parse(gzip.open(path)).getroot().iter('character'):
        misc = ch.find('misc')
        grade = misc.findtext('grade')
        if grade is None or int(grade) > 8:  # Jouyou: grades 1-6 and 8
            continue
        out.append({'k': ch.findtext('literal'), 'grade': int(grade),
                    'freq': int(misc.findtext('freq') or 9999),
                    'strokes': int(misc.findtext('stroke_count')),
                    'on': [r.text for r in ch.iter('reading') if r.get('r_type') == 'ja_on'],
                    'kun': [r.text for r in ch.iter('reading') if r.get('r_type') == 'ja_kun'],
                    'meanings': [m.text for m in ch.iter('meaning') if m.get('m_lang') is None]})
    return out


def is_kana(ch):
    return '぀' <= ch <= 'ヿ' or ch == 'ー'


def example_words(work, joyo):
    path = os.path.join(work, 'JMdict_e.gz')
    fetch('http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz', path)
    found = {}
    for _, e in ET.iterparse(gzip.open(path), events=('end',)):
        if e.tag != 'entry':
            continue
        rebs = [r.findtext('reb') for r in e.findall('r_ele')]
        senses = e.findall('sense')
        gloss = '; '.join([g.text for g in senses[0].findall('gloss')][:2]) if senses else ''
        for k in e.findall('k_ele'):
            keb = k.findtext('keb')
            pri = [p.text for p in k.findall('ke_pri')]
            if not keb or len(keb) > 4 or not any(p in COMMON for p in pri):
                continue
            if not all(ch in joyo or is_kana(ch) for ch in keb):
                continue
            nf = min([int(p[2:]) for p in pri if p.startswith('nf')] or [60])
            score = nf + (0 if 'ichi1' in pri else 5) + (len(keb) - 2) * 3
            for ch in set(keb):
                if ch in joyo:
                    found.setdefault(ch, []).append((score, keb, rebs[0] if rebs else '', gloss))
        e.clear()
    picked = {}
    for k, lst in found.items():
        seen, words = set(), []
        for _, w, r, g in sorted(lst):
            if w not in seen:
                seen.add(w)
                words.append([w, r, g])
            if len(words) == WORDS_PER_KANJI:
                break
        picked[k] = words
    return picked


def strokes(work, kanji):
    folder = os.path.join(work, 'kanjivg')
    os.makedirs(folder, exist_ok=True)

    def one(k):
        name = '%05x.svg' % ord(k)
        path = os.path.join(folder, name)
        fetch('https://raw.githubusercontent.com/KanjiVG/kanjivg/master/kanji/' + name, path)
        return k, re.findall(r'<path[^>]*\sd="([^"]+)"', open(path, encoding='utf-8').read())

    with concurrent.futures.ThreadPoolExecutor(16) as ex:
        return dict(ex.map(one, kanji))


def main(work):
    os.makedirs(work, exist_ok=True)
    kd = kanjidic(work)
    kd.sort(key=lambda c: (c['grade'], c['freq'], c['strokes']))
    joyo = {c['k'] for c in kd}
    words = example_words(work, joyo)
    paths = strokes(work, [c['k'] for c in kd])
    out = []
    for c in kd:
        w = words.get(c['k'])
        if not w:  # rare kanji without a common word: prompt with its own reading
            reading = (c['kun'] or c['on'] or [''])[0].replace('.', '')
            w = [[c['k'], reading, '; '.join(c['meanings'][:2])]]
        out.append({'k': c['k'], 'm': ', '.join(c['meanings'][:3]), 'on': '、'.join(c['on']),
                    'kun': '、'.join(c['kun']), 'w': w, 's': paths[c['k']], 'g': c['grade'],
                    'f': c['freq'] if c['freq'] < 9999 else 0})
    data = json.dumps(out, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    with gzip.open(OUT, 'wb', compresslevel=9) as f:
        f.write(data)
    print('%d kanji -> %s' % (len(out), OUT))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '/tmp/kanji-src')
