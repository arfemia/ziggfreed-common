package com.ziggfreed.common.instance.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.Facing;

/**
 * The hub's fixed lots: the one map every hub copy is laid out on, so a lot id means the same ground on every server
 * and in every release. Pure, no engine type.
 *
 * <p><b>The map.</b> A three by three grid of cells, {@link #PITCH} blocks apart, on the floor at {@link #FLOOR_Y}.
 * The middle cell is the {@link #CONCOURSE}, where players arrive. The four side cells ({@code North}, {@code East},
 * {@code South}, {@code West}) are one {@link Size#LARGE} lot each, its front facing the Concourse. Each corner cell
 * ({@code North_East} and so on) holds four {@link Size#SMALL} lots, {@code <Cell>_1} to {@code _4} for its north-west,
 * north-east, south-east and south-west quarters, the inner two facing the street toward the Concourse's row and the
 * outer two the hub's edge. Every lot's floor runs half a {@link #STREET} out from its edge, so the floors meet in
 * streets between the lots.
 *
 * <p><b>Growth.</b> A lot is never moved or renamed once shipped: a district claims a lot by id, and a new lot (an outer
 * ring) is added beside these, never in their place. A district's prefab is authored with its front facing North (-Z),
 * and its lot turns it to face the lot's street ({@link Lot#turns()}).
 */
public final class HubLots {

    /** The floor's own layer: a district's prefab anchor stands on it, and a player walks the layer above. */
    public static final int FLOOR_Y = 128;

    /** How far below the floor a district's prefab may reach (a cellar, a well). */
    public static final int DEPTH = 16;

    /** How far above the floor a district's prefab may reach. */
    public static final int HEIGHT = 80;

    /** The distance between two cells' centres. */
    public static final int PITCH = 80;

    /** The street between two lots; each lot's floor runs half of it out from its edge. */
    public static final int STREET = 16;

    /** The middle lot, where players arrive. */
    public static final String CONCOURSE = "Concourse";

    /** A lot's size: a closed vocabulary, authored as its word and read without regard to case. */
    public enum Size {
        SMALL("Small", 32),
        LARGE("Large", 64);

        private final String word;
        private final int span;

        Size(@Nonnull String word, int span) {
            this.word = word;
            this.span = span;
        }

        /** The word a file writes, such as {@code Large}. */
        @Nonnull
        public String word() {
            return word;
        }

        /** Its width and depth, in blocks. */
        public int span() {
            return span;
        }

        /** Does a district of this size fit a lot of {@code lot}'s? */
        public boolean fits(@Nonnull Size lot) {
            return span <= lot.span;
        }

        /** The size a file names, matched without regard to case; null for a blank or unknown word. */
        @Nullable
        public static Size parse(@Nullable String word) {
            if (word == null || word.isBlank()) {
                return null;
            }
            String trimmed = word.trim();
            for (Size size : values()) {
                if (size.word.equalsIgnoreCase(trimmed)) {
                    return size;
                }
            }
            return null;
        }

