package com.ziggfreed.common.instance.dungeon.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Branch;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Links;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Stratum;
import com.ziggfreed.common.instance.dungeon.layout.LayoutResult.Relax;
import com.ziggfreed.common.instance.dungeon.layout.PlacedRoom.DoorLink;
import com.ziggfreed.common.instance.dungeon.layout.PlacedRoom.Part;

/**
 * Turns a {@link LayoutPlan}, a {@link RoomCatalogue} and a run seed into a placed {@link Layout}. Pure: no engine
 * type, no clock, no shared state, so one seed gives one layout on every server.
 *
 * <p><b>How a try runs.</b> The seed picks one tag per stratum, then rolls each beat's count and the connectors
 * between beats, which fixes the path's length and so each slot's depth. The path is embedded by socket snapping:
 * the entry's anchor stands on the plan's {@code Bounds.Entry} (the bounds' centre unless the plan says), and each
 * next room is turned so one of its doors
 * meets an open door of the previous room of the same kind face to face, and kept only if its turned box stays in
 * the bounds and clears every placed room, and (unless it is the last) it leaves a door open of a kind the next
 * slot's rooms can meet, a gate likewise for its branch's end room. A room on a branch of its own then answers each
 * path room's {@code Needs}, off a path room before it; the plan's side branches follow, a gate room first where one
 * is named; and every door still open takes a cap, a room whose role is {@code Cap} with one door of that kind.
 * Rooms join as a tree: a door never joins a second room by chance, so no loop can bypass a gate or a puzzle.
 *
 * <p><b>Why a door left open always takes its cap.</b> Each open door holds the box of the smallest cap of its kind
 * in front of it, and nothing later is placed over a held box except the room that joins that door. So the last
 * step never finds a doorway it cannot close, and a room whose own open doors would have no room for their caps is
 * refused when it is tried rather than at the end.
 *
 * <p><b>Determinism.</b> Every draw comes from the layout's own {@link SplitMix64}, a stream per beat, per
 * connector run, per branch and one each for the strata, the needs and the caps, so a branch never shifts the path
 * and a cap never shifts a branch. Collections are walked in written or id order, never hash order.
 *
 * <p><b>Retries.</b> A try that cannot finish is dropped whole and the next one runs on a seed derived from the run
 * seed and the try's number, up to the plan's {@code MaxTries}; then the plan is relaxed (optional branches
 * dropped, then every count at its least, {@link Relax}) and tried again, so a run gets a layout whenever its plan
 * can be met at all. When none can, the result says why.
 */
public final class LayoutGenerator {

    /** How many placement checks one try may spend before it gives up: a guard against a pathological catalogue. */
    static final int CHECK_BUDGET = 20_000;

    private static final long STREAM_STRATA = 1;
    private static final long STREAM_NEEDS = 2;
    private static final long STREAM_CAPS = 3;
    private static final long STREAM_BEAT = 1_000;
    private static final long STREAM_LINK = 2_000;
    private static final long STREAM_BRANCH = 3_000;
    private static final long STREAM_TRY = 1_000_000;

    private LayoutGenerator() {
    }

    /** The layout {@code seed} gives {@code plan} over {@code catalogue}, or why there is none. */
    @Nonnull
    public static LayoutResult generate(@Nonnull LayoutPlan plan, @Nonnull RoomCatalogue catalogue, long seed) {
        View view = new View(plan, catalogue);
        String missing = view.missing();
        if (missing != null) {
            return new LayoutResult(null, 0, Relax.NONE, missing);
        }
        int tries = 0;
        String failure = null;
        Relax last = Relax.NONE;
        for (Relax relax : Relax.values()) {
            last = relax;
            for (int t = 0; t < plan.maxTries(); t++) {
                tries++;
                Attempt attempt = new Attempt(view, seed, trySeed(seed, relax, t), relax);
                Layout layout = attempt.run();
                if (layout != null) {
                    return new LayoutResult(layout, tries, relax, null);
                }
                failure = attempt.failure;
            }
        }
        return new LayoutResult(null, tries, last, failure == null ? "no try found a layout" : failure);
    }

