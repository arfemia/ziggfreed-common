"""Isometric pictures of a prefab or a whole layout, for review (never shipped): every block a shaded box in its own
colour, seen from one corner.

Standard library only; run every tool here with `python -I`. `preview.py iso` is the command; this is the renderer.

- A block is drawn as the boxes its hitbox fills (blockinfo), turned by its rotation, so stairs, slabs, paintings,
  pads and plants keep their shapes; its colour is the game's own computed one (TextureComputedColor). A block that
  gives light gets a soft glow of its light's colour.
- The view looks along (1, 1, 1) from the corner `view` names (SE, SW, NW or NE): the world is turned so that corner
  is +X +Z, and boxes are drawn far to near by their centre's X + Y + Z, which is the depth along the view for true
  isometric. A face a full opaque cube hides is never drawn.
- `png` writes the SVG as a PNG with Edge or Chrome headless, whichever is installed (False when none is).
"""
import html
import math
import os
import shutil
import subprocess
import tempfile

COS = math.cos(math.radians(30))
SIN = 0.5
VIEWS = {"SE": 0, "NE": 1, "NW": 2, "SW": 3}
BROWSERS = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
)
SKY = "#d9d2e9"


def turn_point(x, z, turns):
    """(x, z) turned `turns` quarter steps the engine's way ((x, z) -> (z, -x))."""
    for _ in range(turns % 4):
        x, z = z, -x
    return x, z


def turned_box(cell, box, turns):
    """A box in world units, its cell's offset added, turned for the view."""
    (x0, y0, z0), (x1, y1, z1) = box
    cx, cy, cz = cell
    a = turn_point(cx + x0, cz + z0, turns)
    b = turn_point(cx + x1, cz + z1, turns)
    return ((min(a[0], b[0]), cy + y0, min(a[1], b[1])), (max(a[0], b[0]), cy + y1, max(a[1], b[1])))


RUN = 16


def merge_tops(items):
    """Full cubes showing only their top, side by side along the view's X with one colour, as one long box of up to
    RUN cells, so a floor is a few hundred shapes instead of thousands. Each item gains its sort offset: a run sorts
    where its first cell would (its centre sum less the run's extra length)."""
    runs = {}
    out = []
    for box, rgb, hide in items:
        if hide == (False, True, True) and box[1][0] - box[0][0] == 1 and box[1][1] - box[0][1] == 1:
            runs.setdefault((box[0][1], box[0][2], rgb), []).append(box[0][0])
        else:
            out.append((box, rgb, hide, 0))
    for (y, z, rgb), xs in runs.items():
        xs.sort()
        start = prev = xs[0]
        for x in xs[1:] + [None]:
            if x is not None and x == prev + 1 and x - start < RUN:
                prev = x
                continue
            length = prev - start + 1
            out.append((((start, y, z), (prev + 1, y + 1, z + 1)), rgb, (False, True, True), length - 1))
            if x is not None:
                start = prev = x
    return out


def shade(rgb, f):
    return "#%02x%02x%02x" % tuple(max(0, min(255, int(round(c * f)))) for c in rgb)


