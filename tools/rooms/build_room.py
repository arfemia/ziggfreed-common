"""Build a script room: a room spec plus a theme palette becomes a `.prefab.json` (blocks and logic entities) and its
CommonLib room file (PD1's contract), with a socket report on stdout.

    python -I tools/rooms/build_room.py --spec <spec.json> --palette <palette.json>
        --prefabs-root <Server/Prefabs folder> --rooms-root <DungeonRooms folder>
        [--base-prefabs <vanilla Server/Prefabs>] [--base-rooms <folder of room files>]

The prefab goes to <prefabs-root>/<the spec's Prefab dot path>.prefab.json (an overlay spec: its Overlay dot path) and
the room file to <rooms-root>/<the spec's Id>.json. A second palette re-skins the same spec. The output is
deterministic.

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
  same rule the kit importer follows, so a neighbour's door meets it one step out. A Closed door is written ExitOnly
  in the room file (an overlay's Closed doors too): the layout never enters the room through a door it seals, so the
  room's logic opening it is always the way on.
- Props: {At, Slot}. Spawns: {At, Marker} (a manual spawn marker id, woken by the SealedCombat template). A spec
  without an overlay that lists any writes "Keep": {"Markers": true} in its room file, so the room logic places them.
- Logic: {Template, ...params} from logic_templates.TEMPLATES; a template may place blocks of its own (braziers,
  plates, runes), which count as props. A template is handed the room's doors and its Box (an overlay's: the base
  room's), the region a room hint covers unless it names its own.
- Any cell may be written {X, Z, "OnFloor": true} (Y optional, the height to start looking from): the builder puts it
  in the first air cell over a floor in that column, nearest that height, with headroom above.
The palette: {Id, Slots: {<slot>: block id, {Block, State}, or [{Block, State?, Weight}, ...] picked per cell by a
stable hash}}. A State is one of the block's own state definitions (a lit brazier is the brazier in its On state); the
prefab lists it as *<Block>_State_Definitions_<State>, and a volume's block swap names the block and the state.

An overlay spec builds a vanilla piece plus our own blocks and logic instead of a whole room:
- Base {Prefab (the vanilla piece's dot path: one prefab, never a pool), Room (optional: the id of an imported room
  file whose pool holds that piece; its Box, Doors, Tags and Fill carry over)}, Overlay (the dot path our prefab is
  written to), no Prefab.
- The builder measures the base: its free cells (the air the piece itself carves), its floors, and each doorway's
  opening (prefabio.doorway_cells). Doors default to the base room's; Closed {"<door index>": slot} fills an
  opening's free cells. Shapes may only Fill, and every block the overlay or a template places, and every cell a
  template's volume writes a block into, must be a free cell of the base: the overlay never puts a block on a cell the
  base fills.
- The room file names the base as Prefab and ours as Overlay, painted on top with the same anchor and turn.
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
HERE = os.path.dirname(os.path.abspath(__file__))
SHIPPED_ROOMS = os.path.normpath(os.path.join(HERE, "..", "..", "zc-instance", "src", "main", "resources", "Server",
                                              "ZiggfreedCommon", "DungeonRooms"))


class BuildError(Exception):
    pass


class Palette:
    def __init__(self, doc):
        self.id = doc.get("Id", "")
        self.slots = doc["Slots"]

    def _value(self, slot):
        if slot not in self.slots:
            raise BuildError("palette %s has no slot %s" % (self.id, slot))
        return self.slots[slot]

    @staticmethod
    def _ref(entry):
        if isinstance(entry, str):
            return lt.BlockRef(entry)
        return lt.BlockRef(entry["Block"], entry.get("State", ""))

    def block(self, slot, cell=(0, 0, 0)):
        """The block the prefab lists for `slot` at `cell`."""
        if slot == AIR:
            return pio.EMPTY_BLOCK
        value = self._value(slot)
        if not isinstance(value, list):
            ref = self._ref(value)
            return lt.prefab_name(ref, ref.state)
        total = sum(int(v.get("Weight", 1)) for v in value)
        pick = zlib.crc32(("%s|%d,%d,%d" % (slot, cell[0], cell[1], cell[2])).encode("utf-8")) % total
        chosen = value[-1]
        for v in value:
            pick -= int(v.get("Weight", 1))
            if pick < 0:
                chosen = v
                break
        ref = self._ref(chosen)
        return lt.prefab_name(ref, ref.state)

    def main_block(self, slot):
        """The slot's first block, with its state (what a volume's block swap names)."""
        if slot == AIR:
            return lt.BlockRef(pio.EMPTY_BLOCK)
        value = self._value(slot)
        return self._ref(value[0] if isinstance(value, list) else value)


def cells_in(lo, hi):
    for y in range(lo[1], hi[1] + 1):
        for z in range(lo[2], hi[2] + 1):
            for x in range(lo[0], hi[0] + 1):
                yield (x, y, z)


def box_of(d):
    lo, hi = pio.xyz_tuple(d["Min"]), pio.xyz_tuple(d["Max"])
    return (min(lo[0], hi[0]), min(lo[1], hi[1]), min(lo[2], hi[2])), \
           (max(lo[0], hi[0]), max(lo[1], hi[1]), max(lo[2], hi[2]))


def bounds_of(cells):
    cells = list(cells)
    return (tuple(min(c[i] for c in cells) for i in range(3)), tuple(max(c[i] for c in cells) for i in range(3)))


def door_cells(door):
    """A script door's opening cells (air), its threshold cells (floor) and the opening's bounding cells."""
    at = pio.xyz_tuple(door["At"])
    facing = door["Facing"]
    width = int(door.get("Width", 3))
    height = int(door.get("Height", 3))
    depth = int(door.get("Depth", 1))
    if width % 2 != 1:
        raise BuildError("door at %s: Width must be odd" % (at,))
    axis = pio.lateral_axis(facing)
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
    lo, hi = bounds_of(opening)
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


# ---------------------------------------------------------------------------------------------------------------
# What the room stands on: its own blocks (a script room) or a vanilla piece (an overlay)


class ScriptGround:
    """A script room's own cells: air where its shapes carve, solid where they fill, nothing elsewhere."""

    def __init__(self, slots):
        self.slots = slots

    def free(self, cell):
        return self.slots.get(cell) == AIR

    def solid(self, cell):
        s = self.slots.get(cell)
        return s is not None and s != AIR


