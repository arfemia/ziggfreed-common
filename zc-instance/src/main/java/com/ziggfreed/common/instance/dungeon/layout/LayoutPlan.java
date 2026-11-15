package com.ziggfreed.common.instance.dungeon.layout;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A layout plan as its file reads once folded (the engine-free view of a {@code DungeonLayouts} file): which rooms
 * it draws on, the beats of its path, the connectors between them, its side branches and the box it must fit.
 *
 * @param id        the plan's id
 * @param roomTags  the tags every room it places must carry, folded
 * @param strata    the strata in order; empty for a plan without strata
 * @param path      the ordered beats, the first one the entry
 * @param links     the connectors put between two beats, or null for none
 * @param branches  the side branches
 * @param bounds    the size of the box every room must fit in, in blocks
 * @param entry     the cell the entry room's anchor stands on, in the layout's frame; null for the bounds' centre.
 *                  Kept as written, so {@link RoomCatalogueAudit} can report one outside the bounds.
 * @param maxTries  how many seeds it tries at each relax step before relaxing further
 */
public record LayoutPlan(@Nonnull String id, @Nonnull List<String> roomTags, @Nonnull List<Stratum> strata,
                         @Nonnull List<Beat> path, @Nullable Links links, @Nonnull List<Branch> branches,
                         @Nonnull Cell bounds, @Nullable Cell entry, int maxTries) {

    /** How many seeds a plan tries at each relax step when its file says nothing. */
    public static final int DEFAULT_MAX_TRIES = 64;

    /** The box a layout must fit when its file says nothing, in blocks (lead's call). */
    public static final Cell DEFAULT_BOUNDS = new Cell(512, 128, 512);

    public LayoutPlan {
        String folded = Words.fold(id);
        id = folded == null ? "" : folded;
        roomTags = Words.foldAll(roomTags);
        List<Stratum> kept = new ArrayList<>();
        if (strata != null) {
            for (Stratum stratum : strata) {
                kept.add(stratum == null ? new Stratum(List.of(), null) : stratum);
            }
        }
        strata = List.copyOf(kept);
        path = path == null ? List.of() : List.copyOf(path);
        branches = branches == null ? List.of() : List.copyOf(branches);
        bounds = bounds == null ? DEFAULT_BOUNDS
                : new Cell(Math.max(1, bounds.x()), Math.max(1, bounds.y()), Math.max(1, bounds.z()));
        maxTries = maxTries < 1 ? DEFAULT_MAX_TRIES : maxTries;
    }

    /** The cell the entry room's anchor stands on: {@code entry}, else the centre of the bounds. */
    @Nonnull
    public Cell entryCell() {
        return entry != null ? entry : new Cell(bounds.x() / 2, bounds.y() / 2, bounds.z() / 2);
    }

    /** Whether {@link #entryCell()} lies inside the bounds; a plan whose entry does not makes no layout. */
    public boolean entryInBounds() {
        return Box.ofSize(bounds).contains(entryCell());
    }

    /**
     * One stratum.
     *
     * @param anyOf the tags the seed picks one from, folded; empty lets any room in
     * @param fill  the block the painter fills a room's unlisted cells with in this stratum when the room names
     *              none ({@link RoomSpec#fill()}), as written; null for none
     */
    public record Stratum(@Nonnull List<String> anyOf, @Nullable String fill) {

        public Stratum {
            anyOf = Words.foldAll(anyOf);
            fill = Words.trimmed(fill);
        }
    }

    /** A count range; unauthored means exactly one. */
    public record Count(int min, int max) {

        public static final Count ONE = new Count(1, 1);

        public Count {
            min = Math.max(0, min);
            max = Math.max(min, max);
        }

        /** A count in range from {@code rng}. */
        public int roll(@Nonnull SplitMix64 rng) {
            return min == max ? min : min + rng.nextInt(max - min + 1);
        }
    }

    /**
     * One beat of the path.
     *
     * @param role    the role its rooms fill, folded
     * @param count   how many rooms in a row fill it
     * @param stratum the index of the stratum its rooms come from, or -1 for the previous beat's (the first beat's
     *                -1 is stratum 0)
     */
    public record Beat(@Nonnull String role, @Nonnull Count count, int stratum) {

        public Beat {
            String folded = Words.fold(role);
            role = folded == null ? "" : folded;
            count = count == null ? Count.ONE : count;
            stratum = Math.max(-1, stratum);
        }
    }

    /** The connectors between two beats: {@code count} rooms of {@code role}. */
    public record Links(@Nonnull String role, @Nonnull Count count) {

        public Links {
            String folded = Words.fold(role);
            role = folded == null ? "" : folded;
            count = count == null ? Count.ONE : count;
        }
    }

    /**
     * A side branch off the path: {@code count} of them, each kept with {@code chance}, each one room of
     * {@code role} behind a room of {@code gate} where the branch leaves the path.
     *
     * @param gate the role of the room the branch starts with, or null for none
     */
    public record Branch(@Nonnull String role, @Nonnull Count count, double chance, @Nullable String gate) {

        public Branch {
            String folded = Words.fold(role);
            role = folded == null ? "" : folded;
            count = count == null ? Count.ONE : count;
            chance = Double.isNaN(chance) ? 1 : Math.max(0, Math.min(1, chance));
            gate = Words.fold(gate);
        }

        /** Whether the plan holds without it: none are required, or each is left to chance. */
        public boolean optional() {
            return count.min() == 0 || chance < 1;
        }
    }
}
