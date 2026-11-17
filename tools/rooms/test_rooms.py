"""The room tools' structural gate (cards PD6, PD7a). Run from this folder or with discover:

    python -I -m unittest discover -s tools/rooms -t tools/rooms      (from the ziggfreed-common repository root)

- A door's At and Facing survive four turns, and one turn is the engine's (x, z) -> (z, -x).
- Every imported kit's every door can be met: it sits on its Box's outer face, another door shares its Kind, and a Cap
  room of that Kind exists that carries every tag the door's room carries.
- The importer re-run on unchanged vanilla input writes byte-identical files, the same bytes the repository ships.
  It needs vanilla's assets: HYTALE_ASSETS, or reference/shared-source/release/HytaleAssets in a folder above this one;
  without them that test is skipped.
- Every logic template's volumes, as the sample specs stamp them, use only trigger-volume Types, keys, event names and
  enum values the engine's own codecs read (the release source's builtin TriggerVolumes plugin, read once), and
  CommonLib's own Types the keys their sources declare; a missing source skips the check with the reason.
- The overlay placer never puts a block on a cell the base fills, and never lets a volume write one there; a door
  a spec seals is written ExitOnly; a door cut into a sealed base writes air only over its declared opening.
- A rune order puts a wrong rune out at every step, the first included; a room hint is said once per player.
"""
import filecmp
import os
import re
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import build_room  # noqa: E402
import import_kit  # noqa: E402
import logic_templates as lt  # noqa: E402
import prefabio as pio  # noqa: E402

REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
KITS = os.path.join(REPO, "zc-instance", "src", "main", "resources", "Server", "ZiggfreedCommon", "DungeonRooms",
                    "Kits")
CONFIG = os.path.join(HERE, "kits.json")


SPECS = os.path.join(HERE, "specs")
PALETTE = os.path.join(HERE, "palettes", "Crypt_Stone.json")


def find_reference(name, probe):
    """reference/shared-source/release/<name> in a folder above this one, when `probe` exists under it."""
    d = HERE
    while True:
        cand = os.path.join(d, "reference", "shared-source", "release", name)
        if os.path.exists(os.path.join(cand, *probe)):
            return cand
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def find_assets():
    env = os.environ.get("HYTALE_ASSETS")
    if env and os.path.isdir(env):
        return env
    return find_reference("HytaleAssets", ("Server", "Prefabs"))


def kit_rooms(kit):
    folder = os.path.join(KITS, kit)
    rooms = {}
    for fn in sorted(os.listdir(folder)):
        if fn.endswith(".json"):
            rooms[fn[:-5]] = pio.load_json(os.path.join(folder, fn))
    return rooms


def covers(cap_tags, room_tags):
    """True when the cap's Tags map holds every key and value of the room's (the engine's map: key -> values)."""
    return all(set(values) <= set(cap_tags.get(key, [])) for key, values in room_tags.items())


def shipped_kits():
    return sorted(k for k in pio.load_json(CONFIG)["Kits"] if os.path.isdir(os.path.join(KITS, k)))


class Turns(unittest.TestCase):
    def all_doors(self):
        doors = []
        for kit in shipped_kits():
            for room in kit_rooms(kit).values():
                doors.extend(room["Doors"])
        for facing in pio.FACINGS:
            doors.append({"At": {"X": 3, "Y": -2, "Z": -7}, "Facing": facing, "Kind": "Test"})
        return doors

    def test_a_door_survives_four_turns(self):
        for door in self.all_doors():
            turned = door
            seen = []
            for _ in range(4):
                turned = pio.rotate_door(turned, 1)
                seen.append((pio.xyz_tuple(turned["At"]), turned["Facing"]))
            self.assertEqual(turned, door)
            self.assertEqual(len(set(f for _, f in seen)), 4, "four turns face four ways")

    def test_one_turn_is_the_engines(self):
        self.assertEqual(pio.rotate_xz(1, 0, 1), (0, -1))
        self.assertEqual(pio.rotate_xz(0, 1, 1), (1, 0))
        self.assertEqual(pio.rotate_facing("North", 1), "West")
        self.assertEqual(pio.rotate_facing("East", 1), "North")
        door = {"At": {"X": 8, "Y": 0, "Z": -8}, "Facing": "East", "Kind": "K"}
        self.assertEqual(pio.rotate_door(door, 1), {"At": {"X": -8, "Y": 0, "Z": -8}, "Facing": "North", "Kind": "K"})

    def test_a_turned_door_stays_on_its_turned_box(self):
        for kit in shipped_kits():
            for rid, room in kit_rooms(kit).items():
                lo, hi = pio.xyz_tuple(room["Box"]["Min"]), pio.xyz_tuple(room["Box"]["Max"])
                for t in range(4):
                    blo, bhi = pio.rotate_box(lo, hi, t)
                    for door in room["Doors"]:
                        d = pio.rotate_door(door, t)
                        at = pio.xyz_tuple(d["At"])
                        out = pio.step(at, d["Facing"])
                        self.assertTrue(all(blo[i] <= at[i] <= bhi[i] for i in range(3)), "%s turn %d" % (rid, t))
                        self.assertFalse(all(blo[i] <= out[i] <= bhi[i] for i in range(3)), "%s turn %d" % (rid, t))


