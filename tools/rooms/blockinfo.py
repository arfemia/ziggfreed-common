"""What a preview needs to know about a block id: its colour, the boxes it fills, whether it glows, and the filler
cells a multi-cell block writes. Read from item files (vanilla's and any pack's), never from the engine.

Standard library only; run every tool here with `python -I`.

- A block id is an item id under `Server/Item/Items/` (any depth). Its `BlockType` is merged over its `Parent`'s, key
  by key and group by group, the way a child item inherits.
- The colour is the block's `TextureComputedColor`, the one colour the game computes from its textures (or
  `ParticleColor`, else a hashed grey for an id no root ships), times its first `Tint` for a block the game tints
  (grass, moss), whose textures are grey.
- The boxes are its `HitboxType`'s `Boxes` (`Server/Item/Block/Hitboxes/**/<Type>.json`), the full cube when it names
  none, turned by the cell's rotation the engine's way (BlockBoundingBoxes.rotate90Y: one yaw step maps (x, z) to
  (z, 1 - x)).
- A prefab names a block in a state as `*<Id>_State_Definitions_<State>`; the state's colour is the block's own.
- `fillers` lists the cells a block's turned boxes reach beyond its own, as FillerBlockUtil.forEachFillerBlock walks
  them (the union box, each bound stretched to the cell it touches): what a painted prefab must list itself, since the
  generator rebuilds no filler.
"""
import json
import os
import zlib

FULL = ((0.0, 0.0, 0.0), (1.0, 1.0, 1.0))
STATE_MARK = "_State_Definitions_"


def find_reference(start, name, probe):
    """reference/shared-source/release/<name> in a folder at or above `start`, when `probe` exists under it."""
    d = os.path.abspath(start)
    while True:
        cand = os.path.join(d, "reference", "shared-source", "release", name)
        if os.path.exists(os.path.join(cand, *probe)):
            return cand
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def vanilla_server(start=None):
    """Vanilla's `Server` folder: HYTALE_ASSETS/Server, else the shared source's, found above `start`."""
    env = os.environ.get("HYTALE_ASSETS")
    if env and os.path.isdir(os.path.join(env, "Server")):
        return os.path.join(env, "Server")
    found = find_reference(start or os.path.dirname(os.path.abspath(__file__)), "HytaleAssets", ("Server", "Item"))
    return os.path.join(found, "Server") if found else None


def base_id(name):
    """The block id a prefab entry names: its state form `*<Id>_State_Definitions_<State>` read as `<Id>`."""
    if name.startswith("*"):
        name = name[1:]
    if STATE_MARK in name:
        name = name.split(STATE_MARK, 1)[0]
    return name


def hex_rgb(text):
    t = text.strip().lstrip("#")
    if len(t) == 3:
        t = "".join(c * 2 for c in t)
    return tuple(int(t[i:i + 2], 16) for i in (0, 2, 4))


def merge(parent, child):
    out = dict(parent)
    for k, v in child.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge(out[k], v)
        else:
            out[k] = v
    return out


def turn_box(box, yaw):
    """A box (min, max) in cell units turned by `yaw` quarter steps about the cell's centre (rotate90Y per step)."""
    (x0, y0, z0), (x1, y1, z1) = box
    for _ in range(yaw % 4):
        x0, z0, x1, z1 = z0, 1 - x0, z1, 1 - x1
    return ((min(x0, x1), y0, min(z0, z1)), (max(x0, x1), y1, max(z0, z1)))


