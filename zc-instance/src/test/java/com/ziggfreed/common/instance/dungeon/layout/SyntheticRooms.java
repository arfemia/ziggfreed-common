package com.ziggfreed.common.instance.dungeon.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Branch;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Count;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Links;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Stratum;

/**
 * A synthetic room kit and plan for the generator's tests: two door kinds, three strata, stairs that change floor,
 * a puzzle that needs a clue, gated and chance branches, caps of each kind, and one empty pool that must never be
 * placed. Sizes stand in for vanilla's pieces (rooms 5 to 21 blocks across).
 */
final class SyntheticRooms {

    static final String TAG = "Synthetic";
    static final String GHOST_POOL = "Synthetic.Ghost.*";
    /** The first stratum's fill, and the arenas' own, which wins over it. */
    static final String STRATUM_FILL = "Rock_Stone";
    static final String ARENA_FILL = "Rock_Basalt";

    /** Every pool holds three variants but the ghost's, which holds none; a single prefab is one. */
    static final PoolIndex POOLS = prefab -> prefab.equals(GHOST_POOL) ? 0 : prefab.endsWith(".*") ? 3 : 1;

    private SyntheticRooms() {
    }

    @Nonnull
    static RoomCatalogue catalogue() {
        List<RoomSpec> rooms = new ArrayList<>();
        for (String stratum : new String[] {"Ruins", "Crypt", "Void"}) {
            String s = stratum.toLowerCase(Locale.ROOT);
            if (!stratum.equals("Void")) {
                rooms.add(room("entry_" + s, "Synthetic." + stratum + ".Entry", box(-4, 0, -4, 4, 5, 4),
                        List.of("Entry"), stratum, 1, 0, 1, List.of(),
                        door(0, 0, -4, Facing.NORTH, "Small"), door(4, 0, 0, Facing.EAST, "Small"),
                        door(-4, 0, 0, Facing.WEST, "Large")));
            }
            rooms.add(room("corridor_" + s, "Synthetic." + stratum + ".Corridor.*", box(-2, 0, -6, 2, 4, 6),
                    List.of("Corridor"), stratum, 3, 0, 1, List.of(),
                    door(0, 0, -6, Facing.NORTH, "Small"), door(0, 0, 6, Facing.SOUTH, "Small")));
            rooms.add(room("turn_" + s, "Synthetic." + stratum + ".Turn", box(-2, 0, -2, 2, 4, 2),
                    List.of("Corridor"), stratum, 2, 0, 1, List.of(),
                    door(0, 0, -2, Facing.NORTH, "Small"), door(2, 0, 0, Facing.EAST, "Small")));
            rooms.add(room("adapter_" + s, "Synthetic." + stratum + ".Adapter", box(-3, 0, -6, 3, 5, 6),
                    List.of("Corridor"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, -6, Facing.NORTH, "Small"), door(0, 0, 6, Facing.SOUTH, "Large")));
            rooms.add(room("stair_" + s, "Synthetic." + stratum + ".Stair", box(-2, -5, -6, 2, 4, 6),
                    List.of("Corridor"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, -6, Facing.NORTH, "Small"), door(0, -5, 6, Facing.SOUTH, "Small")));
            rooms.add(room("hall_" + s, "Synthetic." + stratum + ".Hall.*", box(-7, 0, -7, 7, 7, 7),
                    List.of("Combat"), stratum, 2, 0, 1, List.of(),
                    door(0, 0, -7, Facing.NORTH, "Small"), door(7, 0, 0, Facing.EAST, "Large"),
                    door(0, 0, 7, Facing.SOUTH, "Small"), door(-7, 0, 0, Facing.WEST, "Small")));
            rooms.add(filled(room("arena_" + s, "Synthetic." + stratum + ".Arena", box(-10, 0, -10, 10, 10, 10),
                    List.of("Combat", "Boss"), stratum, 1, 0.5, 1, List.of(),
                    door(0, 0, -10, Facing.NORTH, "Large"), door(0, 0, 10, Facing.SOUTH, "Large")), ARENA_FILL));
            rooms.add(room("puzzle_" + s, "Synthetic." + stratum + ".Puzzle.*", box(-5, 0, -5, 5, 6, 5),
                    List.of("Puzzle"), stratum, 1, 0.2, 1, List.of("Clue"),
                    door(0, 0, -5, Facing.NORTH, "Small"), door(0, 0, 5, Facing.SOUTH, "Small"),
                    door(5, 0, 0, Facing.EAST, "Small")));
            rooms.add(room("clue_" + s, "Synthetic." + stratum + ".Clue", box(-3, 0, -3, 3, 4, 3),
                    List.of("Clue"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, 3, Facing.SOUTH, "Small")));
            rooms.add(room("checkpoint_" + s, "Synthetic." + stratum + ".Checkpoint", box(-4, 0, -4, 4, 5, 4),
                    List.of("Checkpoint"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, -4, Facing.NORTH, "Small"), door(0, 0, 4, Facing.SOUTH, "Small")));
            rooms.add(room("lock_" + s, "Synthetic." + stratum + ".Lock", box(-2, 0, -4, 2, 4, 4),
                    List.of("Lock"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, -4, Facing.NORTH, "Small"), door(0, 0, 4, Facing.SOUTH, "Small")));
            rooms.add(room("treasure_" + s, "Synthetic." + stratum + ".Treasure.*", box(-3, 0, -3, 3, 4, 3),
                    List.of("Treasure"), stratum, 1, 0, 1, List.of(),
                    door(-3, 0, 0, Facing.WEST, "Small")));
            rooms.add(room("vault_" + s, "Synthetic." + stratum + ".Vault", box(-6, 0, -6, 6, 6, 6),
                    List.of("Vault"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, 6, Facing.SOUTH, "Large")));
            rooms.add(room("cap_small_" + s, "Synthetic." + stratum + ".Cap_Small.*", box(-1, 0, 0, 1, 3, 0),
                    List.of("Cap"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, 0, Facing.NORTH, "Small")));
            rooms.add(room("cap_large_" + s, "Synthetic." + stratum + ".Cap_Large", box(-2, 0, 0, 2, 4, 1),
                    List.of("Cap"), stratum, 1, 0, 1, List.of(),
                    door(0, 0, 0, Facing.NORTH, "Large")));
        }
        rooms.add(room("ghost_hall", GHOST_POOL, box(-7, 0, -7, 7, 7, 7), List.of("Combat"), "Ruins", 50, 0, 1,
                List.of(), door(0, 0, -7, Facing.NORTH, "Small"), door(0, 0, 7, Facing.SOUTH, "Small")));
        return RoomCatalogue.of(rooms, POOLS);
    }

