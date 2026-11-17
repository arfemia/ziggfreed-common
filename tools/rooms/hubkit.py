"""A canvas for script-built hub districts: draw blocks into a lot-sized grid, then write the prefab the hub paints.

Standard library only; run every tool here with `python -I`. `build_hub.py` draws CommonLib's own districts with it;
a consumer's district script imports it the same way.

- Cells are anchor-relative, as the hub reads a district prefab: the anchor is the lot's centre on the floor's own
  layer (Y 0), a player walks Y 1, and the front faces North (-Z). A lot of span S holds X and Z from -S/2 to S/2 - 1;
  the canvas refuses a cell outside it, so a district never writes onto a street or a neighbour.
- `put` writes one block; a multi-cell block (a painting, a pad, a statue) also writes the filler cells its hitbox
  reaches (blockinfo.Block.fillers), since the hub's painter writes a prefab's stored cells and rebuilds no filler. A
  filler landing on a cell already drawn is refused, so two props never overlap.
- `facing` turns a block's front toward a compass side, and `put` refuses a turn the block's own VariantRotation does
  not offer (a Wall block such as the game's map panel turns only a quarter, a None block not at all). A block's front faces South (+Z) unturned (a painting hangs on
  the wall to its north and shows its picture south, Painting_4x2's box): one turn is the engine's yaw step (Rotation
  Ninety, (x, z) -> (z, -x)), which sends that front East.
- `write` keeps build_room.py's guard: a prefab is replaced only while it carries this tool's stamp and still hashes to
  it, so a prefab saved from the game's editor or edited by hand is never overwritten.
"""
import hashlib
import os
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prefabio as pio  # noqa: E402

STAMP_KEY = "zcGeneratedBy"
STAMP_VALUE = "tools/rooms/build_hub.py"
HASH_KEY = "zcHash"
HAND_BUILT_KEY = "zcHandBuilt"
FRONT = {"S": 0, "E": 1, "N": 2, "W": 3}


class DrawError(Exception):
    pass


def facing(front):
    """The rotation index that turns a block's front toward `front` (S, E, N or W)."""
    return FRONT[front]