class Scene:
    """Boxes to draw, with their colour and glow."""

    def __init__(self, info):
        self.info = info
        self.cells = {}

    def add(self, cell, name, rotation=0):
        if name and name != "Empty":
            self.cells[cell] = (name, rotation)

    def add_cells(self, cells, origin=(0, 0, 0), turns=0):
        """Cells {(x, y, z): (name, rotation)} placed at `origin`, turned `turns` engine steps (a rotation's yaw turns
        with them), as a lot turns a district."""
        ox, oy, oz = origin
        for (x, y, z), (name, rot) in cells.items():
            tx, tz = turn_point(x, z, turns)
            yaw = ((rot & 3) + turns) % 4
            self.add((tx + ox, y + oy, tz + oz), name, (rot & ~3) | yaw)

    def svg(self, view="SE", scale=10.0, title="", subtitle="", clip=None):
        """The picture as SVG text. `clip` ((x0, z0), (x1, z1)) keeps only cells inside it, in world blocks."""
        turns = VIEWS[view]
        solid = set()
        for cell, (name, _) in self.cells.items():
            if self.info.get(name).opaque:
                solid.add(cell)
        items = []
        glows = []
        for cell, (name, rot) in self.cells.items():
            if clip and not (clip[0][0] <= cell[0] <= clip[1][0] and clip[0][1] <= cell[2] <= clip[1][1]):
                continue
            blk = self.info.get(name)
            if blk.full:
                box = turned_box(cell, ((0.0, 0.0, 0.0), (1.0, 1.0, 1.0)), turns)
                # Which neighbours hide this cube's three faces seen from +X +Z +Y, in world terms.
                east = self._world_neighbour(cell, (1, 0, 0), turns)
                south = self._world_neighbour(cell, (0, 0, 1), turns)
                hide = (cell[0], cell[1] + 1, cell[2]) in solid, east in solid, south in solid
                if all(hide):
                    continue
                items.append((box, blk.rgb, hide))
            else:
                for b in blk.turned(rot):
                    box = turned_box(cell, b, turns)
                    items.append((box, blk.rgb, (False, False, False)))
            if blk.light:
                x, y, z = cell
                tx, tz = turn_point(x + 0.5, z + 0.5, turns)
                glows.append((tx, y + 0.6, tz, blk.light))
        items = merge_tops(items)
        items.sort(key=lambda it: (sum((it[0][0][i] + it[0][1][i]) for i in range(3)) - it[3], it[0][0][1]))
        return self._draw(items, glows, scale, title, subtitle)

    def _world_neighbour(self, cell, step, turns):
        """The world cell next to `cell` that the view's +X (or +Z) side faces."""
        # The view's +X axis is the world direction that turns onto +X; turning the world by `turns` maps world
        # (dx, dz) to view (dx', dz'), so invert: find the world step whose turned form is `step`.
        for wx, wz in ((1, 0), (0, 1), (-1, 0), (0, -1)):
            if turn_point(wx, wz, turns) == (step[0], step[2]):
                return (cell[0] + wx, cell[1], cell[2] + wz)
        return cell

    @staticmethod
    def _project(x, y, z, scale):
        return ((x - z) * COS * scale, ((x + z) * SIN - y) * scale)

    def _draw(self, items, glows, scale, title, subtitle):
        polys = []
        lo = [float("inf"), float("inf")]
        hi = [float("-inf"), float("-inf")]

        def pts(corners):
            out = []
            for c in corners:
                p = self._project(c[0], c[1], c[2], scale)
                lo[0], lo[1] = min(lo[0], p[0]), min(lo[1], p[1])
                hi[0], hi[1] = max(hi[0], p[0]), max(hi[1], p[1])
                out.append(p)
            return out

        for (mn, mx), rgb, hide, _ in items:
            x0, y0, z0 = mn
            x1, y1, z1 = mx
            if not hide[0]:
                polys.append((pts([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)]), shade(rgb, 1.0)))
            if not hide[1]:
                polys.append((pts([(x1, y1, z0), (x1, y1, z1), (x1, y0, z1), (x1, y0, z0)]), shade(rgb, 0.78)))
            if not hide[2]:
                polys.append((pts([(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)]), shade(rgb, 0.62)))
        if not polys:
            lo, hi = [0, 0], [1, 1]
        margin = 30
        head = 46 if title else 10
        ox, oy = -lo[0] + margin, -lo[1] + margin + head
        w = int(hi[0] - lo[0] + 2 * margin)
        h = int(hi[1] - lo[1] + 2 * margin + head)
        out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
               'font-family="sans-serif">' % (w, h, w, h),
               '<defs><radialGradient id="glow"><stop offset="0" stop-color="#fff" stop-opacity="0.9"/>'
               '<stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>',
               '<rect x="0" y="0" width="%d" height="%d" fill="%s"/>' % (w, h, SKY)]
        if title:
            out.append('<text x="%d" y="22" font-size="16" fill="#222">%s</text>' % (margin, html.escape(title)))
        if subtitle:
            out.append('<text x="%d" y="40" font-size="11" fill="#555">%s</text>' % (margin, html.escape(subtitle)))
        stroke = max(0.3, scale / 30.0)
        for p, fill in polys:
            out.append('<polygon points="%s" fill="%s" stroke="#000" stroke-opacity="0.18" stroke-width="%.2f"/>' % (
                " ".join("%.1f,%.1f" % (q[0] + ox, q[1] + oy) for q in p), fill, stroke))
        for gx, gy, gz, rgb in glows:
            p = self._project(gx, gy, gz, scale)
            out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s" opacity="0.35"/>' % (
                p[0] + ox, p[1] + oy, scale * 1.6, shade(rgb, 1.0)))
            out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#glow)" opacity="0.8"/>' % (
                p[0] + ox, p[1] + oy, scale * 0.9))
        out.append("</svg>")
        return "\n".join(out) + "\n"