class BaseGround:
    """A vanilla piece measured for an overlay. A free cell is one the piece lists as Empty with no fluid: the air it
    carves itself. An unlisted cell inside the room is rock the painter fills (Fill), so it is never free."""

    def __init__(self, prefab):
        self.prefab = prefab
        self.free_cells = {c for c, b in prefab.blocks.items()
                           if b.get("name") == pio.EMPTY_BLOCK and c not in prefab.fluids}

    def free(self, cell):
        return cell in self.free_cells

    def solid(self, cell):
        return self.prefab.solid_at(cell)


def floor_cell(ground, x, z, hint, ys):
    """The first air cell over a floor in column (x, z) with a free cell above it, nearest `hint`, or None."""
    found = [y for y in ys if ground.solid((x, y - 1, z)) and ground.free((x, y, z)) and ground.free((x, y + 1, z))]
    if not found:
        return None
    return (x, sorted(found, key=lambda y: (abs(y - hint), y))[0], z)


class RoomBuild:
    def __init__(self, spec, palette, base=None, base_room=None):
        """`base` is the vanilla piece (a prefabio.Prefab) an overlay spec builds on, `base_room` its room file."""
        self.spec = spec
        self.palette = palette
        self.base = base
        self.base_room = base_room
        self.overlay = "Base" in spec
        self.slots = {}
        self.problems = []
        if self.overlay and base is None:
            raise BuildError("spec %s names a Base, but no base prefab was given" % spec.get("Id"))

    # -----------------------------------------------------------------------------------------------------------
    # Cells

    def resolve(self, value):
        """Every {X, Z, OnFloor: true} inside `value` becomes the floor cell the ground has there."""
        if isinstance(value, list):
            return [self.resolve(v) for v in value]
        if not isinstance(value, dict):
            return value
        if value.get("OnFloor"):
            hint = int(value.get("Y", 1))
            ys = self.column_range()
            c = floor_cell(self.ground, int(value["X"]), int(value["Z"]), hint, ys)
            if c is None:
                raise BuildError("no floor with headroom in column X %s, Z %s" % (value["X"], value["Z"]))
            return pio.xyz_dict(c)
        return {k: self.resolve(v) for k, v in value.items()}

    def column_range(self):
        if self.overlay:
            lo, hi = self.base.bounds()
        else:
            lo, hi = bounds_of(self.slots) if self.slots else ((0, 0, 0), (0, 0, 0))
        return range(lo[1], hi[1] + 2)

    def put(self, cell, slot, what):
        """Place a block of the overlay or the room; an overlay places only on the base's free cells."""
        if self.overlay:
            if slot == AIR:
                raise BuildError("%s at %s: an overlay places blocks, never air" % (what, cell))
            if not self.ground.free(cell):
                raise BuildError("%s at %s: the base fills that cell (an overlay places only on the air the base "
                                 "carves)" % (what, cell))
        self.slots[cell] = slot

    # -----------------------------------------------------------------------------------------------------------
    # Building

    def run(self):
        spec = self.spec
        if self.overlay:
            self.ground = BaseGround(self.base)
        else:
            self.ground = ScriptGround(self.slots)
        for op in spec.get("Shapes", []):
            kind = op["Op"]
            lo, hi = box_of(op)
            if self.overlay and kind != "Fill":
                raise BuildError("an overlay spec may only Fill (a %s would carve or wall the base)" % kind)
            if kind == "Fill":
                for c in cells_in(lo, hi):
                    self.put(c, op["Slot"], "Fill")
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
        self.doors = self.base_doors() if self.overlay else self.script_doors()
        for p in spec.get("Props", []):
            self.put(pio.xyz_tuple(self.resolve(p["At"])), p["Slot"], "prop %s" % p["Slot"])

        entities = []
        for i, s in enumerate(spec.get("Spawns", [])):
            cell = pio.xyz_tuple(self.resolve(s["At"]))
            if self.overlay and not self.ground.free(cell):
                self.problems.append("spawn marker %s at %s stands in a cell the base fills" % (s["Marker"], cell))
            entities.append(spawn_marker_entity(spec["Id"], i, cell, s["Marker"]))
        ctx = {"doors": self.doors, "slot": self.palette.main_block, "name": spec["Id"] + "_",
               "box": self.room_box()}
        for i, logic in enumerate(spec.get("Logic", [])):
            template = lt.TEMPLATES.get(logic["Template"])
            if template is None:
                raise BuildError("unknown logic template %s" % logic["Template"])
            ctx["name"] = "%s_%d_" % (spec["Id"], i)
            try:
                made = template(self.resolve(logic), ctx)
            except (KeyError, ValueError) as e:
                raise BuildError("logic %d (%s): %s" % (i, logic["Template"], e))
            for cell, slot in sorted(made.blocks.items()):
                self.put(cell, slot, "%s's block %s" % (logic["Template"], slot))
            entities.extend(made.entities)
        self.entities = entities
        self.box = self.room_box()
        self.check()

    def script_doors(self):
        doors = []
        for i, d in enumerate(self.spec.get("Doors", [])):
            opening, threshold, olo, ohi = door_cells(d)
            closed = d.get("Closed")
            for c in opening:
                self.slots[c] = closed or AIR
            for c in threshold:
                if self.slots.get(c, AIR) == AIR:
                    self.slots[c] = "Floor"
            doors.append({"Index": i, "At": pio.xyz_tuple(d["At"]), "Facing": d["Facing"], "Kind": d["Kind"],
                          "OpeningMin": olo, "OpeningMax": ohi, "Opening": opening, "Closed": closed})
        return doors

    def base_doors(self):
        """The base's doorways, measured: each opening is the doorway's free cells in its face plane."""
        given = self.spec.get("Doors")
        if given is None:
            if self.base_room is None:
                raise BuildError("an overlay spec gives Doors, or a Base Room to take them from")
            given = self.base_room.get("Doors") or []
        closed_by = {int(k): v for k, v in (self.spec.get("Closed") or {}).items()}
        doors = []
        for i, d in enumerate(given):
            at, facing = pio.xyz_tuple(d["At"]), d["Facing"]
            found = pio.doorway_cells(self.base, at, facing)
            if found is None:
                raise BuildError("door %d at %s %s: the base has no open doorway there" % (i, at, facing))
            opening = [c for c in pio.doorway_world_cells(at, facing, found[0]) if self.ground.free(c)]
            if not opening:
                raise BuildError("door %d at %s %s: the doorway has no free cell to close" % (i, at, facing))
            olo, ohi = bounds_of(opening)
            closed = d.get("Closed") or closed_by.get(i)
            if closed:
                for c in opening:
                    self.put(c, closed, "door %d's %s" % (i, closed))
            doors.append({"Index": i, "At": at, "Facing": facing, "Kind": d["Kind"], "OpeningMin": olo,
                          "OpeningMax": ohi, "Opening": opening, "Closed": closed})
        return doors

    def room_box(self):
        if not self.overlay:
            return bounds_of(self.slots)
        if self.base_room is not None and self.base_room.get("Box"):
            box = box_of(self.base_room["Box"])
        else:
            box = self.base.bounds()
        cells = [box[0], box[1]] + list(self.slots)
        return bounds_of(cells)

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
            if not self.ground.solid(at) and self.slots.get(at, AIR) == AIR:
                self.problems.append("door %s %s has no floor" % (at, d["Facing"]))
        for name, what in lt.world_absolute_uses(self.entities):
            self.problems.append("volume %s uses a world-absolute position (%s)" % (name, what))
        for name, what, key in lt.unscoped_keys(self.entities):
            self.problems.append("volume %s: %s reaches past the room (%s)" % (name, what, key))
        if self.overlay:
            for name, cell in lt.placed_cells(self.entities):
                if not self.ground.free(cell):
                    self.problems.append("volume %s writes a block into %s, a cell the base fills" % (name, cell))

    # -----------------------------------------------------------------------------------------------------------
    # Output

    def prefab_path_key(self):
        return self.spec["Overlay"] if self.overlay else self.spec["Prefab"]

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
            door = {"At": pio.xyz_dict(d["At"]), "Facing": d["Facing"], "Kind": d["Kind"]}
            if d["Closed"]:
                # Sealed by the room's logic until it is solved: the layout may leave by it, never enter.
                door["ExitOnly"] = True
            doors.append(door)
        label = spec.get("Label", spec["Id"])
        if self.overlay:
            tags = {}
            for source in ((self.base_room or {}).get("Tags") or {}, room.get("Tags") or {}):
                for key, values in source.items():
                    tags.setdefault(key, set()).update(values)
            room["Tags"] = {key: sorted(values) for key, values in sorted(tags.items())}
            if self.base_room and self.base_room.get("Fill") and "Fill" not in room:
                room["Fill"] = self.base_room["Fill"]
            comment = [
                "%s: vanilla's piece %s with our blocks and logic painted over it (%s), built by "
                "tools/rooms/build_room.py from its spec %s with the palette %s." % (
                    label, spec["Base"]["Prefab"], spec["Overlay"], spec["Id"], self.palette.id),
                "Rebuild it from the spec instead of editing this file or the overlay prefab; the overlay places "
                "blocks only on the air the piece carves.",
            ]
            room.update({"Prefab": spec["Base"]["Prefab"], "Overlay": spec["Overlay"]})
        else:
            comment = [
                "%s: a script-built room, built by tools/rooms/build_room.py from its spec %s with the palette %s."
                % (label, spec["Id"], self.palette.id),
                "Rebuild it from the spec instead of editing this file or its prefab; a prefab rebuilt in game is "
                "kept as it is.",
            ]
            room["Prefab"] = spec["Prefab"]
            if spec.get("Spawns"):
                # Its spawn markers stand in its own prefab, which the room logic places only when the room file
                # keeps its markers (Keep.Markers is false unless written).
                keep = dict(room.get("Keep") or {})
                keep["Markers"] = True
                room["Keep"] = keep
        room.update({
            "$Comment": comment,
            "Box": {"Min": pio.xyz_dict(self.box[0]), "Max": pio.xyz_dict(self.box[1])},
            "Doors": doors,
        })
        return room

    def report(self):
        lines = ["Room %s (%s, palette %s): box %s..%s, %d blocks, %d logic entities" % (
            self.spec["Id"], self.prefab_path_key(), self.palette.id, self.box[0], self.box[1], len(self.slots),
            len(self.entities))]
        if self.overlay:
            lines.append("  overlay on %s: %d free cells in the base, %d of them used" % (
                self.spec["Base"]["Prefab"], len(self.ground.free_cells), len(self.slots)))
        for d in self.doors:
            lines.append("  socket %d: %s %s kind %s, opening %s..%s%s" % (
                d["Index"], d["At"], d["Facing"], d["Kind"], d["OpeningMin"], d["OpeningMax"],
                ", closed by %s, exit only" % d["Closed"] if d["Closed"] else ""))
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


