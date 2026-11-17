"""Build CommonLib's own temple hub districts: the Concourse and the scaffolding on unbuilt lots, as prefabs the hub's
generator paints, plus a check that every district's named spots stand on open ground.

    python -I tools/rooms/build_hub.py [--check]        (from the ziggfreed-common repository root)

The prefabs go to zc-instance's Server/Prefabs/ZiggfreedCommon/Hub/ (Concourse, Scaffold_Large, Scaffold_Small), each
drawn on a hubkit.Canvas: anchor-relative, front facing North (-Z), the lot's centre on the floor's own layer at Y 0,
a player walking Y 1. `--check` builds in memory and writes nothing. Pictures: `preview.py hub --out <folder> --png`.

The look follows the Forgotten Temple as Update 7's goblins extended it: the temple's tan quartzite and white marble
brick, its ledge-brick paths, pale lanterns and braziers, moss and old trees growing through it; goblin iron, pipes,
crates and scrap where the goblin builders are still at work; Kweebec lanterns in the gardens; void crystal at the
arrival. Every block id is the installed game's own or one of CommonLib's hub blocks (Server/Item/Items/Hub).

Every shipped district lays the same street block (STREET) as its Floor, so the streets between lots read as one
paving whatever stands on them; a district's prefab paves its own lot.

The spot check: each district file's Anchors (Server/ZiggfreedCommon/HubDistricts/) must stand on a cell with ground
under it and two cells of headroom, inside the lot, or the build fails naming the spot.
"""
import argparse
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import blockinfo as bi  # noqa: E402
import hubkit as hk  # noqa: E402

REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
RESOURCES = os.path.join(REPO, "zc-instance", "src", "main", "resources", "Server")
PREFABS = os.path.join(RESOURCES, "Prefabs", "ZiggfreedCommon", "Hub")
DISTRICTS = os.path.join(RESOURCES, "ZiggfreedCommon", "HubDistricts")

# ---------------------------------------------------------------------------------------------------------------
# The palette

STREET = "Rock_Ledge_Brick"
MARBLE = "Rock_Marble_Brick_Smooth"
MARBLE_BRICK = "Rock_Marble_Brick"
MARBLE_ORNATE = "Rock_Marble_Brick_Ornate"
MARBLE_DECO = "Rock_Marble_Brick_Decorative"
MARBLE_HALF = "Rock_Marble_Brick_Smooth_Half"
MARBLE_WALL = "Rock_Marble_Brick_Wall"
MARBLE_PILLAR_BASE = "Rock_Marble_Brick_Pillar_Base"
MARBLE_PILLAR = "Rock_Marble_Brick_Pillar_Middle"
QUARTZ = "Rock_Quartzite_Brick"
QUARTZ_SMOOTH = "Rock_Quartzite_Brick_Smooth"
QUARTZ_DECO = "Rock_Quartzite_Brick_Decorative"
QUARTZ_HALF = "Rock_Quartzite_Brick_Half"
QUARTZ_PILLAR_BASE = "Rock_Quartzite_Brick_Pillar_Base"
QUARTZ_PILLAR = "Rock_Quartzite_Brick_Pillar_Middle"
QUARTZ_COBBLE = "Rock_Quartzite_Cobble"
CHALK = "Rock_Chalk_Brick_Smooth"
STONE = "Rock_Stone"
VOID_BLOCK = "Rock_Crystal_Void_Block"
VOID_CRYSTALS = [("Rock_Crystal_Void_Small", 2), ("Rock_Crystal_Void_Medium", 2), ("Rock_Crystal_Void_Large", 1)]
LANTERN = "Furniture_Temple_Light_Lantern"
BRAZIER = "Furniture_Temple_Light_Brazier"
STATUE = "Furniture_Temple_Light_Statue"
BENCH = "Furniture_Temple_Light_Bench"
TEMPLE_POT = "Furniture_Temple_Light_Pot"
KWEEBEC_LANTERN = "Furniture_Kweebec_Lantern"
GRASS = "Soil_Grass_Full"
MOSS = "Plant_Moss_Block_Green"
MOSS_RUG = "Plant_Moss_Rug_Green"
TRUNK = "Wood_Oak_Trunk"
LEAVES = "Plant_Leaves_Oak"
BEECH_TRUNK = "Wood_Beech_Trunk"
BEECH_LEAVES = "Plant_Leaves_Beech"
FLOWERS = [("Plant_Flower_Common_Red2", 2), ("Plant_Flower_Common_Yellow2", 2), ("Plant_Flower_Common_Cyan2", 1),
           ("Plant_Grass_Sharp_Short", 5), ("Plant_Bush", 2), ("Plant_Flower_Tall_Blue", 1)]
