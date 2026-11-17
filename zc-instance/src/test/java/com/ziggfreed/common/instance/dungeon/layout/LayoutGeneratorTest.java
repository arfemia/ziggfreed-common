package com.ziggfreed.common.instance.dungeon.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Branch;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Count;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Links;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Stratum;
import com.ziggfreed.common.instance.dungeon.layout.LayoutResult.Relax;
import com.ziggfreed.common.instance.dungeon.layout.PlacedRoom.DoorLink;
import com.ziggfreed.common.instance.dungeon.layout.PlacedRoom.Part;

/**
 * The layout generator's structural gate (R23): one seed gives one layout; across 10,000 seeds over a synthetic kit
 * every layout is whole (every room reachable, every need placed before the room needing it, no two rooms sharing a
 * block, all inside the bounds, every door joined or capped face to face with its own kind, the entry on the plan's
 * entry cell, each room's fill its own else its stratum's, every gate before the end it guards, the rooms a tree,
 * every required branch there, no room entered by an exit-only door); a room with exit-only doors is entered only by
 * its other doors and left by them; rooms sharing a plan's Apart tag stand at least its distance apart, which they
 * would not without it; a room a MaxPerRun rule matches stands no more often than its Max, which it would without
 * it; a plan too big for its bounds relaxes, optional branches first and then counts; a
 * plan that cannot be met says why; and a door turned four quarter turns comes home, each turn the engine's own
 * prefab turn. Untagged: nothing here builds an engine item.
 */
public class LayoutGeneratorTest {

    private static final int SWEEP = 10_000;