class KitDoors(unittest.TestCase):
    def test_every_door_finds_a_partner_kind_and_a_cap(self):
        kits = shipped_kits()
        self.assertTrue(kits, "no imported kit under " + KITS)
        for kit in kits:
            rooms = kit_rooms(kit)
            self.assertTrue(rooms, kit)
            doors_by_kind = {}
            caps_by_kind = {}
            for rid, room in rooms.items():
                for i, d in enumerate(room["Doors"]):
                    doors_by_kind.setdefault(d["Kind"], []).append((rid, i))
                if "Cap" in room["Roles"]:
                    self.assertEqual(len(room["Doors"]), 1, "a cap has one door: " + rid)
                    caps_by_kind.setdefault(room["Doors"][0]["Kind"], []).append(rid)
            for rid, room in rooms.items():
                lo, hi = pio.xyz_tuple(room["Box"]["Min"]), pio.xyz_tuple(room["Box"]["Max"])
                for i, d in enumerate(room["Doors"]):
                    at = pio.xyz_tuple(d["At"])
                    out = pio.step(at, d["Facing"])
                    where = "%s door %d %s %s" % (rid, i, at, d["Facing"])
                    self.assertTrue(all(lo[k] <= at[k] <= hi[k] for k in range(3)), where + " inside its Box")
                    self.assertFalse(all(lo[k] <= out[k] <= hi[k] for k in range(3)), where + " on its Box's face")
                    partners = [p for p in doors_by_kind[d["Kind"]] if p != (rid, i)]
                    self.assertTrue(partners, where + " shares its Kind " + d["Kind"] + " with another door")
                    self.assertTrue(caps_by_kind.get(d["Kind"]), where + " has a Cap of Kind " + d["Kind"])
                    tagged = [c for c in caps_by_kind[d["Kind"]] if covers(rooms[c]["Tags"], room["Tags"])]
                    self.assertTrue(tagged, where + " has a Cap carrying all its room's tags, so a tag filter that "
                                                    "admits the room admits the cap")


class Reimport(unittest.TestCase):
    def test_reimport_is_byte_identical(self):
        assets = find_assets()
        if assets is None:
            self.skipTest("vanilla assets not found (set HYTALE_ASSETS)")
        kits = shipped_kits()
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            for out in (a, b):
                import_kit.main(["--assets", assets, "--out", out] + sum((["--kit", k] for k in kits), []))
            for kit in kits:
                names = sorted(os.listdir(os.path.join(a, kit)))
                self.assertEqual(names, sorted(os.listdir(os.path.join(b, kit))), kit)
                shipped = sorted(fn for fn in os.listdir(os.path.join(KITS, kit)) if fn.endswith(".json"))
                self.assertEqual(names, shipped, kit + ": the shipped room files are the ones a fresh import writes")
                for fn in names:
                    fa, fb = os.path.join(a, kit, fn), os.path.join(b, kit, fn)
                    self.assertTrue(filecmp.cmp(fa, fb, shallow=False), fn + " differs between two runs")
                    with open(fa, "rb") as f:
                        fresh = f.read()
                    self.assertNotIn(b"\r\n", fresh, fn + " is written with LF line endings")
                    # A checkout may turn the shipped file's LF into CRLF (core.autocrlf), so compare it by lines.
                    with open(os.path.join(KITS, kit, fn), "rb") as f:
                        shipped_bytes = f.read().replace(b"\r\n", b"\n")
                    self.assertEqual(fresh, shipped_bytes, fn + " differs from the shipped file: re-run import_kit.py")


