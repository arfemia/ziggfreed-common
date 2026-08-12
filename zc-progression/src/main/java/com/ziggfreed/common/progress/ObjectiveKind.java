package com.ziggfreed.common.progress;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * One entry in an engine's objective vocabulary: the id content authors write, plus the two facts
 * the engine and its validators need about it.
 *
 * <p>The two flags are INDEPENDENT knobs, not a category:
 * <ul>
 *   <li>{@code valueBased} decides which arithmetic a dispatch uses - a high-water mark
 *   ({@link ObjectiveProgressState#applyValue}) instead of an accumulating delta
 *   ({@link ObjectiveProgressState#advance}). Set it when producers fire the player's CURRENT
 *   value rather than an increment, or a run of 5 then 4 wrongly tallies 9.
 *   <li>{@code producible} decides whether authored content may USE the id. An unproducible kind
 *   parses and renders but nothing ever fires it, so a validator rejects authoring one instead of
 *   letting a dead objective ship. Register a kind unproducible when the vocabulary exists before
 *   its producer does.
 * </ul>
 *
 * <p>{@code id} is normalized to upper case at construction, which is the spelling every surface
 * displays; lookups themselves are case-insensitive.
 */
public record ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible) {

    public ObjectiveKind {
        id = id.trim().toUpperCase(Locale.ROOT);
    }

    /** An accumulating, producible kind - the common case. */
    @Nonnull
    public static ObjectiveKind of(@Nonnull String id) {
        return new ObjectiveKind(id, false, true);
    }

    /** A producible kind whose producers fire a current value rather than a delta. */
    @Nonnull
    public static ObjectiveKind valueBased(@Nonnull String id) {
        return new ObjectiveKind(id, true, true);
    }
}
