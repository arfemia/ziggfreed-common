"""Import one of vanilla's modular dungeon kits as CommonLib room files (DungeonRooms, PD1's room contract).

    python -I tools/rooms/import_kit.py --assets <HytaleAssets> --kit Stone --out <DungeonRooms/Kits> [--report <file>]

Reads the kit's cave-node graph (`<CaveRoot>/<Graph>/**/*.node.json`, kits.json) and writes one room file per prefab
pool the graph names, `<out>/<Kit>/Zc_Kit_<Kit>_<Piece>.json`, deleting any other `Zc_Kit_<Kit>_*.json` there, so a
re-import after a Hytale patch leaves exactly what the graph holds now. The output is deterministic: an unchanged input
writes byte-identical files.

How vanilla places a piece (read from the release source):
- A child's origin is its parent's origin plus the child entry's `Offset` turned by the parent's rotation, and its
  rotation is the parent's plus the entry's `Rotation` (PrefabCaveNodeShape.java:226-233; a PREFAB node has zero extent,
  so its "end" is its origin, CaveGenerator.java:187-191). `Anchor` is unset in every vanilla dungeon graph.
- A piece's prefab is pasted with its anchor at that origin (PrefabCaveNodeShape.java:159-181, BsonPrefabBufferDeserializer
  .java:86-91), and vanilla anchors every connectable piece at the floor cell of its doorway, its body running north
  (-Z) from it. So a piece's own entry is a door at its anchor facing South (+Z).
- A node whose prefab is empty (Crypt_Empty) is an inert router: its children are placed through its transform.

How a door is derived: each child entry joins one of the child's doors (its entry, unless that lands outside the parent,
when vanilla attached the child by a side door) to a door of the parent one step back along the child door's facing. A
derived door is then pushed out to the face of its room's geometry, so two joined rooms meet face to face with no shared
column (vanilla sometimes overlaps a piece's frame by one block, which PD1's box test would refuse), and doors on one wall
less than two blocks apart are merged. The doors vanilla joins form classes; each class takes a Kind from kits.json.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prefabio as pio  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ENTRY_FACING = "South"
DOORWAY_BELOW = 1
DOORWAY_ABOVE = 5
MERGE_DISTANCE = 1


# ---------------------------------------------------------------------------------------------------------------
# Graph


class Graph:
    """Every node file under the kit's graph folders, named as the engine names them (dot path under CaveRoot)."""

    def __init__(self, cave_root, folders):
        self.nodes = {}
        self.problems = []
        for folder in folders:
            base = os.path.join(cave_root, *folder.split("/"))
            for dirpath, dirnames, filenames in os.walk(base):
                dirnames.sort()
                for fn in sorted(filenames):
                    if not fn.endswith(".node.json"):
                        continue
                    path = os.path.join(dirpath, fn)
                    name = os.path.relpath(path, cave_root)[:-len(".node.json")].replace(os.sep, ".")
                    try:
                        self.nodes[name] = pio.load_lenient_json(path)
                    except ValueError as e:
                        self.problems.append("node %s does not parse: %s" % (name, e))
        self._inline = {}

    def node(self, name):
        return self.nodes.get(name) or self._inline.get(name)

    def pools(self, name):
        doc = self.node(name)
        if not doc or str(doc.get("Type", "PIPE")).upper() != "PREFAB":
            return []
        pf = doc.get("Prefab")
        if pf is None:
            return []
        return list(pf) if isinstance(pf, list) else [pf]

    def entries(self, name):
        """(child node name, offset, turns) for every child entry, each rotation choice and each node choice."""
        doc = self.node(name)
        children = doc.get("Children") or []
        if isinstance(children, dict):
            children = [children]
        out = []
        for ei, entry in enumerate(children):
            targets = entry.get("Node")
            targets = targets if isinstance(targets, list) else [targets]
            offset = tuple(int(round(float(v))) for v in (entry.get("Offset") or [0, 0, 0]))
            rotation = entry.get("Rotation", 0)
            rotations = rotation if isinstance(rotation, list) else [rotation]
            for ti, target in enumerate(targets):
                if isinstance(target, dict):
                    inline = "%s#%d.%d" % (name, ei, ti)
                    self._inline[inline] = target
                    target = inline
                if not isinstance(target, str):
                    continue
                for r in rotations:
                    out.append((target, offset, pio.turns_from_rotation(r)))
        return out