def load_spec(name):
    return pio.load_json(os.path.join(SPECS, name))


def sample_specs():
    return sorted(fn for fn in os.listdir(SPECS) if fn.endswith(".json"))


def build_sample(name, palette, assets):
    """The RoomBuild of one sample spec, or None for an overlay spec when vanilla's prefabs are not here."""
    spec = load_spec(name)
    base = base_room = None
    if "Base" in spec:
        if assets is None:
            return None
        base, base_room = build_room.load_base(spec, os.path.join(assets, "Server", "Prefabs"),
                                               [build_room.SHIPPED_ROOMS])
    rb = build_room.RoomBuild(spec, palette, base, base_room)
    rb.run()
    return rb


class TemplateCodecs(unittest.TestCase):
    """Every template, as the sample specs stamp it, against the codecs that read its volumes."""

    @classmethod
    def setUpClass(cls):
        import engine_codecs
        cls.ec = engine_codecs
        server = find_reference("HytaleServer", ("builtin", "TriggerVolumes"))
        cls.codecs = None if server is None else engine_codecs.EngineCodecs(server, REPO)
        palette = build_room.Palette(pio.load_json(PALETTE))
        assets = find_assets()
        cls.builds = {}
        for name in sample_specs():
            rb = build_sample(name, palette, assets)
            if rb is not None:
                cls.builds[name] = rb

    def test_every_template_is_stamped_by_a_script_sample(self):
        used = set()
        for name in sample_specs():
            spec = load_spec(name)
            if "Base" not in spec:
                used.update(logic["Template"] for logic in spec.get("Logic", []))
        self.assertEqual(sorted(set(lt.TEMPLATES) - used), [],
                         "a script sample spec (built with no vanilla files) stamps every template, so the codec "
                         "check below always covers it")

    def test_samples_stay_in_their_room(self):
        for name, rb in sorted(self.builds.items()):
            self.assertEqual(rb.problems, [], name)
            self.assertEqual(lt.unscoped_keys(rb.entities), [], name + ": every MatchKey ends @room")
            self.assertEqual(lt.world_absolute_uses(rb.entities), [], name + ": no world-absolute position")

    def test_every_volume_uses_only_what_the_engine_reads(self):
        if self.codecs is None:
            self.skipTest("the release shared source (reference/shared-source/release/HytaleServer) is not here")
        problems, missing = [], {}
        for name, rb in sorted(self.builds.items()):
            for _, tv in lt.volumes(rb.entities):
                where = "%s %s" % (name, tv.get("Name"))
                self.check_volume(tv, where, problems, missing)
        for why, where in sorted(missing.items()):
            with self.subTest(type=why):
                self.skipTest("%s (first used by %s)" % (why, where))
        self.assertEqual(problems, [])

    def check_volume(self, tv, where, problems, missing):
        ec, codecs = self.ec, self.codecs
        for key, value in tv.items():
            if key in ("Effects", "RejectionEffects", "Conditions", "Shape", "Tags"):
                continue
            ec.check_value(codecs.volume, key, value, where, problems)
        shape = tv.get("Shape") or {}
        info = codecs.shapes.get(shape.get("Type"))
        if info is None:
            problems.append("%s: no shape Type %r" % (where, shape.get("Type")))
        else:
            for key, value in shape.items():
                if key != "Type":
                    ec.check_value(info, key, value, where + " shape", problems)
        for list_key, base in (("Effects", "TriggerEffect"), ("RejectionEffects", "TriggerEffect"),
                               ("Conditions", "TriggerCondition")):
            for part in tv.get(list_key) or []:
                info, why = codecs.type_of(base, part.get("Type"))
                if info is None:
                    if why.startswith("CommonLib"):
                        missing.setdefault(why, where)
                    else:
                        problems.append("%s: %s" % (where, why))
                    continue
                if part.get("Event") not in codecs.events:
                    problems.append("%s: %s fires on %r, an event the engine never raises" % (
                        where, part.get("Type"), part.get("Event")))
                for key, value in part.items():
                    if key not in ("Type", "Event"):
                        ec.check_value(info, key, value, "%s %s" % (where, part.get("Type")), problems)


