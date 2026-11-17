"""Top-down SVG previews of room files and layouts, for review (never shipped).

    python -I tools/rooms/preview.py room --room <room.json> --prefabs <Server/Prefabs> [--prefabs <another>] --out <svg>
    python -I tools/rooms/preview.py rooms --rooms <folder of room files> --prefabs <...> --out <folder>
    python -I tools/rooms/preview.py layout --layout <dump.json> --rooms <folder> --prefabs <...> --out <svg>

A room is drawn from one variant of its pool (`--variant`, default the first): north is up, one block a square. A
column whose floor sits at the doors' level is a floor tile, a column with a block at head height is wall (darker),
a column whose floor is lower or higher is tinted blue or orange by how far, and a column of carved air with nothing
under it is hatched. Doors are arrows on their cell, pointing out, labelled with their Kind (hollow and marked exit
when ExitOnly: the layout leaves the room by it, never enters); the room's Box is the
dashed outline; trigger volumes are blue outlines with their names; spawn markers are red dots.

A room with an Overlay draws its base piece, then the overlay over it: each overlay block an inset square in its
block's colour outlined in magenta, or crossed out in red when it sits on a cell the base fills (which build_room.py
refuses, so a red cross means the overlay prefab was edited by hand or the base piece changed), a column where the
overlay writes air over the base (a door cut into a sealed piece) in orange dashes, and the overlay's volumes and
markers. Pass the folder the overlay prefab was built into as another --prefabs.

`rooms` writes three files per room file: <Id>.svg, <Id>.caps.svg (a Cap room of the door's Kind joined at every door,
ghosted) and <Id>.joins.svg (another room of the door's Kind joined at every door, ghosted), and index.html showing
them side by side. Joining follows PD1's rule: door B meets door A when B faces the other way and B's cell is one
step out from A's.

`layout` reads a layout dump: {"Rooms": [{"Room": <id>, "Origin": {X, Y, Z}, "Rotation": 0-3, "Variant": n}]}, each
room's anchor at Origin, turned by Rotation engine steps.
"""
import argparse
import html
import os
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import blockinfo  # noqa: E402
import hubkit  # noqa: E402
import iso  # noqa: E402
import prefabio as pio  # noqa: E402

ZC_SERVER = os.path.normpath(os.path.join(HERE, "..", "..", "zc-instance", "src", "main", "resources", "Server"))

CELL = 10
MARGIN = 40
FAMILY_COLOURS = [
    ("Rock_Quartzite", (185, 178, 165)),
    ("Rock_Stone", (138, 138, 138)),
    ("Rock_Slate", (90, 95, 105)),
    ("Rock_Shale", (110, 100, 95)),
    ("Rock_Sandstone", (205, 175, 120)),
    ("Rock_Basalt", (70, 70, 75)),
    ("Rock_", (125, 125, 130)),
    ("Soil_", (155, 123, 85)),
    ("Wood_", (139, 90, 43)),
    ("Metal_", (111, 127, 143)),
    ("Ore_", (150, 110, 170)),
    ("Crystal", (155, 89, 182)),
    ("Plant_", (76, 175, 80)),
    ("Furniture_", (201, 162, 39)),
    ("Deco_", (201, 162, 39)),
    ("Bench_", (201, 162, 39)),
]


def colour_of(name):
    for prefix, rgb in FAMILY_COLOURS:
        if name.startswith(prefix):
            return rgb
    h = zlib.crc32(name.encode("utf-8"))
    return (80 + h % 120, 80 + (h >> 8) % 120, 80 + (h >> 16) % 120)


def kind_colour(kind):
    h = zlib.crc32(kind.encode("utf-8"))
    palette = [(214, 39, 40), (31, 119, 180), (44, 160, 44), (148, 103, 189), (255, 127, 14), (23, 190, 207),
               (227, 119, 194), (188, 189, 34)]
    return palette[h % len(palette)]


def hexrgb(rgb):
    return "#%02x%02x%02x" % tuple(max(0, min(255, int(round(c)))) for c in rgb)


def shade(rgb, f):
    return tuple(c * f for c in rgb)


def mix(rgb, other, t):
    return tuple(a * (1 - t) + b * t for a, b in zip(rgb, other))


