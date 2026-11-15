"""Build a script room: a room spec plus a theme palette becomes a `.prefab.json` (blocks and logic entities) and its
CommonLib room file (PD1's contract), with a socket report on stdout.

    python -I tools/rooms/build_room.py --spec <spec.json> --palette <palette.json>
        --prefabs-root <Server/Prefabs folder> --rooms-root <DungeonRooms folder>

The prefab goes to <prefabs-root>/<the spec's Prefab dot path>.prefab.json and the room file to
<rooms-root>/<the spec's Id>.json. A second palette re-skins the same spec. The output is deterministic.

Never overwrites a hand-built prefab: an existing prefab is replaced only when it carries this tool's stamp
(`zcGeneratedBy`) and its blocks and entities still hash to the stamp's `zcHash`. A prefab saved from the game's prefab
editor drops the stamp, and a hand edit breaks the hash, so either is left alone (and `"zcHandBuilt": true` says so
outright). The engine reads a prefab's known keys only (BsonPrefabBufferDeserializer.java:44-57,
SelectionPrefabSerializer.java:52-65), so the stamp keys are ignored at load.

The spec (JSON, keys as below; every cell is anchor-relative, the anchor being the prefab's (0, 0, 0)):
- Id, Prefab (engine dot path under Server/Prefabs), Label, Room {Roles, Tags, Weight, Depth, Needs, Keep}.
- Shapes, applied in order, a later one winning a cell: {"Op": "Shell", Min, Max, Floor?, Walls?, Ceiling?} (a closed
  box: floor on Min.Y, ceiling on Max.Y, walls round the sides, air inside), {"Op": "Fill", Min, Max, Slot},
  {"Op": "Carve", Min, Max} (air).
- Doors: {At, Facing, Kind, Width (odd, default 3), Height (default 3), Depth (wall thickness carved, default 1),
  Closed (a slot filling the opening, such as Bars)}. At is the doorway's floor cell on the room's outer face, the
  same rule the kit importer follows, so a neighbour's door meets it one step out.
- Props: {At, Slot}. Spawns: {At, Marker} (a manual spawn marker id, woken by the SealedCombat template).
- Logic: {Template, ...params} from logic_templates.TEMPLATES.
The palette: {Id, Slots: {<slot>: block id, or [{Block, Weight}, ...] picked per cell by a stable hash}}.
"""
import argparse
import base64
import hashlib
import os
import sys
import uuid
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import logic_templates as lt  # noqa: E402
import prefabio as pio  # noqa: E402

AIR = "Air"
STAMP_KEY = "zcGeneratedBy"
STAMP_VALUE = "tools/rooms/build_room.py"
HAND_BUILT_KEY = "zcHandBuilt"
HASH_KEY = "zcHash"


class BuildError(Exception):
    pass


class Palette:
    def __init__(self, doc):
        self.id = doc.get("Id", "")
        self.slots = doc["Slots"]

    def block(self, slot, cell=(0, 0, 0)):
        if slot == AIR:
            return pio.EMPTY_BLOCK
        if slot not in self.slots:
            raise BuildError("palette %s has no slot %s" % (self.id, slot))
        value = self.slots[slot]
        if isinstance(value, str):
            return value
        total = sum(int(v.get("Weight", 1)) for v in value)
        pick = zlib.crc32(("%s|%d,%d,%d" % (slot, cell[0], cell[1], cell[2])).encode("utf-8")) % total
        for v in value:
            pick -= int(v.get("Weight", 1))
            if pick < 0:
                return v["Block"]
        return value[-1]["Block"]

    def main_block(self, slot):
        """The slot's first block (what a volume's block swap names)."""
        if slot == AIR:
            return pio.EMPTY_BLOCK
        value = self.slots[slot]
        return value if isinstance(value, str) else value[0]["Block"]


def cells_in(lo, hi):
    for y in range(lo[1], hi[1] + 1):
        for z in range(lo[2], hi[2] + 1):
            for x in range(lo[0], hi[0] + 1):
                yield (x, y, z)


def box_of(d):
    lo, hi = pio.xyz_tuple(d["Min"]), pio.xyz_tuple(d["Max"])
    return (min(lo[0], hi[0]), min(lo[1], hi[1]), min(lo[2], hi[2])), \
           (max(lo[0], hi[0]), max(lo[1], hi[1]), max(lo[2], hi[2]))


def lateral_axis(facing):
    return 0 if facing in ("North", "South") else 2


