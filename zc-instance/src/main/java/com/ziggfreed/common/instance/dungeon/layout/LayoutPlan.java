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
 * @param apart     the distances rooms sharing a tag keep between them; no relax step loosens one
 * @param maxPerRun how many times one room may stand in a run, for the rooms a rule matches; no relax step loosens one
 */
public record LayoutPlan(@Nonnull String id, @Nonnull List<String> roomTags, @Nonnull List<Stratum> strata,
                         @Nonnull List<Beat> path, @Nullable Links links, @Nonnull List<Branch> branches,
                         @Nonnull Cell bounds, @Nullable Cell entry, int maxTries, @Nonnull List<Apart> apart,
                         @Nonnull List<MaxPerRun> maxPerRun) {

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
        List<Apart> rules = new ArrayList<>();
        if (apart != null) {
            for (Apart rule : apart) {
                if (rule != null && !rule.tag().isEmpty() && rule.blocks() > 0) {
                    rules.add(rule);
                }
            }
        }
        apart = List.copyOf(rules);
        List<MaxPerRun> caps = new ArrayList<>();
        if (maxPerRun != null) {
            for (MaxPerRun rule : maxPerRun) {
                if (rule != null && rule.binds()) {
                    caps.add(rule);
                }
            }
        }
        maxPerRun = List.copyOf(caps);
    }

    /** A plan whose rooms keep no distance from each other beyond not sharing a block, and may repeat. */
    public LayoutPlan(@Nonnull String id, @Nonnull List<String> roomTags, @Nonnull List<Stratum> strata,
            @Nonnull List<Beat> path, @Nullable Links links, @Nonnull List<Branch> branches, @Nullable Cell bounds,
            @Nullable Cell entry, int maxTries) {
        this(id, roomTags, strata, path, links, branches, bounds, entry, maxTries, List.of(), List.of());
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
     * A distance rooms sharing a tag keep: any two rooms that both carry {@code tag} (folded, matched as a room's
     * expanded tags are: a key, a value or {@code key=value}) have their nearest cells at least {@code blocks} apart,
     * such as two boss arenas whose fights reach a hundred blocks round them.
     */
    public record Apart(@Nonnull String tag, int blocks) {

        public Apart {
            String folded = Words.fold(tag);
            tag = folded == null ? "" : folded;
            blocks = Math.max(0, blocks);
        }
    }

    /**
     * How many times one room may stand in a run: each room that fills {@code role} (when one is named) and carries
     * {@code tag} (when one is named) is placed at most {@code max} times, counted per room, so a run of puzzles meets
     * as many different puzzles as the catalogue holds before it meets one twice. Both folded.
     */
    public record MaxPerRun(@Nullable String role, @Nullable String tag, int max) {

        public MaxPerRun {
            role = Words.fold(role);
            tag = Words.fold(tag);
            max = Math.max(1, max);
        }

        /** Whether it names a role or a tag; a rule naming neither binds nothing. */
        public boolean binds() {
            return role != null || tag != null;
        }

        /** Whether {@code room} is one this rule counts. */
        public boolean matches(@Nonnull RoomSpec room) {
            return binds() && (role == null || room.hasRole(role)) && (tag == null || room.carries(tag));
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