# ---------------------------------------------------------------------------------------------------------------
# Plan of one prefab variant


def plan(prefab, level=0):
    """(x, z) -> (kind, block name, height) for one prefab variant, seen from above at the doors' floor level."""
    columns = {}
    for (x, y, z), b in prefab.blocks.items():
        columns.setdefault((x, z), {})[y] = b.get("name")
    out = {}
    for (x, z), ys in columns.items():
        solid = {y: n for y, n in ys.items() if n != pio.EMPTY_BLOCK}

        def walkable(y):
            return y in solid and (y + 1) not in solid and (y + 2) not in solid

        if walkable(level):
            out[(x, z)] = ("floor", solid[level], 0)
        elif (level + 1) in solid or (level + 2) in solid:
            out[(x, z)] = ("wall", solid.get(level + 1) or solid.get(level + 2), 0)
        else:
            heights = sorted((y for y in solid if walkable(y)), key=lambda y: (abs(y - level), y))
            if heights:
                y = heights[0]
                out[(x, z)] = ("level", solid[y], y - level)
            elif solid:
                top = max(solid)
                out[(x, z)] = ("roof", solid[top], top - level)
            else:
                out[(x, z)] = ("air", pio.EMPTY_BLOCK, 0)
    return out


def tile_fill(kind, name, h):
    base = colour_of(name)
    if kind == "floor":
        return hexrgb(mix(base, (255, 255, 255), 0.25))
    if kind == "wall":
        return hexrgb(shade(base, 0.45))
    if kind == "level":
        t = min(0.7, 0.12 * abs(h))
        return hexrgb(mix(base, (60, 110, 220) if h < 0 else (240, 150, 40), t))
    if kind == "roof":
        return hexrgb(mix(base, (255, 255, 255), 0.7))
    return None


# ---------------------------------------------------------------------------------------------------------------
# SVG


