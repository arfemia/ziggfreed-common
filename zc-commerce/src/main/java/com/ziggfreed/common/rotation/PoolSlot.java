package com.ziggfreed.common.rotation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One position a rotating pool fills, and what may fill it.
 *
 * <p>The RUNTIME slot, not the authored shape. Each domain's authoring layer keeps the word its own
 * authors use for a grade - a contract's difficulty band, an offer's tier - and folds it into the
 * one neutral {@link #tier()} the draw filters on, so the two sides can differ in vocabulary
 * without the engine growing a second eligibility rule.
 *
 * <p>{@link #tier()} is a free content string the engine never interprets, matched
 * case-insensitively; null accepts anything. {@link #tag()} narrows further on a second free
 * string, for a pool that wants two independent axes.
 *
 * <p>{@link #count()} draws that many DISTINCT candidates into consecutive positions. An
 * {@link #optional()} slot is skipped in silence when the pool cannot fill it, which is how a board
 * offers a hard contract only on the days one exists.
 */
public final class PoolSlot {

    /** No slot at all: accepts any candidate, one position, required. */
    public static final PoolSlot ANY = new PoolSlot(null, null, 1, false);

    @Nullable private final String tier;
    @Nullable private final String tag;
    private final int count;
    private final boolean optional;

    private PoolSlot(@Nullable String tier, @Nullable String tag, int count, boolean optional) {
        this.tier = (tier == null || tier.isBlank()) ? null : tier.trim();
        this.tag = (tag == null || tag.isBlank()) ? null : tag.trim();
        this.count = Math.max(1, count);
        this.optional = optional;
    }

    /** A slot filtering on a grade and a tag. */
    @Nonnull
    public static PoolSlot of(@Nullable String tier, @Nullable String tag, int count, boolean optional) {
        return new PoolSlot(tier, tag, count, optional);
    }

    /** {@code count} positions of one grade. */
    @Nonnull
    public static PoolSlot tier(@Nullable String tier, int count) {
        return new PoolSlot(tier, null, count, false);
    }

    /** The grade a candidate must carry, or null when any will do. */
    @Nullable
    public String tier() {
        return tier;
    }

    /** The extra tag a candidate must carry, or null when any will do. */
    @Nullable
    public String tag() {
        return tag;
    }

    /** How many positions this slot fills; at least one. */
    public int count() {
        return count;
    }

    /** True when an unfillable slot should be skipped rather than left as a hole. */
    public boolean optional() {
        return optional;
    }

    /**
     * Does a candidate graded {@code candidateTier} and tagged {@code candidateTag} qualify here?
     * The ONE eligibility test, so the draw and any validator hunting an unfillable slot cannot
     * disagree about what could have gone there. Both axes match case-insensitively.
     */
    public boolean accepts(@Nullable String candidateTier, @Nullable String candidateTag) {
        if (tier != null && !tier.equalsIgnoreCase(candidateTier == null ? "" : candidateTier.trim())) {
            return false;
        }
        return tag == null || tag.equalsIgnoreCase(candidateTag == null ? "" : candidateTag.trim());
    }

    @Override
    public String toString() {
        return "PoolSlot[" + (tier == null ? "any" : tier) + " x" + count
                + (optional ? ", optional" : "") + "]";
    }
}