# ---------------------------------------------------------------------------------------------------------------
# Pools


class Pool:
    """A prefab pool: its variants, the union of their boxes, and which cells any variant fills."""

    def __init__(self, prefabs_root, name):
        self.name = name
        self.files = pio.pool_files(prefabs_root, name)
        self.variants = [pio.load_prefab(f) for f in self.files]
        self.box = pio.union_bounds([v.bounds() for v in self.variants])
        self.columns = {}
        for v in self.variants:
            for (x, y, z) in list(v.blocks) + list(v.fluids):
                self.columns.setdefault((x, z), set()).add(y)

    def inert(self):
        return all(v.is_empty() for v in self.variants)

    def in_box(self, cell):
        return self.box is not None and all(self.box[0][i] <= cell[i] <= self.box[1][i] for i in range(3))

    def doorway_filled(self, cell):
        """True when some variant has a block in the doorway's column, from just under its floor to above its lintel."""
        ys = self.columns.get((cell[0], cell[2]))
        if not ys:
            return False
        return any(cell[1] - DOORWAY_BELOW <= y <= cell[1] + DOORWAY_ABOVE for y in ys)

    def floor_continues(self, cell):
        """True when some variant has a block at the doorway's floor, or just under it, at this column."""
        ys = self.columns.get((cell[0], cell[2]))
        return bool(ys) and (cell[1] in ys or cell[1] - 1 in ys)

    def snap(self, cell, facing):
        """Push a door cell out along its facing while this pool's floor goes on under the doorway (a frame, a lip)."""
        while True:
            nxt = pio.step(cell, facing)
            if self.in_box(nxt) and self.floor_continues(nxt):
                cell = nxt
            else:
                return cell

    def accept(self, cell, facing):
        """The door this pool has at a cell a join implies, or None when the cell is not part of this pool."""
        if not self.in_box(cell):
            back = pio.step(cell, facing, -1)
            if not self.in_box(back):
                return None
            cell = back
        if not self.doorway_filled(cell):
            return None
        return self.snap(cell, facing)


# ---------------------------------------------------------------------------------------------------------------
# Union-find over (pool, door)


class Classes:
    def __init__(self):
        self.parent = {}

    def find(self, k):
        self.parent.setdefault(k, k)
        while self.parent[k] != k:
            self.parent[k] = self.parent[self.parent[k]]
            k = self.parent[k]
        return k

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            if rb < ra:
                ra, rb = rb, ra
            self.parent[rb] = ra


def starts_with_any(name, prefixes):
    return any(name.startswith(p) for p in prefixes)


def implied_parent_door(offset, turns, child_door):
    """The parent's door a child door meets when the child sits at `offset` turned by `turns` in the parent's frame."""
    at, facing = child_door
    turned = pio.rotate_cell(at, turns)
    world_at = (offset[0] + turned[0], offset[1] + turned[1], offset[2] + turned[2])
    world_facing = pio.rotate_facing(facing, turns)
    return pio.step(world_at, world_facing), pio.OPPOSITE[world_facing]


# ---------------------------------------------------------------------------------------------------------------
# The import