class Svg:
    """Collects elements in world block coordinates (x east, z south) and writes them scaled, north up."""

    def __init__(self):
        self.items = []
        self.lo = [None, None]
        self.hi = [None, None]

    def grow(self, x, z):
        for i, v in enumerate((x, z)):
            self.lo[i] = v if self.lo[i] is None else min(self.lo[i], v)
            self.hi[i] = v if self.hi[i] is None else max(self.hi[i], v)

    def rect(self, x, z, w=1, d=1, fill="none", stroke=None, dash=False, opacity=1.0, width=1.0):
        self.grow(x, z)
        self.grow(x + w, z + d)
        self.items.append(("rect", x, z, w, d, fill, stroke, dash, opacity, width))

    def text(self, x, z, s, size=9, colour="#222", anchor="start"):
        self.grow(x, z)
        self.items.append(("text", x, z, s, size, colour, anchor))

    def poly(self, pts, fill, stroke="#000", opacity=1.0):
        for p in pts:
            self.grow(*p)
        self.items.append(("poly", pts, fill, stroke, opacity))

    def circle(self, x, z, r, fill, stroke="#000"):
        self.grow(x, z)
        self.items.append(("circle", x, z, r, fill, stroke))

    def line(self, x1, z1, x2, z2, stroke, width=1.0, dash=False):
        self.grow(x1, z1)
        self.grow(x2, z2)
        self.items.append(("line", x1, z1, x2, z2, stroke, width, dash))

    def render(self, title, legend=()):
        if self.lo[0] is None:
            self.grow(0, 0)
        ox, oz = self.lo
        w = max((self.hi[0] - ox) * CELL + 2 * MARGIN + 260, 560)
        h = max((self.hi[1] - oz) * CELL, 14 * (len(legend) + 2)) + 2 * MARGIN + 30

        def X(v):
            return "%.1f" % ((v - ox) * CELL + MARGIN)

        def Z(v):
            return "%.1f" % ((v - oz) * CELL + MARGIN + 30)

        out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
               'font-family="sans-serif">' % (w, h, w, h),
               '<rect x="0" y="0" width="%d" height="%d" fill="#ffffff"/>' % (w, h),
               '<defs><pattern id="hatch" width="4" height="4" patternUnits="userSpaceOnUse">'
               '<path d="M0,4 L4,0" stroke="#bbb" stroke-width="1"/></pattern></defs>',
               '<text x="%d" y="20" font-size="14" fill="#111">%s</text>' % (MARGIN, html.escape(title)),
               '<text x="%d" y="36" font-size="11" fill="#555">North is up, one block a square</text>' % MARGIN]
        for it in self.items:
            kind = it[0]
            if kind == "rect":
                _, x, z, ww, dd, fill, stroke, dash, opacity, width = it
                attrs = 'x="%s" y="%s" width="%.1f" height="%.1f" fill="%s"' % (X(x), Z(z), ww * CELL, dd * CELL, fill)
                if stroke:
                    attrs += ' stroke="%s" stroke-width="%.1f"' % (stroke, width)
                if dash:
                    attrs += ' stroke-dasharray="6,4"'
                if opacity != 1.0:
                    attrs += ' opacity="%.2f"' % opacity
                out.append("<rect %s/>" % attrs)
            elif kind == "text":
                _, x, z, s, size, colour, anchor = it
                out.append('<text x="%s" y="%s" font-size="%d" fill="%s" text-anchor="%s">%s</text>' % (
                    X(x), Z(z), size, colour, anchor, html.escape(s)))
            elif kind == "poly":
                _, pts, fill, stroke, opacity = it
                out.append('<polygon points="%s" fill="%s" stroke="%s" stroke-width="0.8"%s/>' % (
                    " ".join("%s,%s" % (X(p[0]), Z(p[1])) for p in pts), fill, stroke,
                    ' opacity="%.2f"' % opacity if opacity != 1.0 else ""))
            elif kind == "circle":
                _, x, z, r, fill, stroke = it
                out.append('<circle cx="%s" cy="%s" r="%.1f" fill="%s" stroke="%s" stroke-width="0.8"/>' % (
                    X(x), Z(z), r * CELL, fill, stroke))
            elif kind == "line":
                _, x1, z1, x2, z2, stroke, width, dash = it
                out.append('<line x1="%s" y1="%s" x2="%s" y2="%s" stroke="%s" stroke-width="%.1f"%s/>' % (
                    X(x1), Z(z1), X(x2), Z(z2), stroke, width, ' stroke-dasharray="4,3"' if dash else ""))
        lx = (self.hi[0] - ox) * CELL + MARGIN + 20
        ly = MARGIN + 30
        for i, (swatch, label) in enumerate(legend):
            y = ly + i * 14
            out.append('<rect x="%d" y="%d" width="10" height="10" fill="%s" stroke="#444" stroke-width="0.5"/>' % (
                lx, y - 9, swatch))
            out.append('<text x="%d" y="%d" font-size="10" fill="#222">%s</text>' % (lx + 14, y, html.escape(label)))
        out.append("</svg>")
        return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------------------------------------------
# Drawing rooms


def place(cell, origin, turns):
    c = pio.rotate_cell(cell, turns)
    return (c[0] + origin[0], c[1] + origin[1], c[2] + origin[2])


