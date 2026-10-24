"""The room tools' structural gate (card PD6). Run from this folder or with discover:

    python -I -m unittest discover -s tools/rooms -t tools/rooms      (from the ziggfreed-common repository root)

- A door's At and Facing survive four turns, and one turn is the engine's (x, z) -> (z, -x).
- Every imported kit's every door can be met: it sits on its Box's outer face, another door shares its Kind, and a Cap
  room of that Kind exists that carries every tag the door's room carries.
- The importer re-run on unchanged vanilla input writes byte-identical files, the same bytes the repository ships.
  It needs vanilla's assets: HYTALE_ASSETS, or reference/shared-source/release/HytaleAssets in a folder above this one;
  without them that test is skipped.
"""
import filecmp
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import import_kit  # noqa: E402
import prefabio as pio  # noqa: E402

REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
KITS = os.path.join(REPO, "zc-instance", "src", "main", "resources", "Server", "ZiggfreedCommon", "DungeonRooms",
                    "Kits")
CONFIG = os.path.join(HERE, "kits.json")


def find_assets():
    env = os.environ.get("HYTALE_ASSETS")
    if env and os.path.isdir(env):
        return env
    d = HERE
    while True:
        cand = os.path.join(d, "reference", "shared-source", "release", "HytaleAssets")
        if os.path.isdir(os.path.join(cand, "Server", "Prefabs")):
            return cand
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def kit_rooms(kit):
    folder = os.path.join(KITS, kit)
    rooms = {}
    for fn in sorted(os.listdir(folder)):
        if fn.endswith(".json"):
            rooms[fn[:-5]] = pio.load_json(os.path.join(folder, fn))
    return rooms


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
                    tagged = [c for c in caps_by_kind[d["Kind"]] if set(room["Tags"]) <= set(rooms[c]["Tags"])]
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


if __name__ == "__main__":
    unittest.main()
