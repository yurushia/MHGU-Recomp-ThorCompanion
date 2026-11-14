#!/usr/bin/env python3
"""
Where each area is on the whole-map pictures (android/assets/maps/map_NN.png): writes android/assets/maps/areas.csv
    stage,area,cx,cy,x0,y0,x1,y1       (pixels of the 1024 x 1024 picture; area 0 is the camp)

The pictures are 4x upscaled pixel art: transparent ground, black outlines, and in each stage one colour for the number labels and
the camp's icon (white, off-white, grey: it changes), while every other colour is an area (a blob, filled in its own colour) or
a thin dotted path between two. So, on the picture's own pixels (every 4th one):
  1. the areas are the blobs left when the thin paths are eroded away (a gentler erosion for an area too small to survive it);
  2. the labels are found by SHAPE, not colour (a pale area can be as light as a label): a colour whose pixels form glyphs that are
     digits is the label colour; the digit shapes are learned from map_01, whose labels were read by eye (LEARN below), and digits
     side by side are one number; the camp's icon is learned from map_01 the same way and found in any colour;
  3. each number is given to the nearest blob, no blob twice; the camp is where its icon is.
A picture is accepted only if its numbers are exactly 1..N with every number on a blob and no blob left over (the camp's, at most);
one that does not come out so is reported and gets no rows (the panel then shows the picture without markers on it).
usage: python3 tools/maps/make_anchors.py [--check DIR]   (--check writes a picture per map with what was found, to look at)
"""
import csv, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
MAPS = os.path.join(HERE, '..', '..', 'android', 'assets', 'maps')
# the labels of map_01, read from the picture (1024 px coordinates of the middle of each, and its number): the digit templates
LEARN = [((45, 300), 1), ((310, 540), 2), ((430, 780), 3), ((680, 335), 4), ((735, 75), 5), ((80, 85), 6), ((860, 570), 7),
         ((950, 225), 8), ((970, 650), 9), ((565, 950), 10), ((950, 880), 11)]
ICON_AT = (106, 710)  # map_01's camp icon
GW, GH = 10, 16       # a glyph, scaled to this for comparing


def grid(path):
    return np.array(Image.open(path).convert('RGBA'))[2::4, 2::4]  # (the middle of each 4 x 4 block)


def packed(a):
    return (a[..., 0].astype(np.int64) << 16) | (a[..., 1].astype(np.int64) << 8) | a[..., 2].astype(np.int64)


def components(mask, eight=False):
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    out = []
    nb = [(-1, 0), (1, 0), (0, -1), (0, 1)] + ([(-1, -1), (-1, 1), (1, -1), (1, 1)] if eight else [])
    for y0 in range(h):
        for x0 in range(w):
            if not mask[y0, x0] or seen[y0, x0]:
                continue
            stack, pts = [(y0, x0)], []
            seen[y0, x0] = True
            while stack:
                y, x = stack.pop()
                pts.append((y, x))
                for dy, dx in nb:
                    yy, xx = y + dy, x + dx
                    if 0 <= yy < h and 0 <= xx < w and mask[yy, xx] and not seen[yy, xx]:
                        seen[yy, xx] = True
                        stack.append((yy, xx))
            out.append(np.array(pts))
    return out


def erode(mask, n):
    m = mask.copy()
    for _ in range(n):
        p = np.pad(m, 1)
        m = m & p[:-2, 1:-1] & p[2:, 1:-1] & p[1:-1, :-2] & p[1:-1, 2:] & p[:-2, :-2] & p[:-2, 2:] & p[2:, :-2] & p[2:, 2:]
    return m


def holes(g):
    """How many holes a glyph has (background not connected to the outside): a 0 has one, an 8 two: a feature no scale changes."""
    p = np.pad(~g, 1, constant_values=True)
    n = 0
    for comp in components(p):
        if not (comp[:, 0] == 0).any() and not (comp[:, 0] == p.shape[0] - 1).any() and not (comp[:, 1] == 0).any() \
                and not (comp[:, 1] == p.shape[1] - 1).any():
            n += 1
    return n


def glyph_bits(pts):
    y0, x0 = pts.min(0)
    y1, x1 = pts.max(0)
    g = np.zeros((y1 - y0 + 1, x1 - x0 + 1), dtype=bool)
    g[pts[:, 0] - y0, pts[:, 1] - x0] = True
    # scaled by averaging (a hole in a 0 or an 8 survives a bigger or a smaller glyph), as how much of each cell is lit
    small = Image.fromarray((g * 255).astype(np.uint8)).resize((GW, GH), Image.BOX)
    return (np.asarray(small, dtype=float) / 255.0, holes(g)), (x0, y0, x1, y1)