class TopDown(Scene):
    """The same cells seen from straight above, north up: each column's highest block in its colour, lighter the
    higher it stands over the floor, with marks (named spots) and outlines (lots) drawn over it."""

    def svg(self, scale=6.0, title="", subtitle="", marks=(), outlines=(), floor_y=0):
        tops = {}
        for (x, y, z), (name, rot) in self.cells.items():
            blk = self.info.get(name)
            top = y + max(b[1][1] for b in blk.boxes)
            if (x, z) not in tops or top > tops[(x, z)][0]:
                tops[(x, z)] = (top, blk.rgb, blk.light)
        if not tops:
            tops[(0, 0)] = (0, (200, 200, 200), None)
        xs = [c[0] for c in tops]
        zs = [c[1] for c in tops]
        for o in outlines:
            xs += [o[0], o[2] + 1]
            zs += [o[1], o[3] + 1]
        x0, z0, x1, z1 = min(xs), min(zs), max(xs) + 1, max(zs) + 1
        margin, head = 24, 48 if title else 12
        w = int((x1 - x0) * scale + 2 * margin)
        h = int((z1 - z0) * scale + 2 * margin + head)

        def X(v):
            return (v - x0) * scale + margin

        def Z(v):
            return (v - z0) * scale + margin + head

        out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
               'font-family="sans-serif">' % (w, h, w, h),
               '<rect x="0" y="0" width="%d" height="%d" fill="%s"/>' % (w, h, SKY)]
        if title:
            out.append('<text x="%d" y="22" font-size="16" fill="#222">%s</text>' % (margin, html.escape(title)))
        if subtitle:
            out.append('<text x="%d" y="40" font-size="11" fill="#555">%s</text>' % (margin, html.escape(subtitle)))
        rows = {}
        for (x, z), (top, rgb, light) in tops.items():
            rise = top - (floor_y + 1)
            f = 1.0 + max(-0.4, min(0.35, 0.07 * rise))
            fill = shade(rgb, f) if f <= 1.0 else shade(tuple(c + (255 - c) * (f - 1.0) for c in rgb), 1.0)
            rows.setdefault((z, fill), []).append(x)
        for (z, fill), cols in sorted(rows.items()):
            cols.sort()
            start = prev = cols[0]
            for x in cols[1:] + [None]:
                if x is not None and x == prev + 1:
                    prev = x
                    continue
                out.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="%s"/>' % (
                    X(start), Z(z), (prev - start + 1) * scale, scale, fill))
                if x is not None:
                    start = prev = x
        for (x, z), (top, rgb, light) in tops.items():
            if light:
                out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s" opacity="0.45"/>' % (
                    X(x + 0.5), Z(z + 0.5), scale * 1.1, shade(light, 1.0)))
        for ox0, oz0, ox1, oz1, stroke, dash, label in outlines:
            out.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="none" stroke="%s" stroke-width="1.4"%s/>'
                       % (X(ox0), Z(oz0), (ox1 - ox0 + 1) * scale, (oz1 - oz0 + 1) * scale, stroke,
                          ' stroke-dasharray="6,4"' if dash else ""))
            if label:
                out.append('<text x="%.1f" y="%.1f" font-size="9" fill="%s" stroke="#fff" stroke-width="2.5" '
                           'paint-order="stroke">%s</text>' % (
                    X(ox0) + 3, Z(oz0) + 12, stroke, html.escape(label)))
        for mx, mz, label, colour in marks:
            out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s" stroke="#000" stroke-width="0.8"/>' % (
                X(mx + 0.5), Z(mz + 0.5), max(3.0, scale * 0.6), colour))
            out.append('<text x="%.1f" y="%.1f" font-size="11" fill="#000" stroke="#fff" stroke-width="3" '
                       'paint-order="stroke">%s</text>' % (X(mx + 0.5) + scale, Z(mz + 0.5) + 4, html.escape(label)))
        out.append("</svg>")
        return "\n".join(out) + "\n"


def svg_size(text):
    head = text.split(">", 1)[0]
    w = int(head.split('width="', 1)[1].split('"', 1)[0])
    h = int(head.split('height="', 1)[1].split('"', 1)[0])
    return w, h


def png(svg_path, png_path, scale=2):
    """Screenshot the SVG at its own size with a headless browser; False when none is installed."""
    browser = next((b for b in BROWSERS if os.path.isfile(b)), None)
    if browser is None:
        return False
    with open(svg_path, encoding="utf-8") as f:
        w, h = svg_size(f.read())
    profile = tempfile.mkdtemp(prefix="room-preview-")
    try:
        # Only our own local SVG is opened, so the renderer runs without its sandbox (a sandboxed shell refuses it).
        subprocess.run([browser, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars", "--no-first-run",
                        "--force-device-scale-factor=%d" % scale, "--user-data-dir=" + profile,
                        "--window-size=%d,%d" % (w, h), "--screenshot=" + os.path.abspath(png_path),
                        "file:///" + os.path.abspath(svg_path).replace(os.sep, "/")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    return os.path.isfile(png_path)