class Block:
    def __init__(self, bid, doc, boxes):
        bt = doc.get("BlockType") or {}
        self.id = bid
        self.known = bool(doc)
        colour = bt.get("TextureComputedColor") or bt.get("ParticleColor")
        if colour:
            self.rgb = hex_rgb(colour)
        else:
            h = zlib.crc32(bid.encode("utf-8"))
            self.rgb = (110 + h % 60, 110 + (h >> 8) % 60, 110 + (h >> 16) % 60)
        tint = bt.get("Tint")
        if isinstance(tint, list) and tint and isinstance(tint[0], str):
            t = hex_rgb(tint[0])
            self.rgb = tuple(int(c * k / 255.0) for c, k in zip(self.rgb, t))
        light = bt.get("Light") or {}
        self.light = hex_rgb(light["Color"]) if isinstance(light, dict) and light.get("Color") else None
        self.model = bt.get("DrawType") == "Model"
        self.boxes = boxes
        self.full = boxes == [FULL] and not self.model
        self.opaque = self.full and bt.get("Opacity", "Solid") in ("Solid", None)
        self.variant_rotation = bt.get("VariantRotation")

    def turned(self, rotation):
        yaw = rotation & 3
        return [turn_box(b, yaw) for b in self.boxes]

    def fillers(self, rotation=0):
        """The cells (dx, dy, dz), the block's own excepted, that its turned boxes reach."""
        boxes = self.turned(rotation)
        lo = [min(b[0][i] for b in boxes) for i in range(3)]
        hi = [max(b[1][i] for b in boxes) for i in range(3)]
        ranges = []
        for i in range(3):
            a = int(lo[i])  # truncates toward zero, as the engine's (int) cast does
            if a - lo[i] > 0:
                a -= 1
            b = int(hi[i])
            if hi[i] - b > 0:
                b += 1
            ranges.append(range(a, a + max(b - a, 1)))
        return [(x, y, z) for x in ranges[0] for y in ranges[1] for z in ranges[2] if (x, y, z) != (0, 0, 0)]


class BlockInfo:
    """Block ids resolved across item roots: each a `Server` folder (vanilla's, then any pack's; a later root wins)."""

    def __init__(self, server_roots):
        self.items = {}
        self.hitboxes = {}
        for root in server_roots:
            if not root:
                continue
            for dirpath, dirnames, filenames in os.walk(os.path.join(root, "Item", "Items")):
                dirnames.sort()
                for fn in sorted(filenames):
                    if fn.endswith(".json"):
                        self.items[fn[:-5]] = os.path.join(dirpath, fn)
            for dirpath, dirnames, filenames in os.walk(os.path.join(root, "Item", "Block", "Hitboxes")):
                dirnames.sort()
                for fn in sorted(filenames):
                    if fn.endswith(".json"):
                        self.hitboxes[fn[:-5]] = os.path.join(dirpath, fn)
        self._docs = {}
        self._blocks = {}
        self._boxes = {}

    def _doc(self, bid, seen=()):
        if bid in self._docs:
            return self._docs[bid]
        path = self.items.get(bid)
        if path is None or bid in seen:
            return {}
        with open(path, "r", encoding="utf-8-sig") as f:
            doc = json.load(f)
        parent = doc.get("Parent")
        if parent:
            doc = merge(self._doc(parent, seen + (bid,)), {k: v for k, v in doc.items() if k != "Parent"})
        self._docs[bid] = doc
        return doc

    def _hitbox(self, name):
        if not name or name == "Full":
            return [FULL]
        if name not in self._boxes:
            path = self.hitboxes.get(name)
            boxes = [FULL]
            if path:
                with open(path, "r", encoding="utf-8-sig") as f:
                    raw = json.load(f).get("Boxes") or []
                got = []
                for b in raw:
                    mn, mx = b.get("Min", {}), b.get("Max", {})
                    got.append(((float(mn.get("X", 0)), float(mn.get("Y", 0)), float(mn.get("Z", 0))),
                                (float(mx.get("X", 1)), float(mx.get("Y", 1)), float(mx.get("Z", 1)))))
                boxes = got or [FULL]
            self._boxes[name] = boxes
        return self._boxes[name]

    def exists(self, name):
        return base_id(name) in self.items

    def get(self, name):
        bid = base_id(name)
        if bid not in self._blocks:
            doc = self._doc(bid)
            bt = doc.get("BlockType") or {}
            self._blocks[bid] = Block(bid, doc, self._hitbox(bt.get("HitboxType")))
        return self._blocks[bid]
