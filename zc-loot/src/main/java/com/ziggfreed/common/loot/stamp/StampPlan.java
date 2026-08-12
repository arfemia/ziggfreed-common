package com.ziggfreed.common.loot.stamp;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * A finished stamp decision: the entries to write, or a refusal.
 *
 * <p>The two ways of "nothing to write" are deliberately different, and a caller must not conflate
 * them. {@link #NOTHING} means the spec simply had no outcome this time - no candidates, no picks
 * drawn - and the attempt was a legitimate miss. {@link #denied()} means the item is FULL: every
 * point the roll produced was cut away by a budget or a per-stat ceiling.
 *
 * <p>That distinction is what stops an item being farmed for nothing. A denied plan is the signal to
 * abort the whole attempt before charging for it, so a player never pays a cost for a stamp that
 * could not have changed anything.
 */
public record StampPlan(@Nonnull List<StatRoll> entries, boolean denied) {

    /** The spec produced no outcome this time - a legitimate miss, not a refusal. */
    public static final StampPlan NOTHING = new StampPlan(List.of(), false);

    /** The item is full: everything rolled was cut away by a ceiling. Charge nothing, write nothing. */
    public static final StampPlan DENIED = new StampPlan(List.of(), true);

    public StampPlan {
        entries = List.copyOf(entries);
    }

    /** A plan that writes {@code entries}. */
    @Nonnull
    public static StampPlan of(@Nonnull List<StatRoll> entries) {
        return entries.isEmpty() ? NOTHING : new StampPlan(entries, false);
    }

    /** True when there is something to write. */
    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    /** The total points this plan would add. */
    public int totalPoints() {
        int total = 0;
        for (StatRoll roll : entries) {
            total += roll.points();
        }
        return total;
    }
}