def door_cells(door):
    """The doorway's opening cells (air), its threshold cells (floor) and the opening's bounding cells."""
    at = pio.xyz_tuple(door["At"])
    facing = door["Facing"]
    width = int(door.get("Width", 3))
    height = int(door.get("Height", 3))
    depth = int(door.get("Depth", 1))
    if width % 2 != 1:
        raise BuildError("door at %s: Width must be odd" % (at,))
    axis = lateral_axis(facing)
    half = width // 2
    opening, threshold = [], []
    for k in range(depth):
        base = pio.step(at, facing, -k)
        for w in range(-half, half + 1):
            c = list(base)
            c[axis] += w
            threshold.append(tuple(c))
            for h in range(1, height + 1):
                opening.append((c[0], c[1] + h, c[2]))
    lo = tuple(min(c[i] for c in opening) for i in range(3))
    hi = tuple(max(c[i] for c in opening) for i in range(3))
    return opening, threshold, lo, hi


def spawn_marker_entity(spec_id, index, cell, marker):
    """A spawn marker in vanilla's prefab shape (Stone_Level_2_Corridors_001's markers), with a UUID derived from the
    spec id and the marker's index, so a rebuild writes the same bytes."""
    u = uuid.uuid5(uuid.NAMESPACE_URL, "zc-room:%s:spawn:%d" % (spec_id, index))
    return {"Components": {
        "HeadRotation": {"Rotation": {"Pitch": 0.0, "Roll": 0.0, "Yaw": 0.0}},
        "HiddenFromAdventurePlayer": {},
        "Model": {"Model": {"Id": "NPC_Spawn_Marker", "Scale": 1.0, "Static": False}},
        "Nameplate": {"Text": marker},
        "SpawnMarkerComponent": {"NPCReferences": [], "RespawnTime": 0.0, "SpawnCount": 0, "SpawnMarker": marker},
        "Transform": {"Position": lt.vec(cell[0] + 0.5, cell[1], cell[2] + 0.5),
                      "Rotation": {"Pitch": 0.0, "Roll": 0.0, "Yaw": 0.0}},
        "UUID": {"UUID": {"$binary": base64.b64encode(u.bytes).decode("ascii"), "$type": "04"}},
    }}


def content_hash(doc):
    body = {k: doc.get(k) for k in ("anchorX", "anchorY", "anchorZ", "blocks", "fluids", "entities")}
    return hashlib.sha1(pio.dumps(body).encode("utf-8")).hexdigest()