class Canvas:
    def __init__(self, info, span, name, below=4, above=48):
        self.info = info
        self.span = span
        self.name = name
        self.lo = -(span // 2)
        self.hi = span - span // 2 - 1
        self.below = below
        self.above = above
        self.cells = {}
        self.fillers = set()
        self.problems = []

    # ---------------------------------------------------------------------------------------------------------
    # Drawing

    def inside(self, x, y, z):
        return self.lo <= x <= self.hi and self.lo <= z <= self.hi and -self.below <= y <= self.above

    def put(self, x, y, z, block, rotation=0, over=True):
        """Write `block` at (x, y, z), with its fillers. `over` False leaves a cell already drawn alone."""
        if not self.inside(x, y, z):
            raise DrawError("%s: %s at %s lies outside the lot" % (self.name, block, (x, y, z)))
        if (x, y, z) in self.fillers:
            raise DrawError("%s: %s at %s lands on another block's filler" % (self.name, block, (x, y, z)))
        if not over and (x, y, z) in self.cells:
            return False
        if block != "Empty" and not self.info.exists(block):
            self.problems.append("%s at %s is no block any loaded root ships" % (block, (x, y, z)))
        if block != "Empty" and rotation & 3:
            turns = self.info.get(block).variant_rotation
            if turns not in ("NESW", "UpDownNESW") and not (turns == "Wall" and rotation & 3 == 1):
                raise DrawError("%s: %s at %s is turned %d, which its VariantRotation %s does not allow" % (
                    self.name, block, (x, y, z), rotation & 3, turns))
        extra = self.info.get(block).fillers(rotation) if block != "Empty" else []
        for dx, dy, dz in extra:
            c = (x + dx, y + dy, z + dz)
            if not self.inside(*c):
                raise DrawError("%s: %s at %s reaches %s, outside the lot" % (self.name, block, (x, y, z), c))
            if c in self.cells and self.cells[c][0] != "Empty":
                raise DrawError("%s: %s at %s reaches %s, which holds %s" % (self.name, block, (x, y, z), c,
                                                                             self.cells[c][0]))
        self.cells[(x, y, z)] = (block, rotation, 0)
        for dx, dy, dz in extra:
            c = (x + dx, y + dy, z + dz)
            self.cells[c] = (block, rotation, pio.pack_filler(dx, dy, dz))
            self.fillers.add(c)
        return True

    def clear(self, x, y, z):
        """Air at (x, y, z): written as Empty, so the cell is carved even over the lot's floor."""
        self.cells[(x, y, z)] = ("Empty", 0, 0)

    def fill(self, x0, y0, z0, x1, y1, z1, block, rotation=0, over=True):
        """Every cell of the box, `block` a block id or a function (x, y, z) -> block id or None (skip)."""
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                for x in range(min(x0, x1), max(x0, x1) + 1):
                    b = block(x, y, z) if callable(block) else block
                    if b:
                        self.put(x, y, z, b, rotation, over)

    def get(self, x, y, z):
        c = self.cells.get((x, y, z))
        return None if c is None else c[0]

    # ---------------------------------------------------------------------------------------------------------
    # Output

    def blocks(self):
        """The cells as preview scenes read them: {(x, y, z): (name, rotation)} with fillers left out."""
        return {c: (n, r) for c, (n, r, f) in self.cells.items() if not f and n != "Empty"}

    def doc(self):
        entries = []
        for cell, (name, rot, filler) in self.cells.items():
            e = {"name": name}
            if rot:
                e["rotation"] = rot
            if filler:
                e["filler"] = filler
            entries.append((cell, e))
        doc = pio.prefab_doc((0, 0, 0), entries)
        doc[STAMP_KEY] = STAMP_VALUE
        doc["zcDistrict"] = self.name
        doc[HASH_KEY] = content_hash(doc)
        return doc


FLOOR_Y = 128
PITCH = 80
STREET = 16
SPANS = {"Small": 24, "Large": 64}


def lots():
    """The hub's fixed lots as HubLots builds them (zc-instance instance/hub/HubLots.java is the authority; this copy
    draws previews): [(id, size, centre x, centre z, facing, floor (x0, z0, x1, z1))], the Concourse first."""
    out = []
    half = PITCH // 2

    def whole(lid, cx, cz, face):
        out.append((lid, "Large", cx, cz, face, (cx - half, cz - half, cx + half - 1, cz + half - 1)))

    whole("Concourse", 0, 0, "North")
    whole("North", 0, -PITCH, "South")
    whole("East", PITCH, 0, "West")
    whole("South", 0, PITCH, "North")
    whole("West", -PITCH, 0, "East")
    quarter = PITCH // 4
    for cell, cx, cz in (("North_East", PITCH, -PITCH), ("South_East", PITCH, PITCH),
                         ("South_West", -PITCH, PITCH), ("North_West", -PITCH, -PITCH)):
        for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (1, 1), (-1, 1))):
            x0 = cx - half if sx < 0 else cx
            z0 = cz - half if sz < 0 else cz
            out.append(("%s_%d" % (cell, i + 1), "Small", cx + sx * quarter, cz + sz * quarter,
                        "South" if sz < 0 else "North", (x0, z0, x0 + half - 1, z0 + half - 1)))
    return out


TURNS = {"North": 0, "West": 1, "South": 2, "East": 3}


def pick(options, salt):
    """A stable weighted pick per cell: `options` [(block, weight), ...] read the same way on every run."""
    total = sum(w for _, w in options)

    def chooser(x, y, z):
        h = zlib.crc32(("%s|%d,%d,%d" % (salt, x, y, z)).encode("utf-8")) % total
        for block, w in options:
            h -= w
            if h < 0:
                return block
        return options[-1][0]
    return chooser


def content_hash(doc):
    body = {k: doc.get(k) for k in ("anchorX", "anchorY", "anchorZ", "blocks", "fluids", "entities")}
    return hashlib.sha1(pio.dumps(body).encode("utf-8")).hexdigest()


def may_overwrite(path):
    """None when the prefab may be (re)written, else why not."""
    if not os.path.isfile(path):
        return None
    try:
        doc = pio.load_json(path)
    except ValueError:
        return "it does not parse, so it was not written by this tool"
    if doc.get(HAND_BUILT_KEY):
        return "it is marked hand-built (%s)" % HAND_BUILT_KEY
    if doc.get(STAMP_KEY) != STAMP_VALUE:
        return "it carries no %s stamp, so it was built or saved by hand" % STAMP_KEY
    if doc.get(HASH_KEY) != content_hash(doc):
        return "its blocks changed since this tool wrote it"
    return None


def write(canvas, path):
    why = may_overwrite(path)
    if why:
        raise DrawError("refusing to overwrite %s: %s" % (path, why))
    return pio.write_json(path, canvas.doc())