def synthetic_base():
    """A small vanilla-like piece: a stone floor, walls, a ceiling, a pillar, and a south doorway 3 wide."""
    blocks = []
    for x in range(-3, 4):
        for z in range(-6, 1):
            for y in range(0, 5):
                edge = x in (-3, 3) or z in (-6, 0)
                doorway = z == 0 and -1 <= x <= 1 and 1 <= y <= 3
                pillar = (x, z) == (1, -3)
                solid = y in (0, 4) or (edge and not doorway) or pillar
                blocks.append(((x, y, z), {"name": "Rock_Stone" if solid else pio.EMPTY_BLOCK}))
    return pio.Prefab("synthetic", pio.prefab_doc((0, 0, 0), blocks))


SYNTHETIC_ROOM = {"Box": {"Min": {"X": -3, "Y": 0, "Z": -6}, "Max": {"X": 3, "Y": 4, "Z": 0}},
                  "Doors": [{"At": {"X": 0, "Y": 0, "Z": 0}, "Facing": "South", "Kind": "Test"}],
                  "Tags": {"Kit": ["Test"]}, "Fill": "Rock_Stone"}


def overlay_spec(**more):
    spec = {"Id": "Overlay_Test", "Base": {"Prefab": "Test.Base"}, "Overlay": "Test.Overlay", "Room": {"Roles": ["Room"]},
            "Closed": {"0": "Bars"},
            "Props": [{"At": {"X": -2, "Z": -5, "OnFloor": True}, "Slot": "Light"}],
            "Logic": [{"Template": "BrazierRelay", "Opens": [0],
                       "Braziers": [{"X": 2, "Z": -1, "OnFloor": True}, {"X": -2, "Z": -1, "OnFloor": True}]},
                      {"Template": "MemoryClue", "Canvas": {"X": -1, "Z": -3, "OnFloor": True},
                       "Variants": [[[0, 0], [0, 1]]]}]}
    spec.update(more)
    return spec