def door_level(room):
    doors = room.get("Doors") or []
    if not doors:
        return 0
    ys = sorted(d["At"]["Y"] for d in doors)
    return ys[len(ys) // 2]


def overlay_columns(base, overlay):
    """(x, z) -> (top overlay block name, whether any overlay block in the column sits on a cell the base fills,
    whether the overlay cuts the column: writes air over a cell the base fills, a door cut into a sealed piece)."""
    columns = {}
    for (x, y, z), b in sorted(overlay.blocks.items(), key=lambda kv: kv[0][1]):
        name = b.get("name")
        under = base.blocks.get((x, y, z)) if base is not None else None
        # A block in the base's water (air under a fluid) is a spec's declared Submerged cell, never a clash.
        filled = base is not None and (under is None or under.get("name") != pio.EMPTY_BLOCK)
        top, before, cut = columns.get((x, z), (None, False, False))
        if name == pio.EMPTY_BLOCK:
            if filled:
                columns[(x, z)] = (top, before, True)
            continue
        columns[(x, z)] = (name, before or filled, cut)
    return columns


def draw_overlay(svg, base, overlay, origin, turns, materials=None):
    for (x, z), (name, clash, cut) in sorted(overlay_columns(base, overlay).items()):
        wx, _, wz = place((x, 0, z), origin, turns)
        if cut:
            svg.rect(wx, wz, fill="#ffe0b2", stroke="#ef6c00", width=1.6, dash=True)
            if name is None:
                continue
        if clash:
            svg.rect(wx, wz, fill="#ffdddd", stroke="#d62728", width=1.6)
            svg.line(wx, wz, wx + 1, wz + 1, "#d62728", 1.6)
            svg.line(wx + 1, wz, wx, wz + 1, "#d62728", 1.6)
            continue
        svg.rect(wx + 0.15, wz + 0.15, 0.7, 0.7, fill=hexrgb(colour_of(name)), stroke="#c2185b", width=1.4)
        if materials is not None:
            materials[name] = materials.get(name, 0) + 1


def draw_room(svg, room, prefab, origin=(0, 0, 0), turns=0, ghost=False, label=None, materials=None, overlay=None):
    """Draw a room file's plan (from `prefab`, may be None), its overlay (may be None), Box, doors and logic at
    origin, turned."""
    opacity = 0.35 if ghost else 1.0
    level = door_level(room)
    if prefab is not None:
        for (x, z), (kind, name, h) in sorted(plan(prefab, level).items()):
            wx, _, wz = place((x, 0, z), origin, turns)
            fill = tile_fill(kind, name, h)
            if fill is None:
                svg.rect(wx, wz, fill="url(#hatch)", opacity=opacity)
                continue
            svg.rect(wx, wz, fill=fill, opacity=opacity)
            if materials is not None and kind in ("floor", "wall"):
                materials[name] = materials.get(name, 0) + 1
    if overlay is not None and not ghost:
        draw_overlay(svg, prefab, overlay, origin, turns, materials)
    box = room.get("Box")
    if box:
        a = place(pio.xyz_tuple(box["Min"]), origin, turns)
        b = place(pio.xyz_tuple(box["Max"]), origin, turns)
        x0, x1 = min(a[0], b[0]), max(a[0], b[0])
        z0, z1 = min(a[2], b[2]), max(a[2], b[2])
        svg.rect(x0, z0, x1 - x0 + 1, z1 - z0 + 1, stroke="#555" if not ghost else "#999", dash=True, width=1.2)
        if label:
            svg.text(x0, z0 - 0.4, label, size=9, colour="#333" if not ghost else "#888")
    for d in room.get("Doors") or []:
        at = place(pio.xyz_tuple(d["At"]), origin, turns)
        facing = pio.rotate_facing(d["Facing"], turns)
        draw_door(svg, at, facing, d.get("Kind", "?"), ghost, bool(d.get("ExitOnly")))
    if prefab is not None and not ghost:
        draw_entities(svg, prefab, origin, turns)
    if overlay is not None and not ghost:
        draw_entities(svg, overlay, origin, turns)


def draw_door(svg, at, facing, kind, ghost=False, exit_only=False):
    """A door's arrow, pointing out; an exit-only door (one the layout never enters by) is hollow and labelled exit."""
    vx, vz = pio.FACING_VEC[facing]
    cx, cz = at[0] + 0.5, at[2] + 0.5
    px, pz = -vz, vx
    tip = (cx + vx * 1.2, cz + vz * 1.2)
    base1 = (cx - vx * 0.3 + px * 0.7, cz - vz * 0.3 + pz * 0.7)
    base2 = (cx - vx * 0.3 - px * 0.7, cz - vz * 0.3 - pz * 0.7)
    col = hexrgb(kind_colour(kind))
    svg.poly([tip, base1, base2], "#ffffff" if exit_only else col, col if exit_only else "#000",
             opacity=0.45 if ghost else 1.0)
    if not ghost:
        svg.text(cx + vx * 2.2, cz + vz * 2.2 + 0.3, kind + (" (exit)" if exit_only else ""), size=8, colour=col,
                 anchor="middle")


def draw_entities(svg, prefab, origin, turns):
    ax, ay, az = prefab.anchor
    for ent in prefab.entities:
        comps = ent.get("Components", {})
        pos = (comps.get("Transform") or {}).get("Position")
        if not pos:
            continue
        p = (pos["X"] - ax, pos["Y"] - ay, pos["Z"] - az)
        tv = comps.get("TriggerVolume")
        if tv:
            shape = tv.get("Shape") or {}
            name = tv.get("Name") or "volume"
            if shape.get("Type") == "Box":
                mn, mx = shape["Min"], shape["Max"]
                c1 = place((p[0] + mn["X"], 0, p[2] + mn["Z"]), origin, turns)
                c2 = place((p[0] + mx["X"], 0, p[2] + mx["Z"]), origin, turns)
                x0, x1 = min(c1[0], c2[0]), max(c1[0], c2[0])
                z0, z1 = min(c1[2], c2[2]), max(c1[2], c2[2])
                svg.rect(x0, z0, x1 - x0, z1 - z0, stroke="#1f5fbf", width=1.6)
                svg.text(x0 + 0.2, z1 - 0.3, name.split("_")[-1] or name, size=8, colour="#1f5fbf")
            else:
                r = float(shape.get("Radius", 1.0))
                c = place((p[0], 0, p[2]), origin, turns)
                svg.circle(c[0], c[2], r, "none", "#1f5fbf")
                svg.text(c[0], c[2], name, size=8, colour="#1f5fbf", anchor="middle")
        elif "SpawnMarkerComponent" in comps:
            c = place((p[0], 0, p[2]), origin, turns)
            svg.circle(c[0], c[2], 0.45, "#d62728", "#600")
        else:
            c = place((p[0], 0, p[2]), origin, turns)
            svg.circle(c[0], c[2], 0.25, "#777", "#333")


def legend_for(materials, extra=()):
    rows = [(tile_fill("floor", n, 0), n) for n, _ in sorted(materials.items(), key=lambda kv: (-kv[1], kv[0]))[:12]]
    rows.extend(extra)
    return rows


STANDARD_KEY = [
    ("#3c6edc", "lower floor (blue) / higher (orange)"),
    ("#ffffff", "hatched: carved air, no floor"),
    ("#1f5fbf", "trigger volume"),
    ("#d62728", "spawn marker"),
    ("#c2185b", "overlay block (magenta outline)"),
    ("#ffdddd", "overlay block on a filled cell (red cross)"),
    ("#ffe0b2", "door cut: overlay air over the base (orange dashes)"),
]


# ---------------------------------------------------------------------------------------------------------------
# Room lookups


class Library:
    """Room files by id, and the prefab variants their pools resolve to under one or more prefab roots."""

    def __init__(self, prefab_roots, rooms_dir=None):
        self.prefab_roots = list(prefab_roots)
        self.rooms = {}
        self.paths = {}
        self._variants = {}
        if rooms_dir:
            for dirpath, dirnames, filenames in os.walk(rooms_dir):
                dirnames.sort()
                for fn in sorted(filenames):
                    if fn.endswith(".json"):
                        rid = fn[:-5]
                        self.rooms[rid] = pio.load_json(os.path.join(dirpath, fn))
                        self.paths[rid] = os.path.join(dirpath, fn)

    def variant(self, room, index=0):
        return self._load(room.get("Prefab"), index)

    def overlay(self, room):
        """The room's Overlay prefab (one prefab), or None when it has none or it is not under any --prefabs."""
        return self._load(room.get("Overlay"), 0)

    def _load(self, pool, index):
        if not pool:
            return None
        key = (pool, index)
        if key not in self._variants:
            found = None
            for root in self.prefab_roots:
                files = pio.pool_files(root, pool)
                if files:
                    found = pio.load_prefab(files[min(index, len(files) - 1)])
                    break
            self._variants[key] = found
        return self._variants[key]


def join_transform(origin_a, turns_a, door_a, door_b):
    """Where room B goes (origin, turns) so its door_b meets room A's door_a, A placed at origin_a turned turns_a."""
    at_a = place(pio.xyz_tuple(door_a["At"]), origin_a, turns_a)
    facing_a = pio.rotate_facing(door_a["Facing"], turns_a)
    want = pio.OPPOSITE[facing_a]
    for t in range(4):
        if pio.rotate_facing(door_b["Facing"], t) == want:
            target = pio.step(at_a, facing_a)
            turned = pio.rotate_cell(pio.xyz_tuple(door_b["At"]), t)
            return (target[0] - turned[0], target[1] - turned[1], target[2] - turned[2]), t
    raise ValueError("no turn makes the doors face each other")


def is_cap(room):
    return "Cap" in (room.get("Roles") or [])


def render_room(lib, room, title, variant=0):
    svg = Svg()
    mats = {}
    draw_room(svg, room, lib.variant(room, variant), materials=mats, overlay=lib.overlay(room))
    svg.circle(0.5, 0.5, 0.3, "#000", "#000")
    return svg.render(title, legend_for(mats, STANDARD_KEY))


def render_joined(lib, rid, room, partners, title):
    """The room with, at every door, the partner chosen for it joined and ghosted."""
    svg = Svg()
    mats = {}
    for i, d in enumerate(room.get("Doors") or []):
        pick = partners(d, i)
        if pick is None:
            continue
        pid, prow, pdoor = pick
        origin, turns = join_transform((0, 0, 0), 0, d, pdoor)
        draw_room(svg, prow, lib.variant(prow), origin, turns, ghost=True, label=pid)
    draw_room(svg, room, lib.variant(room), materials=mats, overlay=lib.overlay(room))
    return svg.render(title, legend_for(mats, STANDARD_KEY))


def partner_finder(lib, rid, caps_only):
    """For a door, the room of its Kind to join: a cap, or (cycling by door index) another non-cap room."""
    by_kind = {}
    for oid in sorted(lib.rooms):
        o = lib.rooms[oid]
        if is_cap(o) != caps_only:
            continue
        for od in o.get("Doors") or []:
            by_kind.setdefault(od.get("Kind"), []).append((oid, o, od))

    def find(door, index):
        options = [p for p in by_kind.get(door.get("Kind"), []) if caps_only or p[0] != rid]
        if not options:
            return None
        return options[index % len(options)]
    return find


def write_rooms(lib, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    for rid in sorted(lib.rooms):
        room = lib.rooms[rid]
        pio.write_text(os.path.join(out_dir, rid + ".svg"), render_room(lib, room, rid))
        pio.write_text(os.path.join(out_dir, rid + ".caps.svg"),
                       render_joined(lib, rid, room, partner_finder(lib, rid, True), rid + ": a cap at every door"))
        pio.write_text(os.path.join(out_dir, rid + ".joins.svg"),
                       render_joined(lib, rid, room, partner_finder(lib, rid, False),
                                     rid + ": a neighbour at every door"))
        rows.append(rid)
    page = ["<!doctype html>", '<meta charset="utf-8">', "<title>Room previews</title>",
            "<style>body{font-family:sans-serif;background:#fff;color:#111;margin:16px}"
            "section{margin:0 0 32px}img{max-width:100%;border:1px solid #ccc;margin:4px;vertical-align:top}"
            "</style>", "<h1>Room previews</h1>"]
    for rid in rows:
        page.append('<section><h2>%s</h2><img src="%s.svg"><img src="%s.caps.svg"><img src="%s.joins.svg">'
                    "</section>" % (html.escape(rid), rid, rid, rid))
    pio.write_text(os.path.join(out_dir, "index.html"), "\n".join(page) + "\n")
    return len(rows)


def render_layout(lib, layout, title):
    svg = Svg()
    mats = {}
    for i, row in enumerate(layout.get("Rooms", [])):
        rid = row["Room"]
        room = lib.rooms.get(rid)
        if room is None:
            raise SystemExit("layout names a room not under --rooms: %s" % rid)
        origin = pio.xyz_tuple(row.get("Origin", {"X": 0, "Y": 0, "Z": 0}))
        turns = int(row.get("Rotation", 0)) % 4
        draw_room(svg, room, lib.variant(room, int(row.get("Variant", 0))), origin, turns,
                  label="%d %s" % (i, rid), materials=mats, overlay=lib.overlay(room))
    return svg.render(title, legend_for(mats, STANDARD_KEY))


# ---------------------------------------------------------------------------------------------------------------
# The temple hub: its districts and the whole hub, from above and from the corners


def hub_districts(folders):
    """{id: district file} under `folders` (one HubDistricts folder or a list, CommonLib's and a consumer's), read
    whole; a later folder's file of the same id wins, as a later pack's does."""
    out = {}
    for folder in ([folders] if isinstance(folders, str) else folders):
        out.update(hubkit.district_files(folder))
    return out


def hub_layout(districts):
    """[(lot, district id, placeholder)] as HubLayout.assign lays enabled districts out: a claimed lot first by id,
    then every empty lot by the first district filling empty lots of its size."""
    lots = hubkit.lots()
    held = {}
    fillers = []
    for did in sorted(districts, key=str.lower):
        d = districts[did]
        if d.get("Enabled") is False:
            continue
        lot = (d.get("Lot") or {})
        if lot.get("Id"):
            key = lot["Id"].lower()
            if key not in held and any(l[0].lower() == key for l in lots):
                held[key] = did
        elif lot.get("FillsEmpty"):
            fillers.append((did, lot.get("Size")))
    out = []
    for lot in lots:
        if lot[0].lower() in held:
            out.append((lot, held[lot[0].lower()], False))
            continue
        for did, size in fillers:
            if size is None or size.lower() == lot[1].lower():
                out.append((lot, did, True))
                break
    return out


def district_cells(lib_roots, district):
    """The district's prefab cells {(x, y, z): (name, rotation)} from its Prefab.Base and Overlays, its floor block."""
    cells = {}
    prefab = district.get("Prefab") or {}
    for key in [prefab.get("Base")] + list(prefab.get("Overlays") or []):
        if not key:
            continue
        for root in lib_roots:
            files = pio.pool_files(root, key)
            if files:
                p = pio.load_prefab(files[0])
                for c, b in p.blocks.items():
                    if b.get("filler"):
                        continue
                    if b.get("name") == pio.EMPTY_BLOCK:
                        cells.pop(c, None)
                    else:
                        cells[c] = (b["name"], int(b.get("rotation", 0)))
                break
    return cells, prefab.get("Floor")


def lot_scene(scene, lot, district, cells, floor):
    """A district on its lot, its floor out to the middle of the streets first, in world blocks."""
    lid, size, cx, cz, face, (fx0, fz0, fx1, fz1) = lot
    if floor:
        for x in range(fx0, fx1 + 1):
            for z in range(fz0, fz1 + 1):
                scene.add((x, 0, z), floor)
    scene.add_cells(cells, (cx, 0, cz), hubkit.TURNS[face])


def write_hub(out_dir, districts_dirs, prefab_roots, server_roots, png=False, views=("SE", "NW")):
    """Pictures of every district with a prefab (from above with its spots, and from `views`) and of the whole hub,
    from one HubDistricts folder or a list of them."""
    os.makedirs(out_dir, exist_ok=True)
    info = blockinfo.BlockInfo(server_roots)
    districts = hub_districts(districts_dirs)
    written = []

    def emit(name, svg):
        path = os.path.join(out_dir, name + ".svg")
        pio.write_text(path, svg)
        written.append(path)
        if png:
            iso.png(path, path[:-4] + ".png")

    for did, d in sorted(districts.items()):
        cells, floor = district_cells(prefab_roots, d)
        if not cells:
            continue
        lot = d.get("Lot") or {}
        size = lot.get("Size") or "Large"
        span = hubkit.SPANS.get(size, 64)
        lo = -(span // 2)
        top = iso.TopDown(info)
        top.add_cells(cells)
        marks = [(int((a.get("At") or {}).get("X", 0)), int((a.get("At") or {}).get("Z", 0)), a.get("Name", "?"),
                  "#ffd54f") for a in d.get("Anchors") or []]
        emit(did + ".top", top.svg(scale=9.0 if span <= 32 else 7.0, title=did + ": from above",
                                   subtitle="%s lot, %d blocks square; its front (the way in) is the top edge; "
                                            "gold dots are its named spots" % (size, span),
                                   marks=marks, outlines=[(lo, lo, lo + span - 1, lo + span - 1, "#555", True, "")]))
        for view in views:
            sc = iso.Scene(info)
            sc.add_cells(cells)
            emit("%s.%s" % (did, view), sc.svg(view=view, scale=12.0 if span <= 32 else 8.0,
                                                title="%s: from the %s" % (did, view),
                                                subtitle="Front (the way in) faces north"))
            if span > 32:
                # A large lot's middle, closer: where its players land or its way in leads.
                emit("%s.%s.close" % (did, view), sc.svg(
                    view=view, scale=16.0, title="%s: its middle, from the %s" % (did, view),
                    subtitle="The middle 40 blocks square", clip=((-20, -20), (19, 19))))
    placed = hub_layout(districts)
    whole_top = iso.TopDown(info)
    whole = iso.Scene(info)
    outlines = []
    for lot, did, placeholder in placed:
        cells, floor = district_cells(prefab_roots, districts[did])
        lot_scene(whole_top, lot, districts[did], cells, floor)
        lot_scene(whole, lot, districts[did], cells, floor)
        lid, size, cx, cz, face = lot[:5]
        half = hubkit.SPANS[size] // 2
        outlines.append((cx - half, cz - half, cx - half + hubkit.SPANS[size] - 1, cz - half + hubkit.SPANS[size] - 1,
                         "#7a1f1f" if not placeholder else "#444", True, "%s, faces %s" % (lid, face[0])))
    emit("Hub.top", whole_top.svg(scale=3.0, title="The temple hub from above",
                                  subtitle="Every lot with what stands on it today; dashed: each lot's own ground",
                                  outlines=outlines))
    for view in views[:1]:
        emit("Hub.%s" % view, whole.svg(view=view, scale=3.2, title="The temple hub from the %s" % view))
    return written


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    h = sub.add_parser("hub")
    h.add_argument("--districts", action="append", default=[],
                   help="a consumer's HubDistricts folder (repeatable; CommonLib's own is always read first)")
    h.add_argument("--prefabs", action="append", default=[], help="a Server/Prefabs folder (repeatable; CommonLib's "
                   "own is always read)")
    h.add_argument("--server", action="append", default=[], help="a pack's Server folder whose items name blocks "
                   "(repeatable; vanilla's and CommonLib's are always read)")
    h.add_argument("--views", default="SE,NW", help="the corners to draw from, of SE, SW, NW and NE")
    h.add_argument("--png", action="store_true", help="also write each picture as a PNG (Edge or Chrome headless)")
    h.add_argument("--out", required=True)
    a = sub.add_parser("room")
    a.add_argument("--room", required=True)
    a.add_argument("--prefabs", action="append", default=[])
    a.add_argument("--variant", type=int, default=0)
    a.add_argument("--out", required=True)
    b = sub.add_parser("rooms")
    b.add_argument("--rooms", required=True)
    b.add_argument("--prefabs", action="append", default=[])
    b.add_argument("--out", required=True)
    c = sub.add_parser("layout")
    c.add_argument("--layout", required=True)
    c.add_argument("--rooms", required=True)
    c.add_argument("--prefabs", action="append", default=[])
    c.add_argument("--out", required=True)
    args = ap.parse_args(argv)
    if args.mode == "hub":
        vanilla = blockinfo.vanilla_server(HERE)
        prefab_roots = [os.path.join(ZC_SERVER, "Prefabs")] + list(args.prefabs)
        servers = [vanilla, ZC_SERVER] + list(args.server)
        views = tuple(v.strip().upper() for v in args.views.split(",") if v.strip())
        districts = [os.path.join(ZC_SERVER, "ZiggfreedCommon", "HubDistricts")] + list(args.districts)
        written = write_hub(args.out, districts, prefab_roots, servers, args.png, views)
        print("wrote %d pictures to %s" % (len(written), args.out))
    elif args.mode == "room":
        lib = Library(args.prefabs)
        room = pio.load_json(args.room)
        title = os.path.basename(args.room)[:-5]
        pio.write_text(args.out, render_room(lib, room, title, args.variant))
        print("wrote " + args.out)
    elif args.mode == "rooms":
        lib = Library(args.prefabs, args.rooms)
        n = write_rooms(lib, args.out)
        print("wrote previews of %d rooms to %s (index.html)" % (n, args.out))
    else:
        lib = Library(args.prefabs, args.rooms)
        layout = pio.load_json(args.layout)
        pio.write_text(args.out, render_layout(lib, layout, os.path.basename(args.layout)))
        print("wrote " + args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
