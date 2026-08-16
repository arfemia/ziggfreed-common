package com.ziggfreed.common.progress;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * One entry in an engine's objective vocabulary: the id content authors write, plus the facts the
 * engine and its validators need about it.
 *
 * <p>The flags are INDEPENDENT knobs, not a category:
 * <ul>
 *   <li>{@code valueBased} decides which arithmetic a dispatch uses - a high-water mark
 *   ({@link ObjectiveProgressState#applyValue}) instead of an accumulating delta
 *   ({@link ObjectiveProgressState#advance}). Set it when producers fire the player's CURRENT
 *   value rather than an increment, or a run of 5 then 4 wrongly tallies 9.
 *   <li>{@code producible} decides whether authored content may USE the id. An unproducible kind
 *   parses and renders but nothing ever fires it, so a validator rejects authoring one instead of
 *   letting a dead objective ship. Register a kind unproducible when the vocabulary exists before
 *   its producer does.
 *   <li>{@code targetsPlace} says what an objective's TARGET names: a place a player can stand at
 *   (a character, a location) rather than a thing an event carries (a block, an item, an entity).
 *   It is what lets a listing say "this step resolves HERE" for a step with no hand-in of its own,
 *   so set it for a kind whose target is somewhere to go. The comparison it feeds is one whole id
 *   against one whole id: a place is never matched by prefix or substring, because a target written
 *   to catch a family of block ids would otherwise catch character ids too.
 * </ul>
 *
 * <p>{@code id} is normalized to upper case at construction, which is the spelling every surface
 * displays; lookups themselves are case-insensitive.
 */
public record ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                            boolean targetsPlace) {

    public ObjectiveKind {
        id = id.trim().toUpperCase(Locale.ROOT);
    }

    /** A kind whose target names a thing rather than a place, which is the common case. */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible) {
        this(id, valueBased, producible, false);
    }

    /** An accumulating, producible kind - the common case. */
    @Nonnull
    public static ObjectiveKind of(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, false);
    }

    /** A producible kind whose producers fire a current value rather than a delta. */
    @Nonnull
    public static ObjectiveKind valueBased(@Nonnull String id) {
        return new ObjectiveKind(id, true, true, false);
    }

    /** A producible kind whose target names somewhere to go. */
    @Nonnull
    public static ObjectiveKind placeTargeted(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, true);
    }
}