class OverlayPlacer(unittest.TestCase):
    """The overlay never puts a block on a cell the base fills, and never lets a volume write one there."""

    def setUp(self):
        self.palette = build_room.Palette(pio.load_json(PALETTE))
        self.base = synthetic_base()

    def build(self, spec):
        rb = build_room.RoomBuild(spec, self.palette, self.base, SYNTHETIC_ROOM)
        rb.run()
        return rb

    def assert_on_free_cells(self, rb, base, what):
        free = build_room.BaseGround(base).free_cells
        on_filled = sorted(c for c in rb.slots if c not in free)
        self.assertEqual(on_filled, [], what + ": overlay blocks on cells the base fills")
        written = sorted(c for _, c in lt.placed_cells(rb.entities) if c not in free)
        self.assertEqual(written, [], what + ": volumes writing blocks into cells the base fills")

    def test_an_overlay_fits_on_the_base_s_free_cells(self):
        rb = self.build(overlay_spec())
        self.assertEqual(rb.problems, [])
        self.assert_on_free_cells(rb, self.base, "synthetic")
        self.assertEqual(rb.slots[(-2, 1, -5)], "Light", "OnFloor puts a prop in the air cell over the floor")
        self.assertEqual(sorted(c for c, s in rb.slots.items() if s == "Bars"),
                         sorted((x, y, 0) for y in (1, 2, 3) for x in (-1, 0, 1)),
                         "a closed doorway fills exactly the doorway's free cells")
        room = rb.room()
        self.assertEqual((room["Prefab"], room["Overlay"]), ("Test.Base", "Test.Overlay"))
        self.assertEqual(room["Fill"], "Rock_Stone", "the base room's Fill carries over")
        self.assertIs(room["Doors"][0].get("ExitOnly"), True, "a sealed door is one the layout never enters by")

    def test_a_tall_block_writes_its_filler_cells(self):
        doc = pio.load_json(PALETTE)
        doc["Slots"]["Tall"] = {"Block": "Rock_Stone", "Height": 3}
        rb = build_room.RoomBuild(overlay_spec(Props=[{"At": {"X": -2, "Z": -5, "OnFloor": True}, "Slot": "Tall"}]),
                                  build_room.Palette(doc), self.base, SYNTHETIC_ROOM)
        rb.run()
        blocks = {(b["x"], b["y"], b["z"]): b for b in rb.prefab()["blocks"]}
        own = blocks[(-2, 1, -5)]
        self.assertNotIn("filler", own, "the block's own cell is no filler")
        for k in (1, 2):
            above = blocks[(-2, 1 + k, -5)]
            self.assertEqual((above["name"], above["filler"]), (own["name"], k << 10),
                             "the cell %d above is the block's filler, as the engine packs it" % k)
        self.assertNotIn((-2, 4, -5), blocks, "a block three cells tall writes two fillers, no more")

    def test_a_block_on_a_filled_cell_is_refused(self):
        cases = {
            "a prop in a wall": overlay_spec(Props=[{"At": {"X": -3, "Y": 1, "Z": -2}, "Slot": "Light"}]),
            "a template block in the pillar": overlay_spec(Logic=[{"Template": "BrazierRelay",
                                                                   "Braziers": [{"X": 1, "Y": 2, "Z": -3}]}]),
            "a fill over the floor": overlay_spec(Shapes=[{"Op": "Fill", "Slot": "Floor", "Min": {"X": 0, "Y": 0, "Z": -2},
                                                           "Max": {"X": 0, "Y": 1, "Z": -2}}]),
            "a carve": overlay_spec(Shapes=[{"Op": "Carve", "Min": {"X": 0, "Y": 1, "Z": -2},
                                             "Max": {"X": 0, "Y": 1, "Z": -2}}]),
        }
        for what, spec in cases.items():
            with self.subTest(what):
                with self.assertRaises(build_room.BuildError):
                    self.build(spec)

    def test_a_cut_door_carves_air_only_in_its_declared_opening(self):
        south = {"At": {"X": 0, "Y": 0, "Z": 0}, "Facing": "South", "Kind": "Test"}
        east = {"At": {"X": 3, "Y": 0, "Z": -3}, "Facing": "East", "Kind": "Test", "Width": 1, "Height": 2, "Cut": True}
        rb = self.build(overlay_spec(Doors=[south, east]))
        self.assertEqual(rb.problems, [])
        self.assertEqual(sorted(rb.cuts), [(3, 1, -3), (3, 2, -3)], "the cut is the declared opening and no more")
        self.assertTrue(all(self.base.solid_at(c) for c in rb.cuts), "the cut opens the base's own wall")
        free = build_room.BaseGround(self.base).free_cells
        self.assertEqual(sorted(c for c in rb.slots if c not in free and c not in rb.cuts), [],
                         "every other overlay block still stands on a free cell")
        listed = {(b["x"], b["y"], b["z"]): b["name"] for b in rb.prefab()["blocks"]}
        self.assertEqual([listed[c] for c in sorted(rb.cuts)], [pio.EMPTY_BLOCK] * 2,
                         "the overlay lists the cut as air, which the painter writes over the base")
        cut = [d for d in rb.room()["Doors"] if d["Facing"] == "East"]
        self.assertEqual(len(cut), 1, "the cut door is one of the room's doors")
        self.assertNotIn("ExitOnly", cut[0], "a cut door is a way in")
        with self.assertRaises(build_room.BuildError):
            self.build(overlay_spec(Doors=[south, dict(east, Closed="Bars")]))

    def test_a_volume_writing_into_a_filled_cell_is_a_problem(self):
        spec = overlay_spec(Logic=[{"Template": "MemoryClue", "Canvas": {"X": 1, "Y": 2, "Z": -3},
                                    "Variants": [[[0, 0]]]}])
        rb = self.build(spec)
        self.assertTrue(any("a cell the base fills" in p for p in rb.problems), rb.problems)

    def test_the_sample_overlays_sit_inside_their_bases(self):
        assets = find_assets()
        if assets is None:
            self.skipTest("vanilla assets not found (set HYTALE_ASSETS)")
        overlays = [n for n in sample_specs() if "Base" in load_spec(n)]
        self.assertTrue(overlays, "the samples include an overlay spec")
        for name in overlays:
            rb = build_sample(name, self.palette, assets)
            self.assertEqual(rb.problems, [], name)
            self.assert_on_free_cells(rb, rb.base, name)