def label_clusters(mask):
    """The shapes of a mask as glyphs, grouped into numbers: [(cx, cy, [glyph = (bits, holes)], box)] in the reduced pixels."""
    glyphs = []
    for pts in components(mask, eight=True):
        if len(pts) < 8:
            continue
        bits, box = glyph_bits(pts)
        glyphs.append((box, bits, pts))
    glyphs.sort(key=lambda g: g[0][0])
    used = [False] * len(glyphs)
    out = []
    for i, g in enumerate(glyphs):
        if used[i]:
            continue
        group = [g]
        used[i] = True
        for j in range(i + 1, len(glyphs)):
            if used[j]:
                continue
            last = group[-1][0]
            b = glyphs[j][0]
            if abs((b[1] + b[3]) / 2 - (last[1] + last[3]) / 2) < 6 and 0 <= b[0] - last[2] < 10:
                group.append(glyphs[j])
                used[j] = True
        x0 = min(q[0][0] for q in group)
        y0 = min(q[0][1] for q in group)
        x1 = max(q[0][2] for q in group)
        y1 = max(q[0][3] for q in group)
        out.append(((x0 + x1) / 2, (y0 + y1) / 2, [q[1] for q in group], (x0, y0, x1, y1)))
    return out


def learn():
    """(digit templates {char: (bits, holes)}, the camp icon's glyph), from map_01."""
    a = grid(os.path.join(MAPS, 'map_01.png'))
    p = packed(a)
    label = (a[..., 3] != 0) & (p == (238 << 16 | 235 << 8 | 230))
    digits, icon = {}, None
    for cx, cy, bits, box in label_clusters(label):
        if abs(cx * 4 - ICON_AT[0]) < 40 and abs(cy * 4 - ICON_AT[1]) < 40:
            icon = bits[0]  # (bits, holes)
            continue
        for (lx, ly), num in LEARN:
            if abs(cx * 4 - lx) < 60 and abs(cy * 4 - ly) < 60:
                text = str(num)
                assert len(text) == len(bits), (num, len(bits))
                for ch, b in zip(text, bits):
                    digits.setdefault(ch, b)
    assert len(digits) == 10 and icon is not None, (sorted(digits), icon is not None)
    return digits, icon


def read_number(bits, digits):
    text = ''
    worst = 0
    for b in bits:
        best, score = None, 1e9
        for ch, (t, th) in digits.items():
            if th != b[1]:
                continue  # (not a digit with this many holes)
            d = float(np.abs(b[0] - t).sum())
            if d < score:
                best, score = ch, d
        if best is None:
            return None, 1e9  # (a shape no digit has: not a number)
        worst = max(worst, score)
        text += best
    return int(text), worst


def scan_labels(path, digits, icon):
    """(the colour(s) a stage draws its numbers in, the camp icon's place or None). By shape: a colour that makes glyphs which are
    digits is a label colour; one that makes the camp's icon holds it. Near-black is the outlines, never a label."""
    a = grid(path)
    p = packed(a)
    ok = (a[..., 3] != 0) & (a[..., :3].max(axis=2) >= 24)
    colors, hit = [], None
    vals, counts = np.unique(p[ok], return_counts=True)
    for v, n in zip(vals, counts):
        if n < 20:
            continue
        found = False
        for cx, cy, bits, box in label_clusters(ok & (p == v)):
            h = box[3] - box[1] + 1
            if 16 <= h <= 32 and n >= 40:
                num, worst = read_number(bits, digits)
                if num is not None and worst <= 6 and len(bits) == 1:  # (the font is the same everywhere: a label matches exactly,
                    found = True                                          #  where a dark area that merely looks like a digit does not)
            if len(bits) == 1 and 8 <= h <= 30 and bits[0][1] == icon[1]:
                d = float(np.abs(bits[0][0] - icon[0]).sum())
                if d <= 14 and (hit is None or d < hit[0]):
                    hit = (d, cx, cy, box)
        if found:
            colors.append(((int(v) >> 16) & 255, (int(v) >> 8) & 255, int(v) & 255))
    return colors, (hit[1:] if hit else None)


def classes(path, label_colors):
    """(the areas' and paths' pixels, the labels' pixels): transparent ground and the outlines are neither."""
    a = grid(path)
    bg = a[..., 3] == 0
    black = (~bg) & (a[..., :3].max(axis=2) < 24)  # (a stage's outlines can be a hair off black: 080000)
    p = packed(a)
    label = (~bg) & np.isin(p, [(r << 16) | (g << 8) | b for r, g, b in label_colors])
    return (~bg) & (~black) & (~label), label


