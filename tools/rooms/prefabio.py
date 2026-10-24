"""Read and write Hytale `.prefab.json` files, and the Y-axis turn math the room tools share.

Standard library only; run every tool here with `python -I`. Nothing in this module touches the disk except
`load_json`, `load_lenient_json`, `load_prefab`, `pool_files` and `write_json`.

Coordinates are anchor-relative block cells: a prefab file stores absolute cells plus `anchorX/Y/Z`, and the engine
subtracts the anchor on load (BsonPrefabBufferDeserializer.java:86-91, the same for fluids at :135-140 and entity
positions at :249-250). A paste puts the anchor at the paste position.

A turn is one step of the engine's `PrefabRotation` (ROTATION_0, _90, _180, _270 are turns 0 to 3). One turn maps a
cell (x, z) to (z, -x) (PrefabRotation.java:151-186, RotationExecutor_90), and blocks, child offsets and footprints all
use that one executor (PrefabBuffer.java:637-638). Compass: North is -Z, South +Z, East +X, West -X
(Vector3iUtil.java:52-55,65-68), so one turn sends North to West, West to South, South to East and East to North.
"""
import json
import os
import re

FACINGS = ("North", "East", "South", "West")
FACING_VEC = {"North": (0, -1), "East": (1, 0), "South": (0, 1), "West": (-1, 0)}
OPPOSITE = {"North": "South", "South": "North", "East": "West", "West": "East"}
EMPTY_BLOCK = "Empty"


# ---------------------------------------------------------------------------------------------------------------
# Turns


def rotate_xz(x, z, turns):
    """Turn a cell (x, z) about the anchor by `turns` engine steps (each (x, z) -> (z, -x))."""
    for _ in range(turns % 4):
        x, z = z, -x
    return x, z


def rotate_cell(cell, turns):
    """Turn an (x, y, z) cell about the anchor's vertical axis."""
    x, z = rotate_xz(cell[0], cell[2], turns)
    return (x, cell[1], z)


def rotate_facing(facing, turns):
    """The compass facing a door takes after `turns` engine steps."""
    vx, vz = rotate_xz(*FACING_VEC[facing], turns)
    for name, vec in FACING_VEC.items():
        if vec == (vx, vz):
            return name
    raise ValueError(facing)


def step(cell, facing, n=1):
    """The cell `n` steps from `cell` along `facing`."""
    vx, vz = FACING_VEC[facing]
    return (cell[0] + vx * n, cell[1], cell[2] + vz * n)


def rotate_box(box_min, box_max, turns):
    """The axis-aligned box a turned box covers, as (min, max)."""
    a = rotate_cell(box_min, turns)
    b = rotate_cell(box_max, turns)
    return ((min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2])),
            (max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2])))


def rotate_door(door, turns):
    """Turn a door dict ({At: {X, Y, Z}, Facing, Kind}) about the anchor; Kind is kept."""
    at = rotate_cell(xyz_tuple(door["At"]), turns)
    out = dict(door)
    out["At"] = xyz_dict(at)
    out["Facing"] = rotate_facing(door["Facing"], turns)
    return out