        /** Every word, smallest first, for the Asset Editor's dropdown. */
        @Nonnull
        public static String[] words() {
            Size[] all = values();
            String[] out = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = all[i].word;
            }
            return out;
        }
    }

    /**
     * One lot.
     *
     * @param id      its fixed id, as a district file names it
     * @param size    its size
     * @param centreX the X of the block at its centre (the one east of the middle line for an even span)
     * @param centreZ the Z of the block at its centre
     * @param facing  the way its front faces, toward its street
     * @param floor   the ground its district's floor covers, on the floor's layer: the lot and its share of the
     *                streets around it, so the floors of neighbouring lots meet and never overlap
     */
    public record Lot(@Nonnull String id, @Nonnull Size size, int centreX, int centreZ, @Nonnull Facing facing,
                      @Nonnull Box floor) {

        /** The quarter turns a North-facing prefab takes to face this lot's street. */
        public int turns() {
            return Facing.NORTH.turnsTo(facing);
        }

        /** The block a district's prefab anchor stands on: the lot's centre, on the floor's layer. */
        @Nonnull
        public Cell anchor() {
            return new Cell(centreX, FLOOR_Y, centreZ);
        }

        /** The blocks a district on this lot may write. */
        @Nonnull
        public Box box() {
            int half = size.span / 2;
            return new Box(new Cell(centreX - half, FLOOR_Y - DEPTH, centreZ - half),
                    new Cell(centreX - half + size.span - 1, FLOOR_Y + HEIGHT - 1, centreZ - half + size.span - 1));
        }
    }

    private static final List<Lot> LOTS = build();

    private HubLots() {
    }

    /** Every lot, the Concourse first, then the sides, then the corners, in compass order. */
    @Nonnull
    public static List<Lot> all() {
        return LOTS;
    }

    /** The lot {@code id} names, without regard to case; null for none. */
    @Nullable
    public static Lot find(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Lot lot : LOTS) {
            if (lot.id().toLowerCase(Locale.ROOT).equals(wanted)) {
                return lot;
            }
        }
        return null;
    }

    /** Every lot id, in {@link #all()}'s order, for the Asset Editor. */
    @Nonnull
    public static String[] ids() {
        String[] out = new String[LOTS.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = LOTS.get(i).id();
        }
        return out;
    }

    /** The whole hub's ground, every lot's floor together: what a chunk must reach to hold any of it. */
    @Nonnull
    public static Box reach() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Lot lot : LOTS) {
            Box floor = lot.floor();
            minX = Math.min(minX, floor.min().x());
            minZ = Math.min(minZ, floor.min().z());
            maxX = Math.max(maxX, floor.max().x());
            maxZ = Math.max(maxZ, floor.max().z());
        }
        return new Box(new Cell(minX, FLOOR_Y - DEPTH, minZ), new Cell(maxX, FLOOR_Y + HEIGHT - 1, maxZ));
    }

    @Nonnull
    private static List<Lot> build() {
        List<Lot> lots = new ArrayList<>();
        lots.add(whole(CONCOURSE, 0, 0, Facing.NORTH));
        lots.add(whole("North", 0, -PITCH, Facing.SOUTH));
        lots.add(whole("East", PITCH, 0, Facing.WEST));
        lots.add(whole("South", 0, PITCH, Facing.NORTH));
        lots.add(whole("West", -PITCH, 0, Facing.EAST));
        corner(lots, "North_East", PITCH, -PITCH);
        corner(lots, "South_East", PITCH, PITCH);
        corner(lots, "South_West", -PITCH, PITCH);
        corner(lots, "North_West", -PITCH, -PITCH);
        return List.copyOf(lots);
    }

    /** A cell holding one large lot, whose floor is the whole cell. */
    @Nonnull
    private static Lot whole(@Nonnull String id, int cx, int cz, @Nonnull Facing facing) {
        int half = PITCH / 2;
        return new Lot(id, Size.LARGE, cx, cz, facing, new Box(new Cell(cx - half, FLOOR_Y, cz - half),
                new Cell(cx + half - 1, FLOOR_Y, cz + half - 1)));
    }

    /** A corner cell's four small lots, north-west, north-east, south-east, south-west; each floors its quarter. */
    private static void corner(@Nonnull List<Lot> lots, @Nonnull String cell, int cx, int cz) {
        int quarter = Size.SMALL.span / 2;
        int half = PITCH / 2;
        int[][] quarters = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < quarters.length; i++) {
            int sx = quarters[i][0];
            int sz = quarters[i][1];
            // The quarter nearer the hub's middle row faces in, toward the street past the Concourse's row.
            boolean inner = Math.abs(cz + sz * quarter) < Math.abs(cz);
            Facing in = cz < 0 ? Facing.SOUTH : Facing.NORTH;
            int minX = sx < 0 ? cx - half : cx;
            int minZ = sz < 0 ? cz - half : cz;
            lots.add(new Lot(cell + "_" + (i + 1), Size.SMALL, cx + sx * quarter, cz + sz * quarter,
                    inner ? in : in.opposite(), new Box(new Cell(minX, FLOOR_Y, minZ),
                    new Cell(minX + half - 1, FLOOR_Y, minZ + half - 1))));
        }
    }
}