class KitImport:
    def __init__(self, assets, config, kit):
        self.kit = kit
        self.config = config
        self.cfg = config["Kits"][kit]
        self.cave_root = os.path.join(assets, *config["CaveRoot"].split("/"))
        self.prefabs_root = os.path.join(assets, "Server", "Prefabs")
        self.graph = Graph(self.cave_root, self.cfg["Graph"])
        self.pools = {}
        self.problems = list(self.graph.problems)
        self.notes = []

    def pool(self, name):
        if name not in self.pools:
            self.pools[name] = Pool(self.prefabs_root, name)
        return self.pools[name]

    def in_scope(self, pool_name):
        return starts_with_any(pool_name, self.cfg["Pools"])

    def is_router(self, node):
        pools = self.graph.pools(node)
        return bool(pools) and all(self.pool(p).inert() for p in pools)

    def real_children(self, node, offset=(0, 0, 0), turns=0, trail=()):
        """Children of a node, placed through any inert router nodes between (their transforms composed)."""
        out = []
        for target, off, t in self.graph.entries(node):
            if self.graph.node(target) is None:
                self.problems.append("node %s names a missing node %s" % (node, target))
                continue
            turned = pio.rotate_cell(off, turns)
            o2 = (offset[0] + turned[0], offset[1] + turned[1], offset[2] + turned[2])
            t2 = (turns + t) % 4
            if self.is_router(target):
                if target in trail:
                    continue
                out.extend(self.real_children(target, o2, t2, trail + (target,)))
            else:
                out.append((target, o2, t2))
        return out

    def run(self):
        # Pools in scope and their nodes.
        self.pool_nodes = {}
        for node in sorted(self.graph.nodes):
            for p in self.graph.pools(node):
                if self.in_scope(p):
                    self.pool_nodes.setdefault(p, []).append(node)
        for p in sorted(self.pool_nodes):
            pool = self.pool(p)
            if not pool.variants:
                self.problems.append("pool %s has no prefab files" % p)
            elif pool.inert():
                self.notes.append("pool %s is an empty router, not a room" % p)
        self.room_pools = sorted(p for p in self.pool_nodes if self.pool(p).variants and not self.pool(p).inert())

        # Edges between rooms in scope.
        edges = set()
        for node in sorted(self.graph.nodes):
            parents = [p for p in self.graph.pools(node) if p in self.room_pools]
            if not parents:
                continue
            for child, off, t in self.real_children(node):
                for c in self.graph.pools(child):
                    if c in self.room_pools:
                        for p in parents:
                            edges.add((p, c, off, t, node, child))
        self.edges = sorted(edges)

        # Doors: every room's own entry, then whatever the joins imply, until nothing changes.
        self.doors = {p: {} for p in self.room_pools}
        self.classes = Classes()
        for p in self.room_pools:
            self.add_door(p, *self.entry_key(p))
        pending = list(self.edges)
        while True:
            still = []
            for e in pending:
                if not self.resolve(e):
                    still.append(e)
            if len(still) == len(pending):
                break
            pending = still
        self.unplaced = pending

        self.merge_close_doors()
        self.name_kinds()
        self.fit_boxes()

    def fit_boxes(self):
        """Each room's Box is the union of its variants, pulled back to a doorway's wall where no more than ClipDepth
        layers of blocks stand proud of it; a room with a doorway still inside its Box is left out."""
        depth = int(self.config.get("ClipDepth", 1))
        self.boxes, self.clipped, self.excluded = {}, {}, {}
        for p in self.room_pools:
            pool = self.pool(p)
            lo, hi = list(pool.box[0]), list(pool.box[1])
            for (at, facing) in sorted(self.doors[p]):
                vx, vz = pio.FACING_VEC[facing]
                axis = 0 if vx else 2
                sign = vx or vz
                beyond = [c for v in pool.variants for c in list(v.blocks) + list(v.fluids)
                          if (c[axis] - at[axis]) * sign > 0]
                if not beyond:
                    continue
                if max((c[axis] - at[axis]) * sign for c in beyond) > depth:
                    continue
                if sign > 0:
                    hi[axis] = min(hi[axis], at[axis])
                else:
                    lo[axis] = max(lo[axis], at[axis])
            box = (tuple(lo), tuple(hi))
            self.boxes[p] = box
            self.clipped[p] = sum(1 for v in pool.variants for c in list(v.blocks) + list(v.fluids)
                                  if not all(box[0][i] <= c[i] <= box[1][i] for i in range(3)))
            inside = sorted((at, f) for (at, f) in self.doors[p]
                            if all(box[0][i] <= pio.step(at, f)[i] <= box[1][i] for i in range(3)))
            if inside:
                self.excluded[p] = "door%s %s stay%s inside the room's box (more than %d layer%s of the room stand " \
                                   "beyond the doorway's wall), so no neighbour or cap could ever meet %s" % (
                                       "s" if len(inside) > 1 else "", ", ".join("%s %s" % d for d in inside),
                                       "" if len(inside) > 1 else "s", depth, "" if depth == 1 else "s",
                                       "them" if len(inside) > 1 else "it")
        self.output_pools = [p for p in self.room_pools if p not in self.excluded]

    def add_door(self, pool, at, facing):
        key = (at, facing)
        self.doors[pool].setdefault(key, {"joins": 0})
        self.classes.find((pool, key))
        return key

    def resolve(self, edge):
        parent, child, off, t, node, child_node = edge
        pool = self.pool(parent)
        entry = self.entry_key(child)
        ordered = ([entry] if entry in self.doors[child] else []) + sorted(k for k in self.doors[child] if k != entry)
        for ck in ordered:
            cell, facing = implied_parent_door(off, t, ck)
            at = pool.accept(cell, facing)
            if at is None:
                continue
            pk = self.add_door(parent, at, facing)
            self.doors[parent][pk]["joins"] += 1
            self.doors[child][ck]["joins"] += 1
            self.classes.union((parent, pk), (child, ck))
            return True
        return False

    def entry_key(self, pool_name):
        pool = self.pool(pool_name)
        return (pool.snap((0, 0, 0), ENTRY_FACING), ENTRY_FACING)

    def merge_close_doors(self):
        """Doors on one wall of one room closer than two blocks are one doorway: keep the busier one."""
        self.merged = []
        for p in self.room_pools:
            keys = sorted(self.doors[p])
            gone = set()
            for i, a in enumerate(keys):
                if a in gone:
                    continue
                for b in keys[i + 1:]:
                    if b in gone or a[1] != b[1] or a[0][1] != b[0][1]:
                        continue
                    axis = 0 if a[1] in ("North", "South") else 2
                    depth = 2 if axis == 0 else 0
                    if a[0][depth] != b[0][depth] or abs(a[0][axis] - b[0][axis]) > MERGE_DISTANCE:
                        continue
                    da, db = self.doors[p][a], self.doors[p][b]
                    keep, drop = (a, b) if (da["joins"], b) >= (db["joins"], a) else (b, a)
                    self.doors[p][keep]["joins"] += self.doors[p][drop]["joins"]
                    self.classes.union((p, keep), (p, drop))
                    self.merged.append((p, drop, keep))
                    gone.add(drop)
                    if drop == a:
                        break
            for k in gone:
                del self.doors[p][k]

    def name_kinds(self):
        """Each door class takes the Kind of the first rule naming a pool one of its doors sits on, else Default."""
        rules = self.cfg["Kinds"].get("Rules", [])
        default = self.cfg["Kinds"]["Default"]
        members = {}
        for p in self.room_pools:
            for k in self.doors[p]:
                members.setdefault(self.classes.find((p, k)), []).append(p)
        self.kind_of_class = {}
        for root, pools in members.items():
            kind = default
            for rule in rules:
                if any(starts_with_any(p, rule["Pools"]) for p in pools):
                    kind = rule["Kind"]
                    break
            self.kind_of_class[root] = kind

    def kind(self, p, k):
        return self.kind_of_class[self.classes.find((p, k))]

    # -----------------------------------------------------------------------------------------------------------
    # Output

    def piece(self, pool_name):
        root = self.cfg["PieceRoot"]
        name = pool_name[len(root):] if pool_name.startswith(root) else pool_name
        if name.endswith(".*"):
            name = name[:-2]
        return name.replace(".", "_")

    def room_id(self, pool_name):
        return "Zc_Kit_%s_%s" % (self.kit, self.piece(pool_name))

    def roles(self, pool_name):
        folder = pool_name[:-2] if pool_name.endswith(".*") else pool_name
        best = None
        for suffix, roles in self.cfg["Roles"].items():
            if folder == suffix or folder.endswith("." + suffix):
                if best is None or len(suffix) > len(best[0]):
                    best = (suffix, roles)
        roles = list(best[1]) if best else []
        if not best:
            self.problems.append("pool %s matches no role rule" % pool_name)
        if "Cap" not in roles and len(self.doors[pool_name]) == 1:
            roles.append("DeadEnd")
        return sorted(set(roles))

    def own_tags(self, pool_name):
        """The room's tags as the engine's own Tags map (key -> set of values): the kit's Tags, then every TagRule
        whose pools name this one. The engine expands a map into each key, each value and each Key=Value."""
        tags = {}

        def add(more):
            for key, values in more.items():
                tags.setdefault(key, set()).update(values)

        add(self.cfg.get("Tags", {}))
        for rule in self.cfg.get("TagRules", []):
            if starts_with_any(pool_name, rule["Pools"]):
                add(rule["Tags"])
        return tags

    def tags(self, pool_name):
        """A room's own tags; a Cap also carries, key by key, every tag value of the rooms whose doors it can close
        (any room with a door of its Kind), so a plan filtering on a kit or section still finds a cap for each door."""
        tags = self.own_tags(pool_name)
        if "Cap" in self.roles(pool_name):
            kinds = {self.kind(pool_name, k) for k in self.doors[pool_name]}
            for q in self.output_pools:
                if any(self.kind(q, k) in kinds for k in self.doors[q]):
                    for key, values in self.own_tags(q).items():
                        tags.setdefault(key, set()).update(values)
        return {key: sorted(values) for key, values in sorted(tags.items())}

    def door_list(self, p):
        order = {f: i for i, f in enumerate(pio.FACINGS)}
        out = []
        for (at, facing) in sorted(self.doors[p], key=lambda k: (order[k[1]], k[0])):
            out.append({"At": pio.xyz_dict(at), "Facing": facing, "Kind": self.kind(p, (at, facing))})
        return out

    def room_doc(self, p):
        pool = self.pool(p)
        n = len(pool.variants)
        nodes = self.pool_nodes[p]
        folder = p[:-2] if p.endswith(".*") else p
        what = "every prefab under %s, %d interchangeable variant%s" % (folder, n, "" if n == 1 else "s") \
            if p.endswith(".*") else "the one prefab %s" % p
        comment = [
            "%s kit, piece %s: %s." % (self.cfg["Label"][0].upper() + self.cfg["Label"][1:], self.piece(p), what),
            "Imported from vanilla's cave-node graph %s (%d node%s use this pool) by tools/rooms/import_kit.py. Re-run the "
            "importer when Hytale changes the kit instead of editing this file." % (
                ", ".join(self.cfg["Graph"]), len(nodes), "" if len(nodes) == 1 else "s"),
            "Each door is a doorway vanilla's graph joins to another piece; a door only meets a door of its own Kind, and "
            "one left open takes a Cap room of that Kind.",
        ]
        if self.clipped[p]:
            comment.append(
                "Box covers every variant except %d block%s standing one block proud of a doorway's wall, left out so "
                "the doorway sits on the Box's face." % (self.clipped[p], "" if self.clipped[p] == 1 else "s"))
        else:
            comment.append("Box covers every variant.")
        fill = self.cfg.get("Fill")
        if fill:
            comment.append("Vanilla pastes these pieces into solid rock, so Fill (%s) fills the cells a piece leaves "
                           "unlisted inside its Box." % fill)
        if "Cap" in self.roles(p):
            comment.append("As a Cap it carries, key by key, every tag value of the rooms whose doors it can close.")
        box = self.boxes[p]
        doc = {
            "$Comment": comment,
            "Prefab": p,
            "Box": {"Min": pio.xyz_dict(box[0]), "Max": pio.xyz_dict(box[1])},
            "Doors": self.door_list(p),
            "Roles": self.roles(p),
            "Tags": self.tags(p),
        }
        if fill:
            doc["Fill"] = fill
        return doc

    def write(self, out_root):
        folder = os.path.join(out_root, self.kit)
        os.makedirs(folder, exist_ok=True)
        written = set()
        changed = 0
        for p in self.output_pools:
            name = self.room_id(p) + ".json"
            written.add(name)
            if pio.write_json(os.path.join(folder, name), self.room_doc(p)):
                changed += 1
        removed = []
        prefix = "Zc_Kit_%s_" % self.kit
        for fn in sorted(os.listdir(folder)):
            if fn.startswith(prefix) and fn.endswith(".json") and fn not in written:
                os.remove(os.path.join(folder, fn))
                removed.append(fn)
        return len(written), changed, removed

    def shell_holes(self, p):
        """Carved (Empty) cells beside a cell no variant fills inside the Box, over all carved cells: vanilla pastes
        these pieces into solid rock, so a high share means the piece needs rock around it in a void world."""
        pool = self.pool(p)
        lo, hi = self.boxes[p]
        holes = carved = 0
        for v in pool.variants:
            for c, b in v.blocks.items():
                if b.get("name") != pio.EMPTY_BLOCK:
                    continue
                carved += 1
                for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                    n = (c[0] + d[0], c[1] + d[1], c[2] + d[2])
                    if n not in v.blocks and all(lo[i] <= n[i] <= hi[i] for i in range(3)):
                        holes += 1
                        break
        return holes, carved

    def report(self):
        lines = ["Kit %s: %d room files from %d graph nodes, %d joins between rooms, %d rooms left out." % (
            self.kit, len(self.output_pools), len(self.graph.nodes), len(self.edges), len(self.excluded))]
        kinds = {}
        caps = {}
        for p in self.output_pools:
            roles = self.roles(p)
            for d in self.door_list(p):
                kinds.setdefault(d["Kind"], 0)
                kinds[d["Kind"]] += 1
                if "Cap" in roles and len(self.doors[p]) == 1:
                    caps.setdefault(d["Kind"], []).append(self.room_id(p))
        for k in sorted(kinds):
            lines.append("  Kind %s: %d doors, caps: %s" % (k, kinds[k], ", ".join(sorted(caps.get(k, []))) or "NONE"))
        for p in self.room_pools:
            pool = self.pool(p)
            box = self.boxes[p]
            holes, carved = self.shell_holes(p)
            share = 100.0 * holes / carved if carved else 0.0
            lines.append("%s  (%s, %d variants, box %s..%s, roles %s, %d clipped, shell holes %d/%d = %.0f%%%s)" % (
                self.room_id(p), p, len(pool.variants), box[0], box[1], ",".join(self.roles(p)), self.clipped[p],
                holes, carved, share, ", NEEDS ROCK AROUND IT" if share >= 10 else ""))
            if p in self.excluded:
                lines.append("    LEFT OUT: " + self.excluded[p])
            cap = "Cap" in self.roles(p)
            for (at, facing) in sorted(self.doors[p]):
                floor = sum(1 for v in pool.variants if v.solid_at(at))
                head = sum(1 for v in pool.variants
                           if not v.solid_at((at[0], at[1] + 1, at[2])) and not v.solid_at((at[0], at[1] + 2, at[2])))
                out = pio.step(at, facing)
                face = not all(box[0][i] <= out[i] <= box[1][i] for i in range(3))
                flags = []
                if not face:
                    flags.append("RECESSED (its outward cell is inside the box)")
                if floor == 0:
                    flags.append("no floor block in any variant")
                if head == 0 and not cap:
                    flags.append("no headroom in any variant")
                d = self.doors[p][(at, facing)]
                lines.append("    door %s %-5s kind %-20s joins %2d  floor %d/%d head %d/%d  %s" % (
                    at, facing, self.kind(p, (at, facing)), d["joins"], floor, len(pool.variants), head,
                    len(pool.variants), "; ".join(flags)))
        for (p, drop, keep) in self.merged:
            lines.append("merged %s door %s into %s" % (self.room_id(p), drop, keep))
        for e in self.unplaced:
            lines.append("UNPLACED join %s -> %s at offset %s turn %d (node %s -> %s): no door of the child meets the "
                         "parent" % (self.room_id(e[0]), self.room_id(e[1]), e[2], e[3], e[4], e[5]))
        for n in sorted(set(self.notes)):
            lines.append("note: " + n)
        for pr in sorted(set(self.problems)):
            lines.append("problem: " + pr)
        return "\n".join(lines) + "\n"


def load_config(path):
    return pio.load_json(path)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--assets", required=True, help="the HytaleAssets folder (holds Server/Prefabs and Server/World)")
    ap.add_argument("--kit", required=True, action="append", help="a kit id from kits.json; repeat for several")
    ap.add_argument("--out", required=True, help="the DungeonRooms/Kits folder the room files go under")
    ap.add_argument("--config", default=os.path.join(HERE, "kits.json"))
    ap.add_argument("--report", help="write the import report (doors, joins, problems) to this file")
    args = ap.parse_args(argv)
    config = load_config(args.config)
    reports = []
    for kit in args.kit:
        imp = KitImport(args.assets, config, kit)
        imp.run()
        total, changed, removed = imp.write(args.out)
        print("%s: %d room files (%d changed, %d removed), %d joins, %d unplaced" % (
            kit, total, changed, len(removed), len(imp.edges), len(imp.unplaced)))
        reports.append(imp.report())
    if args.report:
        pio.write_text(args.report, "\n".join(reports))
    return 0


if __name__ == "__main__":
    sys.exit(main())