    @Test
    void theSameSeedGivesTheSameLayout() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue();
        LayoutPlan plan = SyntheticRooms.plan();
        LayoutResult first = LayoutGenerator.generate(plan, catalogue, 12345L);
        LayoutResult again = LayoutGenerator.generate(plan, SyntheticRooms.catalogue(), 12345L);
        assertTrue(first.ok(), () -> "seed 12345 made no layout: " + first.failure());
        assertEquals(first, again, "one seed, one layout, whatever catalogue instance it reads");
        LayoutResult other = LayoutGenerator.generate(plan, catalogue, 54321L);
        assertTrue(other.ok(), () -> "seed 54321 made no layout: " + other.failure());
        assertNotEquals(first.layout(), other.layout(), "another seed makes another layout");
    }

    @Test
    void tenThousandSeedsEachGiveAWholeLayout() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue();
        LayoutPlan plan = SyntheticRooms.plan();
        Map<Relax, Integer> relaxed = new EnumMap<>(Relax.class);
        List<String> problems = new ArrayList<>();
        int failures = 0;
        long tries = 0;
        int mostTries = 0;
        long rooms = 0;
        long slowest = 0;
        long started = System.nanoTime();
        for (int i = 0; i < SWEEP; i++) {
            long seed = SplitMix64.mix(i + 1L);
            long t0 = System.nanoTime();
            LayoutResult result = LayoutGenerator.generate(plan, catalogue, seed);
            if (i >= 100) {
                slowest = Math.max(slowest, System.nanoTime() - t0);
            }
            tries += result.tries();
            mostTries = Math.max(mostTries, result.tries());
            if (!result.ok()) {
                failures++;
                if (problems.size() < 10) {
                    problems.add("seed " + seed + ": no layout: " + result.failure());
                }
                continue;
            }
            relaxed.merge(result.relax(), 1, Integer::sum);
            rooms += result.layout().rooms().size();
            List<String> found = problemsOf(result.layout(), plan, catalogue);
            if (!found.isEmpty() && problems.size() < 10) {
                problems.add("seed " + seed + ": " + found);
            }
        }
        double msEach = (System.nanoTime() - started) / 1e6 / SWEEP;
        System.out.println(String.format(Locale.ROOT, "[dungeon-sweep] seeds %d, failures %d, relax %s, tries %.2f "
                        + "per layout (most %d), rooms %.1f per layout, %.3f ms per layout, slowest after warm-up "
                        + "%.3f ms", SWEEP, failures, relaxed, (double) tries / SWEEP, mostTries,
                (double) rooms / Math.max(1, SWEEP - failures), msEach, slowest / 1e6));
        assertEquals(0, failures, () -> "every seed makes a layout: " + problems);
        assertTrue(problems.isEmpty(), () -> "every layout is whole: " + problems);
    }

    @Test
    void aRoomIsNeverEnteredThroughAnExitOnlyDoor() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue(true);
        LayoutPlan plan = SyntheticRooms.plan();
        List<String> problems = new ArrayList<>();
        int puzzles = 0;
        int leftByAnExit = 0;
        for (int i = 0; i < 2_000; i++) {
            long seed = SplitMix64.mix(i + 101L);
            LayoutResult result = LayoutGenerator.generate(plan, catalogue, seed);
            if (!result.ok()) {
                problems.add("seed " + seed + ": no layout: " + result.failure());
                continue;
            }
            for (String problem : problemsOf(result.layout(), plan, catalogue)) {
                problems.add("seed " + seed + ": " + problem);
            }
            for (PlacedRoom room : result.layout().rooms()) {
                if (!room.roomId().startsWith("puzzle_")) {
                    continue;
                }
                puzzles++;
                for (DoorLink door : room.doors()) {
                    RoomSpec spec = catalogue.room(room.roomId());
                    if (door.room() == room.parent() && spec.doors().get(door.door()).exitOnly()) {
                        problems.add("seed " + seed + ": puzzle " + room.index() + " entered by exit-only door "
                                + door.door());
                    }
                    if (spec.doors().get(door.door()).exitOnly() && door.room() > room.index()
                            && result.layout().rooms().get(door.room()).part() != Part.CAP) {
                        leftByAnExit++;
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " problems: "
                + problems.subList(0, Math.min(10, problems.size())));
        int placed = puzzles;
        int left = leftByAnExit;
        assertTrue(placed > 0 && left > 0, () -> "the one-way puzzles are placed (" + placed
                + ") and left by an exit-only door (" + left + ")");
    }

    @Test
    void roomsSharingAnApartTagStandAtLeastItsBlocksApart() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue();
        List<Stratum> strata = List.of(new Stratum(List.of("Ruins", "Crypt"), SyntheticRooms.STRATUM_FILL),
                new Stratum(List.of("Void"), null));
        List<Beat> path = List.of(new Beat("Entry", Count.ONE, -1), new Beat("Combat", new Count(3, 3), -1),
                new Beat("Boss", Count.ONE, -1), new Beat("Combat", new Count(2, 2), -1),
                new Beat("Boss", Count.ONE, 1), new Beat("Vault", Count.ONE, -1));
        int blocks = 20;
        LayoutPlan loose = new LayoutPlan("Arenas_Anywhere", List.of(SyntheticRooms.TAG), strata, path, null,
                List.of(), new Cell(384, 64, 384), new Cell(160, 48, 200), 64);
        LayoutPlan apart = new LayoutPlan("Arenas_Apart", List.of(SyntheticRooms.TAG), strata, path, null,
                List.of(), new Cell(384, 64, 384), new Cell(160, 48, 200), 64,
                List.of(new LayoutPlan.Apart(SyntheticRooms.ARENA_TAG, blocks)), List.of());
        List<String> problems = new ArrayList<>();
        int nearWithout = 0;
        for (int i = 0; i < 1_000; i++) {
            long seed = SplitMix64.mix(i + 303L);
            LayoutResult free = LayoutGenerator.generate(loose, catalogue, seed);
            if (free.ok() && nearestArenas(free.layout(), catalogue) < blocks) {
                nearWithout++;
            }
            LayoutResult kept = LayoutGenerator.generate(apart, catalogue, seed);
            if (!kept.ok()) {
                problems.add("seed " + seed + ": no layout: " + kept.failure());
                continue;
            }
            for (String problem : problemsOf(kept.layout(), apart, catalogue)) {
                problems.add("seed " + seed + ": " + problem);
            }
            double nearest = nearestArenas(kept.layout(), catalogue);
            if (nearest < blocks) {
                problems.add("seed " + seed + ": two arenas stand " + nearest + " blocks apart");
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " problems: "
                + problems.subList(0, Math.min(10, problems.size())));
        int near = nearWithout;
        assertTrue(near > 0, "without the rule some arenas stand nearer than " + blocks + " blocks, so the rule is "
                + "what keeps them apart");
    }

    @Test
    void aRoomAMaxPerRunRuleMatchesStandsNoMoreOftenThanItsMax() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue();
        List<Stratum> strata = List.of(new Stratum(List.of("Ruins", "Crypt"), SyntheticRooms.STRATUM_FILL));
        List<Beat> path = List.of(new Beat("Entry", Count.ONE, -1), new Beat("Combat", new Count(2, 2), -1),
                new Beat("Vault", Count.ONE, -1));
        LayoutPlan repeats = new LayoutPlan("Fights_Repeat", List.of(SyntheticRooms.TAG), strata, path, null,
                List.of(), new Cell(384, 64, 384), new Cell(160, 48, 200), 64);
        LayoutPlan once = new LayoutPlan("Fights_Once", List.of(SyntheticRooms.TAG), strata, path, null, List.of(),
                new Cell(384, 64, 384), new Cell(160, 48, 200), 64, List.of(),
                List.of(new LayoutPlan.MaxPerRun("Combat", null, 1)));
        List<String> problems = new ArrayList<>();
        int repeatedWithout = 0;
        for (int i = 0; i < 1_000; i++) {
            long seed = SplitMix64.mix(i + 505L);
            LayoutResult free = LayoutGenerator.generate(repeats, catalogue, seed);
            if (free.ok() && mostOfOneCombatRoom(free.layout(), catalogue) > 1) {
                repeatedWithout++;
            }
            LayoutResult kept = LayoutGenerator.generate(once, catalogue, seed);
            if (!kept.ok()) {
                problems.add("seed " + seed + ": no layout: " + kept.failure());
                continue;
            }
            for (String problem : problemsOf(kept.layout(), once, catalogue)) {
                problems.add("seed " + seed + ": " + problem);
            }
            if (mostOfOneCombatRoom(kept.layout(), catalogue) > 1) {
                problems.add("seed " + seed + ": a fight room stands twice");
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " problems: "
                + problems.subList(0, Math.min(10, problems.size())));
        int repeated = repeatedWithout;
        assertTrue(repeated > 0, "without the rule some run meets one fight room twice, so the rule is what stops it");
    }

    /** The most times one room filling the Combat role stands in {@code layout}. */
    private static int mostOfOneCombatRoom(@Nonnull Layout layout, @Nonnull RoomCatalogue catalogue) {
        Map<String, Integer> placed = new HashMap<>();
        for (PlacedRoom room : layout.rooms()) {
            if (catalogue.room(room.roomId()).hasRole("Combat")) {
                placed.merge(room.roomId(), 1, Integer::sum);
            }
        }
        int most = 0;
        for (int n : placed.values()) {
            most = Math.max(most, n);
        }
        return most;
    }

    /** The least distance between two rooms of {@code layout} carrying the arena tag; infinity for fewer than two. */
    private static double nearestArenas(@Nonnull Layout layout, @Nonnull RoomCatalogue catalogue) {
        List<PlacedRoom> arenas = new ArrayList<>();
        for (PlacedRoom room : layout.rooms()) {
            if (catalogue.room(room.roomId()).carries(SyntheticRooms.ARENA_TAG)) {
                arenas.add(room);
            }
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (int a = 0; a < arenas.size(); a++) {
            for (int b = a + 1; b < arenas.size(); b++) {
                nearest = Math.min(nearest, arenas.get(a).box().distanceTo(arenas.get(b).box()));
            }
        }
        return nearest;
    }

    @Test
    void aDoorTurnedFourQuarterTurnsComesHomeEachTurnTheEnginesOwn() {
        Cell[] cells = {new Cell(0, 0, 0), new Cell(3, 1, -7), new Cell(-5, -2, 4), new Cell(10, 0, 10)};
        for (Facing facing : Facing.values()) {
            for (Cell at : cells) {
                Cell turnedAt = at;
                Facing turnedFacing = facing;
                for (int turn = 1; turn <= 4; turn++) {
                    Cell next = turnedAt.turned(1);
                    PrefabRotation engine = PrefabRotation.VALUES[1];
                    assertEquals(engine.getX(turnedAt.x(), turnedAt.z()), next.x(), "a turn is the engine's (x)");
                    assertEquals(engine.getZ(turnedAt.x(), turnedAt.z()), next.z(), "a turn is the engine's (z)");
                    assertEquals(turnedAt.step(turnedFacing).turned(1), next.step(turnedFacing.turned(1)),
                            "the cell a door opens onto turns with the door");
                    assertEquals(at.turned(turn), next, "one turn " + turn + " times is " + turn + " turns");
                    assertEquals(facing.turned(turn), turnedFacing.turned(1));
                    turnedAt = next;
                    turnedFacing = turnedFacing.turned(1);
                }
                assertEquals(at, turnedAt, "four turns bring a door's cell home");
                assertEquals(facing, turnedFacing, "four turns bring a door's facing home");
                assertEquals(facing, facing.turned(facing.turnsTo(facing.opposite())).opposite(),
                        "turnsTo finds the turn that faces a door the other way");
            }
        }
    }

    @Test
    void aPlanTooBigForItsBoundsDropsItsOptionalBranchesFirstThenItsCounts() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue();
        LayoutPlan tight = new LayoutPlan("Tight", List.of(SyntheticRooms.TAG),
                List.of(new Stratum(List.of("Ruins"), null)),
                List.of(new Beat("Entry", Count.ONE, -1), new Beat("Combat", new Count(1, 8), -1),
                        new Beat("Vault", Count.ONE, -1)),
                new Links("Corridor", new Count(0, 3)),
                List.of(new Branch("Treasure", new Count(0, 6), 1, "Lock")), new Cell(48, 24, 48), null, 8);
        int max = tight.maxTries();
        int leastPath = 3;
        Map<Relax, Integer> relaxed = new EnumMap<>(Relax.class);
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            long seed = SplitMix64.mix(i + 7L);
            LayoutResult result = LayoutGenerator.generate(tight, catalogue, seed);
            assertTrue(result.ok(), () -> "seed " + seed + " found no layout even relaxed: " + result.failure());
            relaxed.merge(result.relax(), 1, Integer::sum);
            Layout layout = result.layout();
            boolean branched = false;
            for (PlacedRoom room : layout.rooms()) {
                branched |= room.part() == Part.GATE || room.part() == Part.BRANCH;
            }
            String at = "seed " + seed + " (" + result.relax() + ", " + result.tries() + " tries): ";
            switch (result.relax()) {
                case NONE -> {
                    if (result.tries() > max) {
                        problems.add(at + "the plan as written took more than its tries");
                    }
                }
                case OPTIONAL_BRANCHES_DROPPED -> {
                    if (result.tries() <= max || result.tries() > 2 * max) {
                        problems.add(at + "dropping branches came before the plan as written had its tries");
                    }
                    if (branched) {
                        problems.add(at + "an optional branch stayed");
                    }
                }
                case COUNTS_AT_MINIMUM -> {
                    if (result.tries() <= 2 * max) {
                        problems.add(at + "counts went to their least before branches were dropped and tried");
                    }
                    if (branched) {
                        problems.add(at + "an optional branch stayed");
                    }
                    if (layout.pathLength() != leastPath) {
                        problems.add(at + "the path holds " + layout.pathLength() + " rooms, not its least "
                                + leastPath);
                    }
                }
            }
            for (String problem : problemsOf(layout, tight, catalogue)) {
                problems.add(at + problem);
            }
        }
        System.out.println("[dungeon-relax] 400 seeds in 48 by 24 by 48: " + relaxed);
        assertTrue(relaxed.getOrDefault(Relax.OPTIONAL_BRANCHES_DROPPED, 0) > 0
                && relaxed.getOrDefault(Relax.COUNTS_AT_MINIMUM, 0) > 0,
                () -> "the plan is tight enough that both relax steps run: " + relaxed);
        assertTrue(problems.isEmpty(), () -> problems.size() + " problems: " + problems.subList(0,
                Math.min(10, problems.size())));
    }

    @Test
    void aPlanThatCannotBeMetMakesNoLayoutAndSaysWhy() {
        RoomCatalogue catalogue = SyntheticRooms.catalogue();
        LayoutPlan cramped = new LayoutPlan("Cramped", List.of(SyntheticRooms.TAG), List.of(),
                List.of(new Beat("Entry", Count.ONE, -1), new Beat("Vault", Count.ONE, -1)), null, List.of(),
                new Cell(6, 6, 6), null, 4);
        LayoutResult result = LayoutGenerator.generate(cramped, catalogue, 42L);
        assertFalse(result.ok(), "no entry room fits a box 6 blocks across");
        assertNull(result.layout());
        assertEquals(3 * cramped.maxTries(), result.tries(), "every relax step spends its tries first");
        assertEquals(Relax.COUNTS_AT_MINIMUM, result.relax(), "the result names the last step tried");
        assertTrue(result.failure() != null && result.failure().contains("'entry'")
                && result.failure().contains("path slot 0"), () -> "it says why: " + result.failure());

        LayoutPlan unknown = new LayoutPlan("Dragon_Run", List.of(SyntheticRooms.TAG), List.of(),
                List.of(new Beat("Entry", Count.ONE, -1), new Beat("Dragon", Count.ONE, -1)), null, List.of(),
                null, null, 0);
        LayoutResult none = LayoutGenerator.generate(unknown, catalogue, 42L);
        assertFalse(none.ok());
        assertEquals(0, none.tries(), "a role no room fills fails before any try");
        assertTrue(none.failure() != null && none.failure().contains("'dragon'"),
                () -> "it names the role: " + none.failure());
    }

    /**
     * Everything wrong with {@code layout}, checked from its own data. Public for the shipped kits' test
     * ({@code layout/asset/ShippedDungeonRoomsTest}), which holds a kit's layouts to the same rules.
     */
    @Nonnull
    public static List<String> problemsOf(@Nonnull Layout layout, @Nonnull LayoutPlan plan,
            @Nonnull RoomCatalogue catalogue) {
        List<String> out = new ArrayList<>();
        List<PlacedRoom> rooms = layout.rooms();
        Box bounds = Box.ofSize(plan.bounds());
        for (int i = 0; i < rooms.size(); i++) {
            PlacedRoom room = rooms.get(i);
            RoomSpec spec = catalogue.room(room.roomId());
            if (room.index() != i || spec == null) {
                out.add("room " + i + " is not indexed or unknown");
                continue;
            }
            if (!room.box().equals(spec.box().turned(room.rotation()).shifted(room.origin()))) {
                out.add("room " + i + " box is not its file's box turned and moved");
            }
            if (!room.box().within(bounds)) {
                out.add("room " + i + " leaves the bounds");
            }
            if (room.variant() < 0 || room.variant() >= catalogue.variants(spec.id())) {
                out.add("room " + i + " variant " + room.variant() + " is not in its pool");
            }
            if (i < layout.pathLength() != room.onPath()) {
                out.add("room " + i + " is out of path order");
            }
            if (i == 0 && !room.origin().equals(plan.entryCell())) {
                out.add("the entry stands at " + room.origin() + ", not the plan's " + plan.entryCell());
            }
            String stratumFill = room.stratum() < 0 ? null : plan.strata().get(room.stratum()).fill();
            if (!Objects.equals(room.fill(), spec.fill() != null ? spec.fill() : stratumFill)) {
                out.add("room " + i + " fill " + room.fill() + " is neither its own nor its stratum's");
            }
            for (int j = i + 1; j < rooms.size(); j++) {
                if (room.box().overlaps(rooms.get(j).box())) {
                    out.add("rooms " + i + " and " + j + " overlap");
                }
            }
            for (DoorLink door : room.doors()) {
                DoorSpec own = spec.doors().get(door.door());
                if (!door.at().equals(room.origin().plus(own.at().turned(room.rotation())))) {
                    out.add("room " + i + " door " + door.door() + " is not where its file puts it");
                }
                if (door.facing() == null) {
                    continue;
                }
                if (door.room() < 0) {
                    out.add("room " + i + " door " + door.door() + " is left open");
                    continue;
                }
                DoorLink other = rooms.get(door.room()).doors().get(door.roomDoor());
                if (other.room() != i || other.roomDoor() != door.door()) {
                    out.add("room " + i + " door " + door.door() + " is not joined both ways");
                }
                if (!other.at().equals(door.at().step(door.facing())) || other.facing() != door.facing().opposite()
                        || !other.kind().equals(door.kind())) {
                    out.add("room " + i + " door " + door.door() + " does not meet its partner face to face");
                }
                if (door.capped() != (room.part() == PlacedRoom.Part.CAP
                        || rooms.get(door.room()).part() == PlacedRoom.Part.CAP)) {
                    out.add("room " + i + " door " + door.door() + " is capped by a room that is no cap");
                }
                if (own.exitOnly() && door.room() == room.parent() && room.part() != PlacedRoom.Part.CAP) {
                    out.add("room " + i + " is entered from its parent through exit-only door " + door.door());
                }
            }
            for (String need : spec.needs()) {
                boolean answered = false;
                for (PlacedRoom other : rooms) {
                    RoomSpec otherSpec = catalogue.room(other.roomId());
                    if (other.answers() == i && otherSpec != null && otherSpec.hasRole(need)
                            && Layout.isBefore(other, room)) {
                        answered = true;
                    }
                }
                if (!answered) {
                    out.add("room " + i + " needs '" + need + "' and nothing before it fills it");
                }
            }
        }
        boolean[] reached = new boolean[rooms.size()];
        Deque<Integer> queue = new ArrayDeque<>();
        if (!rooms.isEmpty()) {
            reached[0] = true;
            queue.add(0);
        }
        while (!queue.isEmpty()) {
            for (DoorLink door : rooms.get(queue.poll()).doors()) {
                if (door.room() >= 0 && !reached[door.room()]) {
                    reached[door.room()] = true;
                    queue.add(door.room());
                }
            }
        }
        for (int i = 0; i < reached.length; i++) {
            if (!reached[i]) {
                out.add("room " + i + " cannot be reached from the entry");
            }
        }
        out.addAll(shapeProblemsOf(rooms, plan));
        return out;
    }

    /**
     * The card's headline rules that hold by construction, checked from the layout's own data: every gate leaves the
     * path and stands before the one branch end it guards, which hangs off it; the rooms (caps aside) join as a tree,
     * one join fewer than rooms, each room joined to its parent; and every branch the plan requires is there, behind
     * its gate when it names one.
     */
    @Nonnull
    private static List<String> shapeProblemsOf(@Nonnull List<PlacedRoom> rooms, @Nonnull LayoutPlan plan) {
        List<String> out = new ArrayList<>();
        int joins = 0;
        int nonCaps = 0;
        Map<String, Integer> ends = new HashMap<>();
        for (PlacedRoom room : rooms) {
            int i = room.index();
            if (room.part() != Part.CAP) {
                nonCaps++;
                for (DoorLink door : room.doors()) {
                    if (door.room() > i && rooms.get(door.room()).part() != Part.CAP) {
                        joins++;
                    }
                }
            }
            if (i > 0 && !joinedTo(room, room.parent())) {
                out.add("room " + i + " is not joined to its parent " + room.parent());
            }
            if (room.part() == Part.GATE) {
                if (room.parent() < 0 || !rooms.get(room.parent()).onPath()) {
                    out.add("gate " + i + " does not leave the path");
                }
                int guarded = 0;
                for (PlacedRoom other : rooms) {
                    if (other.parent() == i && other.part() == Part.BRANCH) {
                        guarded++;
                    }
                }
                if (guarded != 1) {
                    out.add("gate " + i + " guards " + guarded + " branch ends, not one");
                }
            }
            if (room.part() == Part.BRANCH) {
                PlacedRoom parent = rooms.get(room.parent());
                boolean gated = parent.part() == Part.GATE;
                if (gated && (parent.branch() != room.branch() || parent.index() >= i
                        || !Layout.isBefore(parent, room))) {
                    out.add("branch end " + i + " does not stand behind its gate " + parent.index());
                }
                if (!gated && !parent.onPath()) {
                    out.add("branch end " + i + " hangs off neither a gate nor the path");
                }
                ends.merge(room.role() + "|" + (gated ? parent.role() : ""), 1, Integer::sum);
            }
        }
        if (joins != nonCaps - 1) {
            out.add("the rooms do not join as a tree: " + joins + " joins between " + nonCaps + " rooms");
        }
        Map<String, Integer> required = new HashMap<>();
        for (Branch branch : plan.branches()) {
            if (!branch.optional()) {
                required.merge(branch.role() + "|" + (branch.gate() == null ? "" : branch.gate()),
                        branch.count().min(), Integer::sum);
            }
        }
        required.forEach((key, least) -> {
            int found = ends.getOrDefault(key, 0);
            if (found < least) {
                out.add("required branch " + key + " is there " + found + " times, not " + least);
            }
        });
        return out;
    }

    private static boolean joinedTo(@Nonnull PlacedRoom room, int other) {
        for (DoorLink door : room.doors()) {
            if (door.room() == other && other >= 0) {
                return true;
            }
        }
        return false;
    }
}