IRON = "Metal_Goblin_Iron"
IRON_SMOOTH = "Metal_Goblin_Iron_Smooth"
IRON_HALF = "Metal_Goblin_Iron_Half"
IRON_ORNATE = "Metal_Goblin_Iron_Ornate"
IRON_FENCE = "Metal_Goblin_Iron_Fence"
IRON_PLATFORM = "Metal_Goblin_Iron_Bars_Platforms"
IRON_STACK = "Metal_Goblin_Iron_Stack"
IRON_CHAINS = "Metal_Goblin_Iron_Chains_Vertical"
IRON_ROOF = "Metal_Goblin_Iron_Roof_Flat"
PIPE = "Metal_Goblin_Iron_Pipe_Large"
PIPE_GLOWY = "Metal_Goblin_Iron_Pipe_Large_Glowy"
GOBLIN_BRAZIER = "Furniture_Goblin_Brazier"
GOBLIN_CANDLE = "Furniture_Goblin_Candle"
GOBLIN_POT = "Furniture_Goblin_Pot_Loot"
GOBLIN_TABLE = "Furniture_Goblin_Table"
GOBLIN_STOOL = "Furniture_Goblin_Stool"
CRATE = "Furniture_Ancient_Crate"
BARREL = "Furniture_Ancient_Barrel"
SACK = "Furniture_Ancient_Sack"
SCRAP = "Deco_Scrap_Pile_Shiny"
RUBBLE = [("Rubble_Quartzite", 3), ("Rubble_Quartzite_Medium", 1), ("Rubble_Marble", 1)]

# CommonLib's own hub blocks (Server/Item/Items/Hub).
DOOR_TEMPLE = "Zc_Hub_Door_Temple"
DOOR_OVERWORLD = "Zc_Hub_Door_Overworld"
SIGN_BUILDING = "Zc_Hub_Sign_Building"
MAP_BOARD = "Zc_Hub_Map_Board"
NOTICE_BOARD = "Zc_Hub_Notice_Board"
LORE_WELCOME = "Zc_Hub_Lore_Welcome"
LORE_WORK_ORDER = "Zc_Hub_Lore_Work_Order"
LORE_KWEEBEC = "Zc_Hub_Lore_Kweebec"


def m(x):
    """The cell mirrored across the lot's middle line (a lot of even span has its middle between -1 and 0)."""
    return -1 - x


# ---------------------------------------------------------------------------------------------------------------
# Shapes


def column(cv, x, z, y0, y1, block, rot=0):
    for y in range(y0, y1 + 1):
        cv.put(x, y, z, block, rot)


def pillar(cv, x, z, height, base, middle, cap=None, light=None, y=1):
    """A pillar standing on the floor at Y `y`: its base, middle blocks up to `height`, a cap, and a light on top."""
    cv.put(x, y, z, base)
    column(cv, x, z, y + 1, y + height - 1, middle)
    top = y + height
    if cap:
        cv.put(x, top, z, cap)
        top += 1
    if light:
        cv.put(x, top, z, light)


def tree(cv, x, z, trunk_height, radius, trunk=TRUNK, leaves=LEAVES, salt="tree", y=1):
    """A rough old tree: a trunk and a lumpy crown, which never overwrites anything already drawn."""
    column(cv, x, z, y, y + trunk_height - 1, trunk)
    cy = y + trunk_height
    chance = hk.pick([(True, 5), (False, 1)], salt)
    for dy in range(-radius + 1, radius):
        for dz in range(-radius, radius + 1):
            for dx in range(-radius, radius + 1):
                d = (dx * dx + dz * dz) / float(radius * radius) + (dy * dy) / float(max(1, radius - 1) ** 2)
                if d > 1.05 or not chance(x + dx, cy + dy, z + dz):
                    continue
                c = (x + dx, cy + dy, z + dz)
                if cv.inside(*c) and c not in cv.cells:
                    cv.put(c[0], c[1], c[2], leaves)


def scatter(cv, x0, z0, x1, z1, options, salt, density, y=1, only_on=None):
    """Props on the floor at Y `y` over a rectangle, a stable share of the free cells (`density` out of 100)."""
    roll = hk.pick([(True, density), (False, 100 - density)], salt + "/roll")
    choose = hk.pick(options, salt)
    for z in range(min(z0, z1), max(z0, z1) + 1):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            if not cv.inside(x, y, z) or (x, y, z) in cv.cells or not roll(x, y, z):
                continue
            if only_on and cv.get(x, y - 1, z) not in only_on:
                continue
            cv.put(x, y, z, choose(x, y, z))