def turns_from_rotation(value):
    """Engine rotation text or number (0, "90", "ROTATION_180", ...) as turns 0 to 3 (PrefabRotation.valueOfExtended)."""
    text = str(value).strip()
    if text.startswith("ROTATION_"):
        text = text[len("ROTATION_"):]
    degrees = int(float(text))
    if degrees % 90 != 0:
        raise ValueError("rotation is not a multiple of 90: %r" % (value,))
    return (degrees // 90) % 4


def xyz_dict(cell):
    return {"X": int(cell[0]), "Y": int(cell[1]), "Z": int(cell[2])}


def xyz_tuple(d):
    return (int(d["X"]), int(d["Y"]), int(d["Z"]))


# ---------------------------------------------------------------------------------------------------------------
# Prefab files


class Prefab:
    """A loaded prefab with anchor-relative cells: `blocks` maps (x, y, z) to the block entry (its `name` and any
    `rotation`, `support`, `filler`, `components`), `fluids` likewise, `entities` keeps the raw entity documents."""

    def __init__(self, path, doc):
        self.path = path
        self.doc = doc
        self.anchor = (int(doc.get("anchorX", 0)), int(doc.get("anchorY", 0)), int(doc.get("anchorZ", 0)))
        ax, ay, az = self.anchor
        self.blocks = {}
        for b in doc.get("blocks") or []:
            self.blocks[(int(b["x"]) - ax, int(b["y"]) - ay, int(b["z"]) - az)] = b
        self.fluids = {}
        for f in doc.get("fluids") or []:
            self.fluids[(int(f["x"]) - ax, int(f["y"]) - ay, int(f["z"]) - az)] = f
        self.entities = list(doc.get("entities") or [])

    def bounds(self):
        """(min, max) over every block and fluid cell, anchor-relative, or None for an empty prefab."""
        cells = list(self.blocks) + list(self.fluids)
        if not cells:
            return None
        return ((min(c[0] for c in cells), min(c[1] for c in cells), min(c[2] for c in cells)),
                (max(c[0] for c in cells), max(c[1] for c in cells), max(c[2] for c in cells)))

    def is_empty(self):
        return not self.blocks and not self.fluids and not self.entities

    def name_at(self, cell):
        b = self.blocks.get(cell)
        return None if b is None else b.get("name")

    def solid_at(self, cell):
        """True when a block other than Empty stands at the cell."""
        n = self.name_at(cell)
        return n is not None and n != EMPTY_BLOCK


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def load_prefab(path):
    return Prefab(path, load_json(path))


def union_bounds(boxes):
    boxes = [b for b in boxes if b is not None]
    if not boxes:
        return None
    return ((min(b[0][0] for b in boxes), min(b[0][1] for b in boxes), min(b[0][2] for b in boxes)),
            (max(b[1][0] for b in boxes), max(b[1][1] for b in boxes), max(b[1][2] for b in boxes)))


PREFAB_SUFFIX = ".prefab.json"


def pool_files(prefabs_root, pool):
    """The `.prefab.json` files an engine prefab name resolves to, sorted by their path under `prefabs_root`.

    `A.B.*` is every prefab under folder A/B, subfolders included (PrefabLoader.java:52-90 walks the tree with no depth
    limit); a name without `.*` is the one file A/B.prefab.json (PrefabLoader.java:34-48)."""
    if pool.endswith(".*"):
        folder = os.path.join(prefabs_root, *pool[:-2].split("."))
        found = []
        if os.path.isdir(folder):
            for dirpath, dirnames, filenames in os.walk(folder):
                dirnames.sort()
                for name in filenames:
                    if name.endswith(PREFAB_SUFFIX):
                        full = os.path.join(dirpath, name)
                        found.append((os.path.relpath(full, prefabs_root).replace(os.sep, "/"), full))
        return [full for _, full in sorted(found)]
    single = os.path.join(prefabs_root, *pool.split(".")) + PREFAB_SUFFIX
    return [single] if os.path.isfile(single) else []


# ---------------------------------------------------------------------------------------------------------------
# Lenient worldgen JSON (node graphs are read by Gson in lenient mode: // and /* */ comments, trailing commas, a BOM)


def strip_lenient(text):
    """Remove comments and trailing commas outside strings, so the strict parser reads a lenient Gson file."""
    out = []
    i, n = 0, len(text)
    in_string = False
    while i < n:
        c = text[i]
        if in_string:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append(c)
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == "#":
            j = text.find("\n", i)
            i = n if j < 0 else j
        else:
            out.append(c)
            i += 1
    cleaned = "".join(out)
    return re.sub(r",(\s*[}\]])", r"\1", cleaned)


def load_lenient_json(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.loads(strip_lenient(f.read()))


# ---------------------------------------------------------------------------------------------------------------
# Deterministic output


def dumps(obj):
    """Sorted keys, two-space indent, UTF-8 text, one trailing newline: a re-run on the same input gives the same bytes."""
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def write_text(path, text):
    """Write `text` with LF line endings, only when the bytes differ (so an unchanged file keeps its timestamp)."""
    data = text.encode("utf-8")
    if os.path.isfile(path):
        with open(path, "rb") as f:
            if f.read() == data:
                return False
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return True


def write_json(path, obj):
    return write_text(path, dumps(obj))


def prefab_doc(anchor, blocks, fluids=(), entities=()):
    """A `.prefab.json` document (version 8, blockIdVersion 11, vanilla's values) from anchor-relative cells.

    `blocks` is an iterable of ((x, y, z), entry) where entry holds `name` and optionally `rotation`, `support`,
    `filler`, `components`; cells are written absolute (cell + anchor) in a stable y, z, x order."""
    ax, ay, az = anchor
    out_blocks = []
    for cell, entry in sorted(blocks, key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        b = {"x": cell[0] + ax, "y": cell[1] + ay, "z": cell[2] + az, "name": entry["name"]}
        for key in ("rotation", "support", "filler", "components"):
            if entry.get(key) not in (None, 0, {}, []):
                b[key] = entry[key]
        out_blocks.append(b)
    doc = {"version": 8, "blockIdVersion": 11, "anchorX": ax, "anchorY": ay, "anchorZ": az, "blocks": out_blocks}
    fl = [{"x": c[0] + ax, "y": c[1] + ay, "z": c[2] + az, "name": e["name"], "level": e.get("level", 8)}
          for c, e in sorted(fluids, key=lambda kv: (kv[0][1], kv[0][2], kv[0][0]))]
    if fl:
        doc["fluids"] = fl
    ents = list(entities)
    if ents:
        doc["entities"] = ents
    return doc