    /**
     * The sweep's plan: entry, two or three fights, a puzzle that needs a clue, a checkpoint, more fights, the boss
     * in the second stratum and a vault; corridors between beats; gated treasure branches (one required), a chance
     * one and a required clue. The
     * entry stands high in the box, off its centre, as a descending dungeon's would.
     */
    @Nonnull
    static LayoutPlan plan() {
        return new LayoutPlan("Synthetic_Run", List.of(TAG),
                List.of(new Stratum(List.of("Ruins", "Crypt"), STRATUM_FILL), new Stratum(List.of("Void"), null)),
                List.of(new Beat("Entry", Count.ONE, -1),
                        new Beat("Combat", new Count(2, 3), -1),
                        new Beat("Puzzle", Count.ONE, -1),
                        new Beat("Checkpoint", Count.ONE, -1),
                        new Beat("Combat", new Count(1, 2), -1),
                        new Beat("Boss", Count.ONE, 1),
                        new Beat("Vault", Count.ONE, -1)),
                new Links("Corridor", new Count(0, 2)),
                List.of(new Branch("Treasure", new Count(0, 2), 1, "Lock"),
                        new Branch("Treasure", Count.ONE, 0.5, null),
                        new Branch("Clue", Count.ONE, 1, null),
                        new Branch("Treasure", Count.ONE, 1, "Lock")),
                new Cell(384, 64, 384), new Cell(160, 48, 200), 64);
    }

    /** {@code room} with its own fill block. */
    @Nonnull
    static RoomSpec filled(@Nonnull RoomSpec room, @Nonnull String fill) {
        return new RoomSpec(room.id(), room.prefab(), room.box(), room.doors(), room.roles(), room.tags(),
                room.weight(), room.depthMin(), room.depthMax(), room.needs(), room.keepMarkers(),
                room.keepContainers(), fill);
    }

    @Nonnull
    static RoomSpec room(@Nonnull String id, @Nonnull String prefab, @Nonnull Box box, @Nonnull List<String> roles,
            @Nullable String stratum, double weight, double depthMin, double depthMax, @Nonnull List<String> needs,
            @Nonnull DoorSpec... doors) {
        List<String> tags = new ArrayList<>();
        tags.add(TAG);
        if (stratum != null) {
            tags.add(stratum);
        }
        return new RoomSpec(id, prefab, box, List.of(doors), roles, tags, weight, depthMin, depthMax, needs, false,
                false, null);
    }

    @Nonnull
    static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new Cell(x0, y0, z0), new Cell(x1, y1, z1));
    }

    @Nonnull
    static DoorSpec door(int x, int y, int z, @Nullable Facing facing, @Nonnull String kind) {
        return new DoorSpec(new Cell(x, y, z), facing, kind);
    }
}