def patches(salt, options, size=5):
    """A block per cell that changes in patches about `size` blocks across, not cell by cell."""
    chooser = hk.pick(options, salt)

    def at(x, y, z):
        return chooser(x // size, 0, z // size) if hk.pick([(True, 3), (False, 1)], salt + "/keep")(x, y, z) \
            else chooser(x, y, z)
    return at


def foundation(cv, block=QUARTZ, core=STONE):
    """Stone under the lot's paving, stepping in as it goes down, so its edge reads as built ground from below."""
    lo, hi = cv.lo, cv.hi
    cv.fill(lo, -1, lo, hi, -1, hi, block, over=False)
    cv.fill(lo + 2, -2, lo + 2, hi - 2, -2, hi - 2, block, over=False)
    if cv.below >= 3:
        cv.fill(lo + 5, -3, lo + 5, hi - 5, -3, hi - 5, core, over=False)


# ---------------------------------------------------------------------------------------------------------------
# The Concourse (a Large lot, 64 square)


def concourse(info):
    cv = hk.Canvas(info, 64, "Concourse", below=4)
    lo, hi = cv.lo, cv.hi  # -32, 31

    def ground(x, y, z):
        u, v = x + 0.5, z + 0.5
        au, av = abs(u), abs(v)
        r = math.hypot(u, v)
        if r <= 1.5:
            return VOID_BLOCK
        if r <= 3.6:
            return MARBLE_ORNATE
        if r <= 5.2:
            return QUARTZ_DECO
        if r <= 7.2:
            return CHALK
        if min(au, av) <= 2.5:
            return QUARTZ
        if min(au, av) <= 3.5:
            return QUARTZ_DECO
        if au >= 21 and av >= 21:
            return GRASS
        if max(au, av) >= 29.5:
            return MARBLE_BRICK
        if abs(r - 12.5) <= 0.5:
            return CHALK
        return MARBLE
    cv.fill(lo, 0, lo, hi, 0, hi, ground)
    foundation(cv)

    landing(cv)
    perimeter(cv)
    for x, z in ((0, lo), (0, hi), (lo, 0), (hi, 0)):
        gateway(cv, x, z)
    for sx in (1, -1):
        for sz in (1, -1):
            garden(cv, sx, sz)
    avenue_planters(cv)
    map_board(cv)
    temple_door(cv)
    overworld_door(cv)
    notice_boards(cv)
    construction_corner(cv)
    kweebec_corner(cv)
    moss(cv)
    return cv


def landing(cv):
    """The arrival: void crystal in the floor where players land, the rose round it, crystals on the diagonals and a
    ring of eight lantern pillars just outside, open along the avenues."""
    crystals = hk.pick(VOID_CRYSTALS, "rose")
    for x, z in ((4, 4), (m(4), 4), (4, m(4)), (m(4), m(4))):
        cv.put(x, 1, z, MARBLE_DECO)
        cv.put(x, 2, z, crystals(x, 2, z))
    for a, b in ((5, 10), (10, 5)):
        for x, z in ((a, b), (m(a), b), (a, m(b)), (m(a), m(b))):
            pillar(cv, x, z, 4, MARBLE_PILLAR_BASE, MARBLE_PILLAR, MARBLE_ORNATE, LANTERN)


def perimeter(cv):
    """A low marble balustrade round the lot's edge, open at the four gateways, a lantern pillar every eight."""
    lo, hi = cv.lo, cv.hi
    for i in range(lo, hi + 1):
        for x, z in ((i, lo), (i, hi), (lo, i), (hi, i)):
            along = x if z in (lo, hi) else z
            if abs(along + 0.5) <= 6 or (x, 1, z) in cv.cells:
                continue
            if along % 8 == 0 or along in (lo, hi):
                pillar(cv, x, z, 3, MARBLE_PILLAR_BASE, MARBLE_PILLAR, MARBLE_ORNATE, LANTERN)
            else:
                cv.put(x, 1, z, MARBLE_WALL)


def gateway(cv, x, z):
    """A gateway arch on the lot's edge at (x, z), the side lot's door straight across the street: two quartzite piers,
    a marble lintel and a crown of void crystal, a lantern either end."""
    along_x = z in (cv.lo, cv.hi)

    def at(k):
        return (k, z) if along_x else (x, k)

    for k in (5, 6, m(5), m(6)):
        px, pz = at(k)
        column(cv, px, pz, 1, 6, QUARTZ)
    for k in range(m(7), 7):
        px, pz = at(k)
        cv.put(px, 7, pz, MARBLE_ORNATE if k in (m(7), 6) else MARBLE_BRICK)
        if m(3) <= k <= 2:
            cv.put(px, 8, pz, MARBLE_DECO)
    for k in (m(0), 0):
        px, pz = at(k)
        cv.put(px, 9, pz, VOID_BLOCK)
    for k in (m(7), 6):
        px, pz = at(k)
        cv.put(px, 8, pz, LANTERN)


def garden(cv, sx, sz):
    """An overgrown corner: old trees through the paving, flowers, a Kweebec lantern, a crystal and (south-west) the
    temple's statue."""
    cx = 25 if sx > 0 else m(25)
    cz = 25 if sz > 0 else m(25)
    salt = "garden%d%d" % (sx, sz)
    if (sx, sz) == (1, 1):
        tree(cv, cx + sx, cz + sz, 6, 4, BEECH_TRUNK, BEECH_LEAVES, salt)
    else:
        tree(cv, cx + sx, cz + sz, 5 + (sx < 0), 4, TRUNK, LEAVES, salt)
    tree(cv, cx - sx * 3, cz - sz * 3, 3, 2, TRUNK, LEAVES, salt + "b")
    cv.put(cx - sx * 2, 1, cz + sz * 3, KWEEBEC_LANTERN, over=False)
    cv.put(cx + sx * 3, 1, cz - sz * 2, KWEEBEC_LANTERN, over=False)
    if (sx, sz) == (-1, 1):
        cv.put(cx + sx * 2, 1, cz - sz * 4, STATUE, hk.facing("N"), over=False)
    crystals = hk.pick(VOID_CRYSTALS, salt + "c")
    cv.put(cx - sx * 4, 1, cz + sz * 1, crystals(cx, 1, cz), over=False)
    scatter(cv, cx - 8, cz - 8, cx + 8, cz + 8, FLOWERS, salt + "f", 40, only_on=(GRASS,))


def planter(cv, x, z, salt, height=4, radius=2):
    """A square planter on the paving: grass in a quartzite rim, a young tree or a Kweebec lantern in it."""
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            cv.put(x + dx, 0, z + dz, GRASS)
            if (dx, dz) != (0, 0):
                cv.put(x + dx, 1, z + dz, QUARTZ_HALF)
    tree(cv, x, z, height, radius, TRUNK, LEAVES, salt)


def avenue_planters(cv):
    """Young trees in planters lining the four avenues, two each side."""
    for d in (16, 24):
        for x, z in ((6, d), (m(6), d), (6, m(d)), (m(6), m(d)), (d, 6), (d, m(6)), (m(d), 6), (m(d), m(6))):
            planter(cv, x, z, "planter%d,%d" % (x, z), height=3 + (d == 24), radius=2)


def map_board(cv):
    """The hub map ahead-left of the arrival, just off the north avenue so the way north stays open: a framed board
    three wide facing the landing, the welcome note on its stand at its right end, the Porter beside that on the
    avenue's edge, and the temple's statue on a plinth across the avenue to match it."""
    z = -7
    x0 = m(9)
    for x in (x0, x0 + 4):
        pillar(cv, x, z, 3, MARBLE_PILLAR_BASE, MARBLE_PILLAR)
    cv.fill(x0 + 1, 1, z, x0 + 3, 1, z, MARBLE_DECO)
    for x in range(x0 + 1, x0 + 4):
        cv.put(x, 2, z, MAP_BOARD, hk.facing("S"))
    cv.fill(x0, 3, z, x0 + 4, 3, z, MARBLE_ORNATE)
    cv.put(x0 + 2, 4, z, LANTERN)
    cv.put(x0 + 5, 1, z + 1, MARBLE_DECO)
    cv.put(x0 + 5, 2, z + 1, LORE_WELCOME, hk.facing("S"))
    cv.put(m(x0 + 2), 1, z, MARBLE_ORNATE)
    cv.put(m(x0 + 2), 2, z, STATUE, hk.facing("S"))


def niche(cv, cx, cz, wall, trim, roof):
    """A three-sided niche round a door pad at (cx, cz - 1), open to the south: walls 7 wide and 5 high, a flat roof,
    trim on the front corners."""
    for x in range(cx - 3, cx + 4):
        column(cv, x, cz - 3, 1, 5, wall)
    for z in range(cz - 2, cz + 2):
        column(cv, cx - 3, z, 1, 5, wall)
        column(cv, cx + 3, z, 1, 5, wall)
    for x in range(cx - 3, cx + 4):
        for z in range(cz - 3, cz + 2):
            cv.put(x, 6, z, roof)
    for x in (cx - 3, cx + 3):
        cv.put(x, 6, cz + 1, trim)
        cv.put(x, 7, cz + 1, trim)


def temple_door(cv):
    """The way back to the temple, ahead-left of the arrival: a marble niche round a portal pad in the temple's own
    gateway shape, its pale braziers either side, the goblins' painting of the temple on its back wall."""
    cx, cz = m(15), m(15)
    niche(cv, cx, cz, MARBLE_BRICK, MARBLE_ORNATE, MARBLE_HALF)
    cv.fill(cx - 2, 0, cz - 2, cx + 2, 0, cz + 2, MARBLE_ORNATE)
    cv.put(cx, 1, cz - 1, DOOR_TEMPLE, hk.facing("S"))
    cv.put(cx - 1, 3, cz - 2, "Goblin_Painting_ForgottenTemple", hk.facing("S"))
    for x in (cx - 4, cx + 4):
        cv.put(x, 1, cz + 2, BRAZIER)
    cv.put(cx, 7, cz - 1, LANTERN)
    for x in (cx - 4, cx + 4):
        cv.put(x, 1, cz - 3, MOSS)
        cv.put(x, 2, cz - 3, MOSS_RUG)


def overworld_door(cv):
    """The way out to the overworld, ahead-right of the arrival: a goblin-built iron niche round the same pad, goblin
    braziers, a glowing pipe and the goblins' painting of a traveller."""
    cx, cz = 15 - 1, m(15)
    niche(cv, cx, cz, IRON, IRON_ORNATE, IRON_HALF)
    cv.fill(cx - 2, 0, cz - 2, cx + 2, 0, cz + 2, IRON_SMOOTH)
    cv.put(cx, 1, cz - 1, DOOR_OVERWORLD, hk.facing("S"))
    cv.put(cx - 1, 3, cz - 2, "Goblin_Painting_Portal", hk.facing("S"))
    for x in (cx - 4, cx + 4):
        cv.put(x, 1, cz + 2, GOBLIN_BRAZIER)
    column(cv, cx + 4, cz - 3, 1, 8, PIPE_GLOWY)
    cv.put(cx - 4, 1, cz - 3, IRON_STACK)
    cv.put(cx - 4, 2, cz - 3, GOBLIN_POT)


def notice_boards(cv):
    """The boards behind the arrival, a pair either side of the south avenue, each framed like the map and lit, a bench
    before each pair."""
    z = 13
    for x0 in (m(13), 8):
        for x in (x0, x0 + 5):
            pillar(cv, x, z, 3, MARBLE_PILLAR_BASE, MARBLE_PILLAR)
        cv.fill(x0 + 1, 1, z, x0 + 4, 1, z, MARBLE_DECO)
        for x in range(x0 + 1, x0 + 5):
            cv.put(x, 2, z, NOTICE_BOARD)
        cv.fill(x0, 3, z, x0 + 5, 3, z, MARBLE_ORNATE)
        cv.put(x0 + 2, 4, z, LANTERN)
        cv.put(x0 + 3, 4, z, LANTERN)
        cv.put(x0 + 2, 1, z - 3, BENCH, hk.facing("S"))


def construction_corner(cv):
    """The Concourse's own unfinished corner, south-east: a pavilion still in scaffolding, the goblin builder's pipe,
    crates, scrap and the goblins' work order on their table."""
    x0, z0 = 9, 17
    cv.fill(x0, 0, z0, x0 + 8, 0, z0 + 6, QUARTZ_SMOOTH)
    for (x, z), h in (((x0, z0), 5), ((x0 + 8, z0), 5), ((x0, z0 + 6), 2), ((x0 + 8, z0 + 6), 1)):
        pillar(cv, x, z, h, QUARTZ_PILLAR_BASE, QUARTZ_PILLAR, QUARTZ_DECO if h == 5 else None)
    for x in range(x0, x0 + 9):
        cv.put(x, 7, z0, MARBLE_BRICK)
    for x in (x0 - 1, x0 + 4, x0 + 9):
        column(cv, x, z0 + 7, 1, 6, IRON)
    for x in range(x0 - 1, x0 + 10):
        cv.put(x, 4, z0 + 7, IRON_PLATFORM, over=False)
    column(cv, x0 + 4, z0 + 7, 7, 7, IRON)
    cv.put(x0 + 4, 8, z0 + 7, GOBLIN_BRAZIER)
    column(cv, x0 + 3, z0 + 3, 1, 3, PIPE)
    cv.put(x0 + 5, 1, z0 + 2, IRON_STACK)
    cv.put(x0 + 6, 1, z0 + 2, IRON_STACK)
    cv.put(x0 + 6, 2, z0 + 2, IRON_STACK)
    for x, z in ((x0 + 10, z0 + 2), (x0 + 11, z0 + 2), (x0 + 10, z0 + 3)):
        cv.put(x, 1, z, CRATE)
    cv.put(x0 + 10, 2, z0 + 2, CRATE)
    cv.put(x0 + 11, 1, z0 + 4, SCRAP)
    cv.put(x0 - 1, 1, z0 + 1, SCRAP)
    rubble = hk.pick(RUBBLE, "concourse-site")
    for x, z in ((x0 + 2, z0 + 5), (x0 + 7, z0 + 4), (x0 - 1, z0 + 2)):
        cv.put(x, 1, z, rubble(x, 1, z))
    cv.put(x0 - 2, 1, z0 + 3, GOBLIN_TABLE)
    cv.put(x0 - 2, 2, z0 + 3, LORE_WORK_ORDER, hk.facing("W"))
    cv.put(x0 - 2, 2, z0 + 4, GOBLIN_CANDLE)
    cv.put(x0 - 3, 1, z0 + 3, GOBLIN_STOOL)


def kweebec_corner(cv):
    """The Kweebecs' bit of the old garden, north-west: a pot, a lantern and their note on its stand."""
    x, z = m(20), m(21)
    cv.put(x, 1, z, MARBLE_DECO, over=False)
    cv.put(x, 2, z, LORE_KWEEBEC, hk.facing("E"))
    cv.put(x - 1, 1, z, TEMPLE_POT, over=False)
    cv.put(x, 1, z + 1, KWEEBEC_LANTERN, over=False)


def moss(cv):
    """Moss creeping over the paving's outer half, never on the avenues."""
    patch = hk.pick([(MOSS_RUG, 1), (None, 11)], "moss")
    for z in range(cv.lo + 1, cv.hi):
        for x in range(cv.lo + 1, cv.hi):
            r = max(abs(x + 0.5), abs(z + 0.5))
            if 14 <= r <= 28 and (x, 1, z) not in cv.cells and cv.get(x, 0, z) == MARBLE:
                b = patch(x, 1, z)
                if b:
                    cv.put(x, 1, z, b)


# ---------------------------------------------------------------------------------------------------------------
# The scaffolding


SITE_GROUND = [("Soil_Pathway", 6), ("Soil_Gravel", 2), ("Rock_Quartzite_Cobble", 2)]


def site(cv, salt, path_width, path_depth):
    """The building site's ground (packed earth in patches of gravel and cobble), a cobbled way in from the lot's
    front, where the street meets it, and stone underneath."""
    cv.fill(cv.lo, 0, cv.lo, cv.hi, 0, cv.hi, patches(salt, SITE_GROUND, 4))
    half = path_width // 2
    cv.fill(-half, 0, cv.lo, half - 1, 0, cv.lo + path_depth - 1, QUARTZ_COBBLE)
    foundation(cv, STONE, STONE)


def fence(cv, inset, gap):
    """A goblin iron fence round the site, `inset` from the lot's edge, open `gap` blocks wide at the front."""
    lo, hi = cv.lo + inset, cv.hi - inset
    for i in range(lo, hi + 1):
        for x, z in ((i, lo), (i, hi), (lo, i), (hi, i)):
            if z == lo and abs(x + 0.5) <= gap / 2.0:
                continue
            cv.put(x, 1, z, IRON_FENCE, over=False)


def scaffold_tower(cv, x0, z0, height, levels):
    """A goblin scaffold tower: four iron posts round a 3 by 3 footprint, a platform at each of `levels`."""
    for x, z in ((x0, z0), (x0 + 2, z0), (x0, z0 + 2), (x0 + 2, z0 + 2)):
        column(cv, x, z, 1, height, IRON)
    for y in levels:
        for x in range(x0, x0 + 3):
            for z in range(z0, z0 + 3):
                cv.put(x, y, z, IRON_PLATFORM, over=False)


def yard(cv, x0, z0, salt):
    """A pile of building stock at (x0, z0): stacked stone, iron and crates, scrap and rubble round it."""
    cv.fill(x0, 1, z0, x0 + 2, 1, z0 + 1, QUARTZ)
    cv.fill(x0, 2, z0, x0 + 1, 2, z0 + 1, QUARTZ)
    cv.put(x0 + 4, 1, z0, IRON_STACK)
    cv.put(x0 + 4, 2, z0, IRON_STACK)
    cv.put(x0 + 4, 1, z0 + 1, IRON_STACK)
    cv.put(x0 + 1, 1, z0 + 3, CRATE)
    cv.put(x0 + 2, 1, z0 + 3, BARREL)
    cv.put(x0 + 1, 2, z0 + 3, CRATE)
    cv.put(x0 + 3, 1, z0 + 3, SACK)
    scatter(cv, x0 - 2, z0 - 2, x0 + 6, z0 + 5, RUBBLE + [(SCRAP, 1)], salt, 12)


def site_office(cv, x0, z0):
    """The goblins' site office: an iron roof on four posts over their table (two blocks square), stools, a pot and a
    candle."""
    for x, z in ((x0, z0), (x0 + 4, z0), (x0, z0 + 3), (x0 + 4, z0 + 3)):
        column(cv, x, z, 1, 3, IRON)
    for x in range(x0 - 1, x0 + 6):
        for z in range(z0 - 1, z0 + 5):
            cv.put(x, 4, z, IRON_ROOF, over=False)
    cv.put(x0 + 2, 1, z0 + 1, GOBLIN_TABLE)
    cv.put(x0 + 2, 2, z0 + 1, GOBLIN_CANDLE)
    cv.put(x0 + 1, 1, z0 + 1, GOBLIN_STOOL)
    cv.put(x0 + 2, 1, z0 + 3, GOBLIN_STOOL)
    cv.put(x0 + 1, 1, z0 + 2, GOBLIN_POT)


def scaffold_large(info):
    cv = hk.Canvas(info, 64, "Scaffold_Large", below=4)
    site(cv, "large", 6, 18)
    fence(cv, 2, 12)
    lo, hi = cv.lo, cv.hi
    # The hall's footprint: a raised quartzite base and its walls, some courses up, most still to come.
    bx0, bz0, bx1, bz1 = -14, -12, 13, 17
    cv.fill(bx0, 0, bz0, bx1, 0, bz1, QUARTZ)
    cv.fill(bx0 + 1, 1, bz0 + 1, bx1 - 1, 1, bz1 - 1, QUARTZ_SMOOTH)
    rise = {}
    for x in range(bx0, bx1 + 1):
        rise[(x, bz1)] = 5 if x < 0 else 3
        rise[(x, bz0)] = 0 if abs(x + 0.5) <= 3 else (3 if x < -6 else 2)
    for z in range(bz0, bz1 + 1):
        rise[(bx0, z)] = 6 if z > 4 else 4
        rise[(bx1, z)] = 1 + (z - bz0) // 7
    for (x, z), h in sorted(rise.items()):
        if h:
            column(cv, x, z, 1, h, QUARTZ)
    for x, z in ((bx0, bz0), (bx1, bz0), (bx0, bz1), (bx1, bz1)):
        pillar(cv, x, z, 8 if (x, z) != (bx1, bz0) else 4, QUARTZ_PILLAR_BASE, QUARTZ_PILLAR,
               QUARTZ_DECO if (x, z) != (bx1, bz0) else None)
    # An iron frame for the upper floor over the back half, on posts.
    for x in (bx0 + 7, bx1 - 7):
        column(cv, x, bz1 - 6, 2, 8, IRON)
    for x in range(bx0 + 1, bx1):
        cv.put(x, 9, bz1 - 6, IRON_HALF, over=False)
    for z in range(bz1 - 6, bz1):
        cv.put(bx0 + 7, 9, z, IRON_HALF, over=False)
        cv.put(bx1 - 7, 9, z, IRON_HALF, over=False)
    # Goblin scaffold towers against the west wall and the front corner.
    scaffold_tower(cv, bx0 - 4, 2, 10, (5, 9))
    scaffold_tower(cv, bx0 - 4, 9, 7, (5,))
    scaffold_tower(cv, bx1 + 2, bz0 - 1, 9, (4, 8))
    # A crane at the back: a tall post, an arm and a crate on its chain.
    cx, cz = bx1 - 4, bz1 - 3
    column(cv, cx, cz, 2, 18, IRON)
    for x in range(cx - 11, cx + 3):
        cv.put(x, 19, cz, IRON_HALF)
    column(cv, cx - 10, cz, 13, 18, IRON_CHAINS)
    cv.put(cx - 10, 12, cz, CRATE)
    cv.put(cx + 2, 20, cz, GOBLIN_BRAZIER)
    # Stock waiting, the site office, the builder's pipe by the front of the hall, and the sign by the way in.
    yard(cv, -25, -22, "large-yard")
    yard(cv, 19, -24, "large-yard2")
    yard(cv, 18, 22, "large-yard3")
    yard(cv, -26, 20, "large-yard4")
    site_office(cv, -24, -8)
    column(cv, 4, bz0 - 3, 1, 3, PIPE)
    cv.put(6, 1, bz0 - 3, IRON_STACK)
    cv.put(5, 1, lo + 3, SIGN_BUILDING, hk.facing("N"))
    for x in (m(6), 6):
        column(cv, x, lo + 2, 1, 2, IRON)
        cv.put(x, 3, lo + 2, GOBLIN_BRAZIER)
    return cv


def scaffold_small(info):
    cv = hk.Canvas(info, 24, "Scaffold_Small", below=3)
    site(cv, "small", 4, 6)
    fence(cv, 1, 6)
    lo, hi = cv.lo, cv.hi  # -12, 11
    # A footprint with corner posts and a first platform, the frame of something to come.
    fx0, fz0, fx1, fz1 = -6, -3, 5, 7
    cv.fill(fx0, 0, fz0, fx1, 0, fz1, QUARTZ)
    for x, z in ((fx0, fz0), (fx1, fz0), (fx0, fz1), (fx1, fz1)):
        column(cv, x, z, 1, 6, IRON)
    for x in range(fx0, fx1 + 1):
        cv.put(x, 5, fz1, IRON_PLATFORM, over=False)
    for z in range(fz0 + 1, fz1):
        cv.put(fx0, 5, z, IRON_PLATFORM, over=False)
    for x in range(fx0 + 1, fx0 + 5):
        column(cv, x, fz1, 1, 3 - (x - fx0) // 2, QUARTZ)
    for z in range(fz0 + 1, fz0 + 4):
        column(cv, fx0, z, 1, 2, QUARTZ)
    cv.put(fx1 - 2, 1, fz0 + 2, CRATE)
    cv.put(fx1 - 2, 2, fz0 + 2, CRATE)
    cv.put(fx1 - 1, 1, fz0 + 2, BARREL)
    cv.put(fx1 - 3, 1, fz0 + 2, IRON_STACK)
    cv.put(fx0 + 2, 1, fz0 + 3, SCRAP)
    rubble = hk.pick(RUBBLE, "small")
    for x, z in ((fx0 + 4, fz1 - 2), (fx1 + 2, fz1 - 1), (fx0 - 2, fz0 + 1), (fx1 + 3, fz0)):
        cv.put(x, 1, z, rubble(x, 1, z))
    cv.put(4, 1, lo + 2, SIGN_BUILDING, hk.facing("N"))
    column(cv, m(3), lo + 1, 1, 1, IRON)
    cv.put(m(3), 2, lo + 1, GOBLIN_BRAZIER)
    return cv


DESIGNS = (("Concourse", concourse), ("Scaffold_Large", scaffold_large), ("Scaffold_Small", scaffold_small))


# ---------------------------------------------------------------------------------------------------------------
# The spot check


def district_files():
    out = {}
    for fn in sorted(os.listdir(DISTRICTS)):
        if fn.endswith(".json"):
            with open(os.path.join(DISTRICTS, fn), "r", encoding="utf-8-sig") as f:
                out[fn[:-5]] = json.load(f)
    return out


def prefab_key(district):
    base = ((district.get("Prefab") or {}).get("Base") or "")
    return base.rsplit(".", 1)[-1] if base.startswith("ZiggfreedCommon.Hub.") else None


def check_spots(canvases):
    """Problems with each district's Anchors over the canvas its Prefab names, and a Floor other than STREET."""
    problems = []
    for did, district in district_files().items():
        cv = canvases.get(prefab_key(district))
        if cv is None:
            continue
        floor = (district.get("Prefab") or {}).get("Floor")
        if floor != STREET:
            problems.append("%s lays its streets in %s, not the hub's street block %s" % (did, floor, STREET))
        for anchor in district.get("Anchors") or []:
            at = anchor.get("At") or {}
            x, y, z = int(at.get("X", 0)), int(at.get("Y", 0)), int(at.get("Z", 0))
            name = anchor.get("Name")
            if not cv.inside(x, y, z):
                problems.append("%s: spot %s at %s lies outside the lot" % (did, name, (x, y, z)))
                continue
            below = cv.get(x, y - 1, z)
            if not below or below == "Empty" or not solid(cv, below):
                problems.append("%s: spot %s at %s has no ground under it (%s)" % (did, name, (x, y, z), below))
            for dy in (0, 1):
                here = cv.get(x, y + dy, z)
                if here and here != "Empty" or (x, y + dy, z) in cv.fillers:
                    problems.append("%s: spot %s at %s is blocked by %s at Y %d" % (did, name, (x, y, z), here,
                                                                                    y + dy))
    return problems


def solid(cv, name):
    return any(b[1][1] >= 0.99 for b in cv.info.get(name).boxes)


def build(write=True):
    info = bi.BlockInfo([bi.vanilla_server(HERE), RESOURCES])
    canvases = {}
    problems = []
    for name, design in DESIGNS:
        cv = design(info)
        canvases[name] = cv
        problems.extend(cv.problems)
    problems.extend(check_spots(canvases))
    if problems:
        raise hk.DrawError("\n".join(problems))
    if write:
        for name, cv in canvases.items():
            path = os.path.join(PREFABS, name + ".prefab.json")
            changed = hk.write(cv, path)
            print("%s %s (%d cells)" % ("wrote" if changed else "unchanged", os.path.relpath(path, REPO),
                                         len(cv.cells)))
    return canvases


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="build in memory and write nothing")
    args = ap.parse_args(argv)
    try:
        build(write=not args.check)
    except hk.DrawError as e:
        print(str(e), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