def find_room_file(rooms_dirs, room_id):
    for folder in rooms_dirs:
        for dirpath, dirnames, filenames in os.walk(folder):
            dirnames.sort()
            if room_id + ".json" in filenames:
                return os.path.join(dirpath, room_id + ".json")
    return None


def load_base(spec, base_prefabs, base_rooms):
    """The vanilla piece and (when named) its room file an overlay spec builds on."""
    base = spec["Base"]
    if base["Prefab"].endswith(".*"):
        raise BuildError("Base.Prefab must name one prefab, never a pool, so the overlay's cells stay fitted")
    if not base_prefabs:
        raise BuildError("an overlay spec needs --base-prefabs (vanilla's Server/Prefabs folder)")
    files = pio.pool_files(base_prefabs, base["Prefab"])
    if not files:
        raise BuildError("no prefab %s under %s" % (base["Prefab"], base_prefabs))
    room = None
    if base.get("Room"):
        path = find_room_file(base_rooms, base["Room"])
        if path is None:
            raise BuildError("no room file %s under %s" % (base["Room"], ", ".join(base_rooms)))
        room = pio.load_json(path)
        pool = room.get("Prefab", "")
        if pool != base["Prefab"] and not (pool.endswith(".*") and base["Prefab"].startswith(pool[:-1])):
            raise BuildError("room %s's pool %s does not hold %s" % (base["Room"], pool, base["Prefab"]))
    return pio.load_prefab(files[0]), room


def build(spec_path, palette_path, prefabs_root, rooms_root, base_prefabs=None, base_rooms=None):
    spec = pio.load_json(spec_path)
    palette = Palette(pio.load_json(palette_path))
    base = base_room = None
    if "Base" in spec:
        base, base_room = load_base(spec, base_prefabs, base_rooms or [SHIPPED_ROOMS])
    rb = RoomBuild(spec, palette, base, base_room)
    rb.run()
    if rb.problems:
        raise BuildError(rb.report())
    out = prefab_path(prefabs_root, rb.prefab_path_key())
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
    ap.add_argument("--base-prefabs", help="vanilla's Server/Prefabs folder, for an overlay spec's Base")
    ap.add_argument("--base-rooms", action="append",
                    help="a folder holding the room file an overlay's Base names (default the shipped DungeonRooms)")
    args = ap.parse_args(argv)
    try:
        rb, out, room_out = build(args.spec, args.palette, args.prefabs_root, args.rooms_root, args.base_prefabs,
                                  args.base_rooms)
    except BuildError as e:
        print(str(e), file=sys.stderr)
        return 2
    sys.stdout.write(rb.report())
    print("wrote %s and %s" % (out, room_out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