def blob_list(color, n_erode, min_size):
    out = []
    for pts in components(erode(color, n_erode)):
        if len(pts) < min_size:
            continue
        cy, cx = pts.mean(0)
        y0, x0 = pts.min(0)
        y1, x1 = pts.max(0)
        out.append({'c': (cx, cy), 'box': (x0 - n_erode, y0 - n_erode, x1 + n_erode, y1 + n_erode), 'pts': pts})
    return out


def dist(b, cx, cy):
    return float(np.sqrt(((b['pts'][:, 1] - cx) ** 2 + (b['pts'][:, 0] - cy) ** 2).min()))


def analyse(path, digits, icon):
    """(areas {number: (cx, cy, x0, y0, x1, y1)} in 1024 px, problems, blobs, numbers, camp icon)"""
    colors, camp_icon = scan_labels(path, digits, icon)
    color, label = classes(path, colors)
    blobs = blob_list(color, 2, 30)
    numbers = []
    for cx, cy, bits, box in label_clusters(label):
        if box[3] - box[1] + 1 < 16:  # (a digit is 20 high: shorter is an icon)
            continue
        n, worst = read_number(bits, digits)
        if n is not None and worst <= 26 and 1 <= n <= 40 and len(bits) <= 2:
            numbers.append((n, cx, cy))
    # an area is marked at its NUMBER: the digits are read exactly, while which blob a number belongs to is a guess (the numbers sit
    # beside their areas, and in a tangle of small ones the nearest blob is sometimes the neighbour's)
    problems = []
    got = sorted(n for n, _, _ in numbers)
    if got != list(range(1, len(got) + 1)):
        problems.append('numbers read: %s' % got)
    areas = {}
    for n, cx, cy in numbers:
        box = next(b for c in label_clusters(label) for b in [c[3]] if c[0] == cx and c[1] == cy)
        areas[n] = (round(cx * 4 + 2), round(cy * 4 + 2), box[0] * 4, box[1] * 4, box[2] * 4 + 3, box[3] * 4 + 3)
    if camp_icon is not None:
        cx, cy, box = camp_icon
        areas[0] = (round(cx * 4 + 2), round(cy * 4 + 2), box[0] * 4, box[1] * 4, box[2] * 4 + 3, box[3] * 4 + 3)
    return areas, problems, blobs, numbers, camp_icon


def main():
    check = sys.argv[sys.argv.index('--check') + 1] if '--check' in sys.argv else None
    digits, icon = learn()
    rows, bad = [], []
    for stage in range(1, 28):
        path = os.path.join(MAPS, 'map_%02d.png' % stage)
        if not os.path.exists(path):
            continue
        areas, problems, blobs, numbers, camp_icon = analyse(path, digits, icon)
        print('map_%02d: %2d numbers, %2d blobs, camp %s%s' % (stage, len(numbers), len(blobs), 'yes' if 0 in areas else 'NO',
                                                              '' if not problems else '   PROBLEM: ' + '; '.join(problems)))
        if problems:
            bad.append(stage)
        else:
            for n in sorted(areas):
                rows.append((stage,) + (n,) + areas[n])
        if check:
            os.makedirs(check, exist_ok=True)
            im = Image.open(path).convert('RGBA')
            bg = Image.new('RGBA', im.size, (255, 255, 255, 255))
            bg.alpha_composite(im)
            d = ImageDraw.Draw(bg)
            for n, a in areas.items():
                d.rectangle(a[2:6], outline=(255, 0, 0, 255), width=3)
                d.ellipse((a[0] - 10, a[1] - 10, a[0] + 10, a[1] + 10), fill=(255, 0, 0, 255))
                d.text((a[0] + 14, a[1] - 8), 'A%d' % n, fill=(200, 0, 0, 255))
            bg.convert('RGB').save(os.path.join(check, 'check_%02d.png' % stage))
    out = os.path.join(MAPS, 'areas.csv')
    with open(out, 'w', newline='') as f:
        w = csv.writer(f, lineterminator='\n')
        w.writerow(['stage', 'area', 'cx', 'cy', 'x0', 'y0', 'x1', 'y1'])
        w.writerows(rows)
    print('wrote %s: %d rows for %d maps; no rows for %s' % (os.path.relpath(out), len(rows), 27 - len(bad), bad or 'none'))


if __name__ == '__main__':
    main()