    /** The seed of try {@code tryIndex} at relax step {@code relax}. */
    static long trySeed(long seed, @Nonnull Relax relax, int tryIndex) {
        return SplitMix64.derive(seed, STREAM_TRY * (relax.ordinal() + 1) + tryIndex).nextLong();
    }

    /** {@code rooms} in a weighted random order: a heavier room tends to come first (Efraimidis and Spirakis). */
    @Nonnull
    static List<RoomSpec> weightedOrder(@Nonnull List<RoomSpec> rooms, @Nonnull SplitMix64 rng) {
        int n = rooms.size();
        if (n <= 1) {
            return rooms;
        }
        double[] keys = new double[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            keys[i] = -StrictMath.log(1.0 - rng.nextDouble()) / rooms.get(i).weight();
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int byKey = Double.compare(keys[a], keys[b]);
            return byKey != 0 ? byKey : Integer.compare(a, b);
        });
        List<RoomSpec> out = new ArrayList<>(n);
        for (Integer i : order) {
            out.add(rooms.get(i));
        }
        return out;
    }

    /** {@code values} shuffled in place (Fisher and Yates). */
    static void shuffle(@Nonnull int[] values, @Nonnull SplitMix64 rng) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int held = values[i];
            values[i] = values[j];
            values[j] = held;
        }
    }

    // ==================== the plan's view of the catalogue ====================

    /** The rooms a plan may place, by role, and the caps by door kind; built once per call. */
    private static final class View {

        final LayoutPlan plan;
        final RoomCatalogue catalogue;
        final Box bounds;
        final Map<String, List<RoomSpec>> byRole = new HashMap<>();
        final Map<String, List<RoomSpec>> capsByKind = new HashMap<>();
        final Map<String, RoomSpec> heldCap = new HashMap<>();

        View(@Nonnull LayoutPlan plan, @Nonnull RoomCatalogue catalogue) {
            this.plan = plan;
            this.catalogue = catalogue;
            this.bounds = Box.ofSize(plan.bounds());
            for (RoomSpec room : catalogue.rooms()) {
                if (!catalogue.usable(room) || !room.carriesAll(plan.roomTags())) {
                    continue;
                }
                for (String role : room.roles()) {
                    byRole.computeIfAbsent(role, k -> new ArrayList<>()).add(room);
                }
                if (room.isCap()) {
                    String kind = capDoor(room).kind();
                    capsByKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(room);
                    RoomSpec held = heldCap.get(kind);
                    if (held == null || room.box().volume() < held.box().volume()) {
                        heldCap.put(kind, room);
                    }
                }
            }
        }

        @Nonnull
        List<RoomSpec> withRole(@Nonnull String role) {
            List<RoomSpec> rooms = byRole.get(role);
            return rooms == null ? List.of() : rooms;
        }

        @Nonnull
        List<RoomSpec> capsOf(@Nonnull String kind) {
            List<RoomSpec> caps = capsByKind.get(kind);
            return caps == null ? List.of() : caps;
        }

        /**
         * The box the smallest cap of {@code kind} takes in front of an open door at {@code at} facing {@code facing},
         * or null when no cap of that kind exists.
         */
        @Nullable
        Box held(@Nonnull Cell at, @Nonnull Facing facing, @Nonnull String kind) {
            RoomSpec cap = heldCap.get(kind);
            if (cap == null) {
                return null;
            }
            int door = capDoorIndex(cap);
            DoorSpec spec = cap.doors().get(door);
            int turns = spec.facing().turnsTo(facing.opposite());
            Cell origin = at.step(facing).minus(spec.at().turned(turns));
            return cap.box().turned(turns).shifted(origin);
        }

        /** Why no try can succeed whatever the seed, or null when one might. */
        @Nullable
        String missing() {
            int rooms = 0;
            int most = 0;
            for (Beat beat : plan.path()) {
                rooms += beat.count().min();
                most += beat.count().max();
                if (beat.count().min() > 0 && withRole(beat.role()).isEmpty()) {
                    return noRoom(beat.role());
                }
            }
            if (most == 0) {
                return "the plan's path holds no room";
            }
            if (!plan.entryInBounds()) {
                return "the plan's Bounds.Entry " + plan.entryCell() + " lies outside its Bounds " + plan.bounds();
            }
            Links links = plan.links();
            if (rooms > 1 && links != null && links.count().min() > 0 && withRole(links.role()).isEmpty()) {
                return noRoom(links.role());
            }
            for (Branch branch : plan.branches()) {
                if (!branch.optional() && withRole(branch.role()).isEmpty()) {
                    return noRoom(branch.role());
                }
                if (!branch.optional() && branch.gate() != null && withRole(branch.gate()).isEmpty()) {
                    return noRoom(branch.gate());
                }
            }
            return null;
        }

        @Nonnull
        private String noRoom(@Nonnull String role) {
            return "no usable room carries role '" + role + "' and the plan's tags " + plan.roomTags();
        }
    }

    /** A cap's one usable door. */
    @Nonnull
    private static DoorSpec capDoor(@Nonnull RoomSpec cap) {
        return cap.doors().get(capDoorIndex(cap));
    }

    private static int capDoorIndex(@Nonnull RoomSpec cap) {
        for (int i = 0; i < cap.doors().size(); i++) {
            if (cap.doors().get(i).usable()) {
                return i;
            }
        }
        return 0;
    }

    // ==================== one try ====================

    /** A path slot: the role it fills, its part, beat and strata, and the stream its rooms draw from. */
    private record Slot(@Nonnull String role, @Nonnull Part part, int beat, int stratum, int altStratum,
                        @Nonnull SplitMix64 rng) {
    }

    /** What a room placed for a slot, a branch or a need is to the layout. */
    private record Fill(@Nonnull String role, @Nonnull Part part, int beat, int stratum, int pathIndex, double depth,
                        int branch, int step, int answers) {
    }

    /** A door left open, and the box held in front of it for its cap. */
    private record Open(int room, int door, @Nonnull Cell at, @Nonnull Facing facing, @Nonnull String kind,
                        @Nonnull Box held) {
    }

    /** A fit found for a room: where it stands and the doors it leaves open. */
    private record Placement(@Nonnull RoomSpec spec, int rotation, @Nonnull Cell origin, @Nonnull Box box,
                             int joinDoor, @Nullable Open join, @Nonnull List<Open> fresh) {
    }

    /** Where a try stood, to go back to when a branch or a need cannot be finished. */
    private record Mark(int rooms, @Nonnull List<Open> open, int joins, int branches) {
    }

    /** A room placed in this try, mutable until the try is built. */
    private static final class Node {

        final RoomSpec spec;
        final int index;
        final int[] linkRoom;
        final int[] linkDoor;
        final boolean[] capped;
        int variant;
        Cell origin = Cell.ZERO;
        int rotation;
        Box box;
        Fill fill;
        int parent = -1;

        Node(@Nonnull RoomSpec spec, int index) {
            this.spec = spec;
            this.index = index;
            int doors = spec.doors().size();
            this.linkRoom = new int[doors];
            this.linkDoor = new int[doors];
            this.capped = new boolean[doors];
            Arrays.fill(linkRoom, -1);
            Arrays.fill(linkDoor, -1);
        }
    }

    private static final class Attempt {

        final View view;
        final LayoutPlan plan;
        final long seed;
        final long trySeed;
        final Relax relax;
        final List<Node> rooms = new ArrayList<>();
        final List<int[]> joins = new ArrayList<>();
        List<Open> open = new ArrayList<>();
        String[] strataTags = new String[0];
        int pathLength;
        int branches;
        int checks;
        boolean exhausted;
        String failure;

        Attempt(@Nonnull View view, long seed, long trySeed, @Nonnull Relax relax) {
            this.view = view;
            this.plan = view.plan;
            this.seed = seed;
            this.trySeed = trySeed;
            this.relax = relax;
        }

        @Nullable
        Layout run() {
            pickStrata();
            List<Slot> slots = pathSlots();
            if (slots.isEmpty()) {
                return fail("the plan's path holds no room");
            }
            pathLength = slots.size();
            List<List<RoomSpec>> slotCandidates = new ArrayList<>(pathLength);
            for (int i = 0; i < pathLength; i++) {
                Slot slot = slots.get(i);
                slotCandidates.add(candidates(slot.role(), slot.stratum(), slot.altStratum(), depthAt(i)));
            }
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                double depth = depthAt(i);
                Fill fill = new Fill(slot.role(), slot.part(), slot.beat(), slot.stratum(), i, depth, -1, 0, -1);
                List<RoomSpec> candidates = slotCandidates.get(i);
                List<String> onward = i + 1 < pathLength ? kindsOf(slotCandidates.get(i + 1)) : null;
                Node placed = i == 0 ? placeEntry(candidates, slot.rng(), fill, onward)
                        : attach(candidates, rooms.get(i - 1), slot.rng(), fill, onward);
                if (placed == null) {
                    return fail(exhausted ? budgetSpent()
                            : "no room with role '" + slot.role() + "' fits path slot " + i + " (beat " + slot.beat()
                                    + ", " + candidates.size() + " candidates)");
                }
            }
            SplitMix64 needs = SplitMix64.derive(trySeed, STREAM_NEEDS);
            for (int i = 0; i < pathLength; i++) {
                if (!answerNeeds(rooms.get(i), needs)) {
                    return fail(exhausted ? budgetSpent() : failure);
                }
            }
            if (!placeBranches()) {
                return fail(exhausted ? budgetSpent() : failure);
            }
            if (!capAll()) {
                return fail(failure);
            }
            return build();
        }

        @Nullable
        private Layout fail(@Nullable String why) {
            failure = why;
            return null;
        }

        @Nonnull
        private String budgetSpent() {
            return "the try spent its " + CHECK_BUDGET + " placement checks";
        }

        // ---------- the plan's beats ----------

        private void pickStrata() {
            SplitMix64 rng = SplitMix64.derive(trySeed, STREAM_STRATA);
            List<Stratum> strata = plan.strata();
            strataTags = new String[strata.size()];
            for (int s = 0; s < strata.size(); s++) {
                List<String> anyOf = strata.get(s).anyOf();
                strataTags[s] = anyOf.isEmpty() ? "" : anyOf.get(rng.nextInt(anyOf.size()));
            }
        }

        @Nonnull
        private List<Slot> pathSlots() {
            boolean least = relax == Relax.COUNTS_AT_MINIMUM;
            List<Slot> slots = new ArrayList<>();
            Links links = plan.links();
            int previous = 0;
            for (int b = 0; b < plan.path().size(); b++) {
                Beat beat = plan.path().get(b);
                SplitMix64 rng = SplitMix64.derive(trySeed, STREAM_BEAT + b);
                int count = least ? beat.count().min() : beat.count().roll(rng);
                int stratum = clampStratum(beat.stratum() >= 0 ? beat.stratum() : previous);
                previous = Math.max(0, stratum);
                if (count == 0) {
                    continue;
                }
                if (!slots.isEmpty() && links != null) {
                    SplitMix64 linkRng = SplitMix64.derive(trySeed, STREAM_LINK + b);
                    int linkCount = least ? links.count().min() : links.count().roll(linkRng);
                    int from = slots.get(slots.size() - 1).stratum();
                    for (int j = 0; j < linkCount; j++) {
                        slots.add(new Slot(links.role(), Part.LINK, b, from, stratum, linkRng));
                    }
                }
                for (int j = 0; j < count; j++) {
                    slots.add(new Slot(beat.role(), Part.PATH, b, stratum, stratum, rng));
                }
            }
            return slots;
        }

        private int clampStratum(int stratum) {
            if (strataTags.length == 0) {
                return -1;
            }
            return Math.max(0, Math.min(strataTags.length - 1, stratum));
        }

        @Nullable
        private String stratumTag(int stratum) {
            if (stratum < 0 || stratum >= strataTags.length || strataTags[stratum].isEmpty()) {
                return null;
            }
            return strataTags[stratum];
        }

        /** Stratum {@code stratum}'s fill block, or null for none or no stratum. */
        @Nullable
        private String stratumFill(int stratum) {
            return stratum < 0 || stratum >= plan.strata().size() ? null : plan.strata().get(stratum).fill();
        }

        private double depthAt(int pathIndex) {
            return pathLength <= 1 ? 0 : (double) pathIndex / (pathLength - 1);
        }

        /**
         * The rooms of {@code role} that may stand at {@code depth} in stratum {@code stratum} (or, for a connector
         * between two strata, {@code altStratum}): a room carrying either stratum's tag fits, and a stratum that
         * lists no tag lets any room in.
         */
        @Nonnull
        private List<RoomSpec> candidates(@Nonnull String role, int stratum, int altStratum, double depth) {
            String tag = stratumTag(stratum);
            String alt = stratumTag(altStratum);
            boolean filtered = tag != null && alt != null;
            List<RoomSpec> out = new ArrayList<>();
            for (RoomSpec room : view.withRole(role)) {
                if (!room.fitsDepth(depth)) {
                    continue;
                }
                if (filtered && !room.tags().contains(tag) && !room.tags().contains(alt)) {
                    continue;
                }
                out.add(room);
            }
            return out;
        }

        // ---------- placing one room ----------

        @Nullable
        private Node placeEntry(@Nonnull List<RoomSpec> candidates, @Nonnull SplitMix64 rng, @Nonnull Fill fill,
                @Nullable List<String> onward) {
            Cell entry = plan.entryCell();
            int firstTurn = rng.nextInt(4);
            for (RoomSpec spec : weightedOrder(candidates, rng)) {
                for (int k = 0; k < 4; k++) {
                    if (++checks > CHECK_BUDGET) {
                        exhausted = true;
                        return null;
                    }
                    Placement fit = fit(spec, QuarterTurns.normalize(firstTurn + k), entry, -1, null, onward);
                    if (fit != null) {
                        return place(fit, fill, -1, rng);
                    }
                }
            }
            return null;
        }

        /**
         * A room of {@code candidates} joined onto an open door of {@code target}, or null when none fits. With
         * {@code onward}, the room must also leave a door open of one of those kinds, so the next room has a door to
         * meet.
         */
        @Nullable
        private Node attach(@Nonnull List<RoomSpec> candidates, @Nonnull Node target, @Nonnull SplitMix64 rng,
                @Nonnull Fill fill, @Nullable List<String> onward) {
            List<Open> doors = openOf(target.index);
            if (doors.isEmpty() || candidates.isEmpty()) {
                return null;
            }
            int[] doorOrder = indices(doors.size());
            shuffle(doorOrder, rng);
            for (RoomSpec spec : weightedOrder(candidates, rng)) {
                int[] own = usableDoors(spec);
                shuffle(own, rng);
                for (int o : doorOrder) {
                    Open join = doors.get(o);
                    for (int d : own) {
                        DoorSpec door = spec.doors().get(d);
                        if (!door.kind().equals(join.kind())) {
                            continue;
                        }
                        if (++checks > CHECK_BUDGET) {
                            exhausted = true;
                            return null;
                        }
                        int turns = door.facing().turnsTo(join.facing().opposite());
                        Cell origin = join.at().step(join.facing()).minus(door.at().turned(turns));
                        Placement fit = fit(spec, turns, origin, d, join, onward);
                        if (fit != null) {
                            return place(fit, fill, target.index, rng);
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Whether {@code spec}, turned {@code turns} with its anchor at {@code origin}, fits: inside the bounds,
         * clear of every room and every held cap box but the one of the door it joins, with room for a cap in
         * front of each door it leaves open, and (with {@code onward}) one of those doors of a kind listed there.
         */
        @Nullable
        private Placement fit(@Nonnull RoomSpec spec, int turns, @Nonnull Cell origin, int joinDoor,
                @Nullable Open join, @Nullable List<String> onward) {
            Box box = spec.box().turned(turns).shifted(origin);
            if (!box.within(view.bounds) || hitsRoom(box) || hitsHeld(box, join, List.of())) {
                return null;
            }
            List<Open> fresh = new ArrayList<>();
            for (int d = 0; d < spec.doors().size(); d++) {
                DoorSpec door = spec.doors().get(d);
                if (d == joinDoor || !door.usable()) {
                    continue;
                }
                Cell at = origin.plus(door.at().turned(turns));
                Facing facing = door.facing().turned(turns);
                Box held = view.held(at, facing, door.kind());
                if (held == null || !held.within(view.bounds) || held.overlaps(box) || hitsRoom(held)
                        || hitsHeld(held, join, fresh)) {
                    return null;
                }
                fresh.add(new Open(-1, d, at, facing, door.kind(), held));
            }
            if (onward != null && !leavesOnward(fresh, onward)) {
                return null;
            }
            return new Placement(spec, turns, origin, box, joinDoor, join, fresh);
        }

        private boolean leavesOnward(@Nonnull List<Open> fresh, @Nonnull List<String> onward) {
            for (Open o : fresh) {
                if (onward.contains(o.kind())) {
                    return true;
                }
            }
            return false;
        }

        private boolean hitsRoom(@Nonnull Box box) {
            for (Node node : rooms) {
                if (box.overlaps(node.box)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hitsHeld(@Nonnull Box box, @Nullable Open except, @Nonnull List<Open> more) {
            for (Open o : open) {
                if (o != except && box.overlaps(o.held())) {
                    return true;
                }
            }
            for (Open o : more) {
                if (box.overlaps(o.held())) {
                    return true;
                }
            }
            return false;
        }

        @Nonnull
        private Node place(@Nonnull Placement fit, @Nonnull Fill fill, int parent, @Nonnull SplitMix64 rng) {
            Node node = new Node(fit.spec(), rooms.size());
            node.variant = rng.nextInt(view.catalogue.variants(fit.spec().id()));
            node.origin = fit.origin();
            node.rotation = fit.rotation();
            node.box = fit.box();
            node.fill = fill;
            node.parent = parent;
            rooms.add(node);
            Open join = fit.join();
            if (join != null) {
                link(join.room(), join.door(), node.index, fit.joinDoor(), false);
                open.remove(join);
            }
            for (Open fresh : fit.fresh()) {
                open.add(new Open(node.index, fresh.door(), fresh.at(), fresh.facing(), fresh.kind(), fresh.held()));
            }
            return node;
        }

        private void link(int a, int aDoor, int b, int bDoor, boolean capped) {
            Node first = rooms.get(a);
            Node second = rooms.get(b);
            first.linkRoom[aDoor] = b;
            first.linkDoor[aDoor] = bDoor;
            first.capped[aDoor] = capped;
            second.linkRoom[bDoor] = a;
            second.linkDoor[bDoor] = aDoor;
            second.capped[bDoor] = capped;
            joins.add(new int[] {a, aDoor});
        }

        @Nonnull
        private List<Open> openOf(int room) {
            List<Open> out = new ArrayList<>();
            for (Open o : open) {
                if (o.room() == room) {
                    out.add(o);
                }
            }
            return out;
        }

        private boolean hasOpenDoor(int room) {
            for (Open o : open) {
                if (o.room() == room) {
                    return true;
                }
            }
            return false;
        }

        @Nonnull
        private Mark mark() {
            return new Mark(rooms.size(), new ArrayList<>(open), joins.size(), branches);
        }

        private void rollback(@Nonnull Mark mark) {
            for (int i = joins.size() - 1; i >= mark.joins(); i--) {
                int[] join = joins.remove(i);
                if (join[0] < mark.rooms()) {
                    Node node = rooms.get(join[0]);
                    node.linkRoom[join[1]] = -1;
                    node.linkDoor[join[1]] = -1;
                    node.capped[join[1]] = false;
                }
            }
            while (rooms.size() > mark.rooms()) {
                rooms.remove(rooms.size() - 1);
            }
            open = new ArrayList<>(mark.open());
            branches = mark.branches();
        }

        // ---------- needs and branches ----------

        /** Places, for each role {@code needer} needs, a room of that role on a branch off a path room before it. */
        private boolean answerNeeds(@Nonnull Node needer, @Nonnull SplitMix64 rng) {
            for (String need : needer.spec.needs()) {
                if (!answerNeed(needer, need, rng)) {
                    failure = "no room with role '" + need + "' fits on a branch before room '"
                            + needer.spec.id() + "' (path slot " + needer.fill.pathIndex() + ")";
                    return false;
                }
            }
            return true;
        }

        private boolean answerNeed(@Nonnull Node needer, @Nonnull String need, @Nonnull SplitMix64 rng) {
            int[] hosts = indices(needer.fill.pathIndex());
            shuffle(hosts, rng);
            for (int h : hosts) {
                if (!hasOpenDoor(h)) {
                    continue;
                }
                Node host = rooms.get(h);
                Fill hostFill = host.fill;
                Mark mark = mark();
                int branch = branches++;
                Fill fill = new Fill(need, Part.NEED, -1, hostFill.stratum(), hostFill.pathIndex(), hostFill.depth(),
                        branch, 1, needer.index);
                Node room = attach(candidates(need, hostFill.stratum(), hostFill.stratum(), hostFill.depth()), host,
                        rng, fill, null);
                if (room != null && answerNeeds(room, rng)) {
                    return true;
                }
                rollback(mark);
                if (exhausted) {
                    return false;
                }
            }
            return false;
        }

        private boolean placeBranches() {
            List<Branch> list = plan.branches();
            for (int b = 0; b < list.size(); b++) {
                Branch branch = list.get(b);
                if (relax != Relax.NONE && branch.optional()) {
                    continue;
                }
                SplitMix64 rng = SplitMix64.derive(trySeed, STREAM_BRANCH + b);
                int count = relax == Relax.COUNTS_AT_MINIMUM ? branch.count().min() : branch.count().roll(rng);
                for (int i = 0; i < count; i++) {
                    if (branch.chance() < 1 && rng.nextDouble() >= branch.chance()) {
                        continue;
                    }
                    boolean required = i < branch.count().min() && branch.chance() >= 1;
                    boolean placed = placeBranch(branch, rng);
                    if (exhausted) {
                        return false;
                    }
                    if (!placed && required) {
                        failure = "no branch of role '" + branch.role() + "'"
                                + (branch.gate() == null ? "" : " behind a '" + branch.gate() + "'")
                                + " fits off the path";
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean placeBranch(@Nonnull Branch branch, @Nonnull SplitMix64 rng) {
            int[] hosts = indices(pathLength);
            shuffle(hosts, rng);
            for (int h : hosts) {
                if (!hasOpenDoor(h)) {
                    continue;
                }
                Node host = rooms.get(h);
                Fill at = host.fill;
                Mark mark = mark();
                int id = branches++;
                List<RoomSpec> ends = candidates(branch.role(), at.stratum(), at.stratum(), at.depth());
                Node entrance = host;
                int step = 1;
                if (branch.gate() != null) {
                    entrance = attach(candidates(branch.gate(), at.stratum(), at.stratum(), at.depth()), host, rng,
                            new Fill(branch.gate(), Part.GATE, -1, at.stratum(), at.pathIndex(), at.depth(), id, step,
                                    -1), kindsOf(ends));
                    step++;
                }
                Node room = entrance == null ? null
                        : attach(ends, entrance, rng, new Fill(branch.role(), Part.BRANCH, -1, at.stratum(),
                                at.pathIndex(), at.depth(), id, step, -1), null);
                boolean done = room != null && (entrance == host || answerNeeds(entrance, rng))
                        && answerNeeds(room, rng);
                if (done) {
                    return true;
                }
                rollback(mark);
                if (exhausted) {
                    return false;
                }
            }
            return false;
        }

        // ---------- caps ----------

        /** Closes every door still open with a cap of its kind, one carrying the room's stratum tag first. */
        private boolean capAll() {
            SplitMix64 rng = SplitMix64.derive(trySeed, STREAM_CAPS);
            while (!open.isEmpty()) {
                Open door = open.get(0);
                Node owner = rooms.get(door.room());
                if (!cap(door, owner, capOrder(door.kind(), stratumTag(owner.fill.stratum()), rng), rng)) {
                    failure = "no cap fits the open '" + door.kind() + "' door " + door.door() + " of room '"
                            + owner.spec.id() + "'";
                    return false;
                }
            }
            return true;
        }

        @Nonnull
        private List<RoomSpec> capOrder(@Nonnull String kind, @Nullable String stratumTag, @Nonnull SplitMix64 rng) {
            List<RoomSpec> preferred = new ArrayList<>();
            List<RoomSpec> rest = new ArrayList<>();
            for (RoomSpec cap : view.capsOf(kind)) {
                (stratumTag != null && cap.tags().contains(stratumTag) ? preferred : rest).add(cap);
            }
            List<RoomSpec> order = new ArrayList<>(weightedOrder(preferred, rng));
            order.addAll(weightedOrder(rest, rng));
            return order;
        }

        private boolean cap(@Nonnull Open door, @Nonnull Node owner, @Nonnull List<RoomSpec> caps,
                @Nonnull SplitMix64 rng) {
            for (RoomSpec cap : caps) {
                int d = capDoorIndex(cap);
                DoorSpec spec = cap.doors().get(d);
                int turns = spec.facing().turnsTo(door.facing().opposite());
                Cell origin = door.at().step(door.facing()).minus(spec.at().turned(turns));
                Box box = cap.box().turned(turns).shifted(origin);
                if (!box.within(view.bounds) || hitsRoom(box) || hitsHeld(box, door, List.of())) {
                    continue;
                }
                Fill at = owner.fill;
                Node node = new Node(cap, rooms.size());
                node.variant = rng.nextInt(view.catalogue.variants(cap.id()));
                node.origin = origin;
                node.rotation = turns;
                node.box = box;
                node.fill = new Fill(RoomSpec.CAP_ROLE, Part.CAP, -1, at.stratum(), at.pathIndex(), at.depth(),
                        at.branch(), at.step() + 1, -1);
                node.parent = owner.index;
                rooms.add(node);
                link(owner.index, door.door(), node.index, d, true);
                open.remove(door);
                return true;
            }
            return false;
        }

        // ---------- the result ----------

        @Nonnull
        private Layout build() {
            List<PlacedRoom> out = new ArrayList<>(rooms.size());
            for (Node node : rooms) {
                List<DoorLink> doors = new ArrayList<>(node.spec.doors().size());
                for (int d = 0; d < node.spec.doors().size(); d++) {
                    DoorSpec door = node.spec.doors().get(d);
                    Cell at = node.origin.plus(door.at().turned(node.rotation));
                    Facing facing = door.facing() == null ? null : door.facing().turned(node.rotation);
                    doors.add(new DoorLink(d, at, facing, door.kind(), node.linkRoom[d], node.linkDoor[d],
                            node.capped[d]));
                }
                Fill fill = node.fill;
                String block = node.spec.fill() != null ? node.spec.fill() : stratumFill(fill.stratum());
                out.add(new PlacedRoom(node.index, node.spec.id(), node.variant, node.origin, node.rotation, node.box,
                        block, fill.role(), fill.part(), fill.beat(), fill.stratum(), fill.pathIndex(), fill.depth(),
                        node.parent, fill.branch(), fill.step(), fill.answers(), doors));
            }
            List<String> strata = new ArrayList<>(strataTags.length);
            for (String tag : strataTags) {
                strata.add(tag);
            }
            return new Layout(plan.id(), seed, strata, pathLength, out);
        }
    }

    @Nonnull
    private static int[] indices(int n) {
        int[] out = new int[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) {
            out[i] = i;
        }
        return out;
    }

    /** The kinds of every usable door of {@code rooms}, each once, in the order met. */
    @Nonnull
    private static List<String> kindsOf(@Nonnull List<RoomSpec> rooms) {
        List<String> kinds = new ArrayList<>();
        for (RoomSpec room : rooms) {
            for (DoorSpec door : room.doors()) {
                if (door.usable() && !kinds.contains(door.kind())) {
                    kinds.add(door.kind());
                }
            }
        }
        return kinds;
    }

    @Nonnull
    private static int[] usableDoors(@Nonnull RoomSpec spec) {
        int[] out = new int[spec.usableDoorCount()];
        int n = 0;
        for (int i = 0; i < spec.doors().size(); i++) {
            if (spec.doors().get(i).usable()) {
                out[n++] = i;
            }
        }
        return out;
    }
}