class PuzzleLogic(unittest.TestCase):
    def ctx(self):
        palette = build_room.Palette(pio.load_json(PALETTE))
        return {"doors": [], "slot": palette.main_block, "name": "T_", "box": ((-5, 0, -5), (5, 5, 5))}

    def test_a_wrong_first_rune_is_put_out(self):
        params = {"Runes": [{"X": 0, "Y": 1, "Z": -2}, {"X": 2, "Y": 1, "Z": -2}, {"X": 4, "Y": 1, "Z": -2}],
                  "Glyphs": ["Rune_A", "Rune_B", "Rune_C"], "Lit": ["Rune_A_Lit", "Rune_B_Lit", "Rune_C_Lit"],
                  "Variants": [[2, 0, 1]], "Milestone": ""}
        made = lt.rune_order(params, self.ctx())
        controller = [tv for _, tv in lt.volumes(made.entities) if tv["Name"].startswith("T_Runes_")][0]
        for k, rune in enumerate([2, 0, 1]):
            wrong = [c.get("Entry", 0) for c in controller["Conditions"]
                     if c.get("TagKey") == "Rune" and c.get("Comparison") == "NotEquals" and c["TagValue"] == str(rune)]
            self.assertEqual(len(wrong), 1, "step %d has a wrong-rune entry" % k)
            entry = wrong[0]
            self.assertIn(str(k), [c["TagValue"] for c in controller["Conditions"]
                                   if c.get("Entry", 0) == entry and c.get("TagKey") == "Step"])
            puts_out = [e for e in controller["Effects"] if e.get("Entry", 0) == entry and e["Type"] == "ReplaceBlockType"]
            self.assertEqual(len(puts_out), 3, "a wrong rune at step %d puts every rune out" % k)

    def test_a_door_lock_fills_exactly_its_doorway(self):
        ctx = self.ctx()
        opening = [(0, 1, 0), (0, 2, 0), (0, 3, 0), (1, 1, 0), (1, 2, 0), (-1, 2, 0)]
        ctx["doors"] = [{"Opening": opening}]
        made = lt.skill_lock({"Lock": {"Door": 0}, "Param": "MMO_Level_MINING", "Min": 10,
                              "HintKey": "server.test.lock"}, ctx)
        self.assertEqual(sorted(made.blocks), sorted(opening), "the lock stands in every doorway cell, no other")
        tv = made.entities[0]["Components"]["TriggerVolume"]
        swapped = sorted((e["Offset"]["X"], e["Offset"]["Y"], e["Y"]) for e in tv["Effects"]
                         if e["Type"] == "ReplaceBlockType")
        self.assertEqual(len(swapped), 3, "opening it swaps the doorway column by column")

    def test_a_room_hint_is_said_once_per_player(self):
        made = lt.room_hint({"HintKey": "server.test.hint"}, self.ctx())
        tv = made.entities[0]["Components"]["TriggerVolume"]
        self.assertEqual([(e["Event"], e["Type"], e["Message"]) for e in tv["Effects"]],
                         [("ENTER", "SendMessage", "server.test.hint")])
        self.assertGreaterEqual(tv["Cooldown"], 3600.0, "a per-player cooldown longer than a run")
        self.assertNotIn("CooldownMode", tv, "the engine's default cooldown is per entity")
        plates = lt.pressure_plates({"Plates": [{"X": 0, "Y": 1, "Z": 0}], "HintKey": "server.test.plates"},
                                    self.ctx())
        self.assertEqual(sum(1 for _, v in lt.volumes(plates.entities) if v["Name"] == "T_Hint"), 1,
                         "a puzzle's HintKey stamps the room hint")


if __name__ == "__main__":
    unittest.main()