class RoomBuild:
    def __init__(self, spec, palette):
        self.spec = spec
        self.palette = palette
        self.slots = {}
        self.problems = []

    def run(self):
        spec = self.spec
        for op in spec.get("Shapes", []):
            kind = op["Op"]
            lo, hi = box_of(op)
            if kind == "Fill":
                for c in cells_in(lo, hi):
                    self.slots[c] = op["Slot"]
            elif kind == "Carve":
                for c in cells_in(lo, hi):
                    self.slots[c] = AIR
            elif kind == "Shell":
                floor, walls, ceiling = op.get("Floor", "Floor"), op.get("Walls", "Wall"), op.get("Ceiling", "Ceiling")
                for c in cells_in(lo, hi):
                    if c[1] == lo[1]:
                        self.slots[c] = floor
                    elif c[1] == hi[1]:
                        self.slots[c] = ceiling
                    elif c[0] in (lo[0], hi[0]) or c[2] in (lo[2], hi[2]):
                        self.slots[c] = walls
                    else:
                        self.slots[c] = AIR
            else:
                raise BuildError("unknown shape op %s" % kind)
        self.doors = []
        for i, d in enumerate(spec.get("Doors", [])):
            opening, threshold, olo, ohi = door_cells(d)
            closed = d.get("Closed")
            for c in opening:
                self.slots[c] = closed or AIR
            for c in threshold:
                if self.slots.get(c, AIR) == AIR:
                    self.slots[c] = "Floor"
            self.doors.append({"Index": i, "At": pio.xyz_tuple(d["At"]), "Facing": d["Facing"], "Kind": d["Kind"],
                               "OpeningMin": olo, "OpeningMax": ohi, "Opening": opening, "Closed": closed})
        for p in spec.get("Props", []):
            self.slots[pio.xyz_tuple(p["At"])] = p["Slot"]

        self.box = (tuple(min(c[i] for c in self.slots) for i in range(3)),
                    tuple(max(c[i] for c in self.slots) for i in range(3)))

        entities = []
        for i, s in enumerate(spec.get("Spawns", [])):
            entities.append(spawn_marker_entity(spec["Id"], i, pio.xyz_tuple(s["At"]), s["Marker"]))
        ctx = {"doors": self.doors, "slot": self.palette.main_block, "name": spec["Id"] + "_"}
        for i, logic in enumerate(spec.get("Logic", [])):
            template = lt.TEMPLATES.get(logic["Template"])
            if template is None:
                raise BuildError("unknown logic template %s" % logic["Template"])
            ctx["name"] = "%s_%d_" % (spec["Id"], i)
            entities.extend(template(logic, ctx))
        self.entities = entities
        self.check()

    def check(self):
        lo, hi = self.box
        inside = lambda c: all(lo[i] <= c[i] <= hi[i] for i in range(3))
        for d in self.doors:
            at = d["At"]
            if not inside(at):
                self.problems.append("door %s %s lies outside the room" % (at, d["Facing"]))
            elif inside(pio.step(at, d["Facing"])):
                self.problems.append("door %s %s is not on the room's outer face, so no neighbour could meet it"
                                     % (at, d["Facing"]))
            if self.slots.get(at, AIR) == AIR:
                self.problems.append("door %s %s has no floor" % (at, d["Facing"]))
        for name, what in lt.world_absolute_uses(self.entities):
            self.problems.append("volume %s uses a world-absolute position (%s)" % (name, what))

    def prefab(self):
        blocks = []
        for cell in sorted(self.slots):
            slot = self.slots[cell]
            blocks.append((cell, {"name": self.palette.block(slot, cell)}))
        doc = pio.prefab_doc((0, 0, 0), blocks, entities=self.entities)
        doc[STAMP_KEY] = STAMP_VALUE
        doc["zcSpec"] = self.spec["Id"]
        doc["zcPalette"] = self.palette.id
        doc[HASH_KEY] = content_hash(doc)
        return doc

    def room(self):
        spec = self.spec
        room = dict(spec.get("Room", {}))
        doors = []
        order = {f: i for i, f in enumerate(pio.FACINGS)}
        for d in sorted(self.doors, key=lambda d: (order[d["Facing"]], d["At"])):
            doors.append({"At": pio.xyz_dict(d["At"]), "Facing": d["Facing"], "Kind": d["Kind"]})
        room.update({
            "$Comment": [
                "%s: a script-built room, built by tools/rooms/build_room.py from its spec %s with the palette %s."
                % (spec.get("Label", spec["Id"]), spec["Id"], self.palette.id),
                "Rebuild it from the spec instead of editing this file or its prefab; a prefab rebuilt in game is "
                "kept as it is.",
            ],
            "Prefab": spec["Prefab"],
            "Box": {"Min": pio.xyz_dict(self.box[0]), "Max": pio.xyz_dict(self.box[1])},
            "Doors": doors,
        })
        return room

    def report(self):
        lines = ["Room %s (%s, palette %s): box %s..%s, %d blocks, %d logic entities" % (
            self.spec["Id"], self.spec["Prefab"], self.palette.id, self.box[0], self.box[1], len(self.slots),
            len(self.entities))]
        for d in self.doors:
            lines.append("  socket %d: %s %s kind %s, opening %s..%s%s" % (
                d["Index"], d["At"], d["Facing"], d["Kind"], d["OpeningMin"], d["OpeningMax"],
                ", closed by %s" % d["Closed"] if d["Closed"] else ""))
        keys = lt.room_scoped_keys(self.entities)
        lines.append("  room-scoped keys: %s" % (", ".join(keys) or "none"))
        for p in self.problems:
            lines.append("  PROBLEM: " + p)
        return "\n".join(lines) + "\n"


def prefab_path(prefabs_root, dot_path):
    return os.path.join(prefabs_root, *dot_path.split(".")) + pio.PREFAB_SUFFIX


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
        return "its blocks or entities changed since this tool wrote it"
    return None


def build(spec_path, palette_path, prefabs_root, rooms_root):
    spec = pio.load_json(spec_path)
    palette = Palette(pio.load_json(palette_path))
    rb = RoomBuild(spec, palette)
    rb.run()
    if rb.problems:
        raise BuildError(rb.report())
    out = prefab_path(prefabs_root, spec["Prefab"])
    why = may_overwrite(out)
    if why:
        raise BuildError("refusing to overwrite %s: %s" % (out, why))
    pio.write_json(out, rb.prefab())
    room_out = os.path.join(rooms_root, spec["Id"] + ".json")
    pio.write_json(room_out, rb.room())
    return rb, out, room_out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--spec", required=True)
    ap.add_argument("--palette", required=True)
    ap.add_argument("--prefabs-root", required=True, help="the Server/Prefabs folder the prefab is written under")
    ap.add_argument("--rooms-root", required=True, help="the DungeonRooms folder the room file is written to")
    args = ap.parse_args(argv)
    try:
        rb, out, room_out = build(args.spec, args.palette, args.prefabs_root, args.rooms_root)
    except BuildError as e:
        print(str(e), file=sys.stderr)
        return 2
    sys.stdout.write(rb.report())
    print("wrote %s and %s" % (out, room_out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
