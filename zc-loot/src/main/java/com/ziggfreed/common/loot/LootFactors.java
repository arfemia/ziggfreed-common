package com.ziggfreed.common.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * The two readings an instance-style payout offers the factor vocabulary: how well the player did,
 * and whether they won.
 *
 * <p>They exist so a run's outcome stops being a special case. Before, "this reward needs 4000
 * points" and "this one only on a loss" were two hard-coded fields understood by exactly one loot
 * table implementation. As factors they are ordinary readings, which means a roll can gate on the
 * score the same way it gates on a tool's quality, mix it with any other factor in a chance formula,
 * and climb a ladder with it - all without the loot core learning what an instance is.
 *
 * <p>Both are answered from an {@link Outcome} carried on the {@link FactorContext} payload, so the
 * value is whatever the moment being evaluated actually had. With no outcome on the context, both
 * read as UNANSWERABLE rather than as zero: a gate over them then fails closed, which is the right
 * answer for "was this a win?" asked somewhere there was no run at all.
 */
public final class LootFactors {

    /** How well the player did, as a plain number. Higher is better; never negative. */
    public static final String INSTANCE_SCORE = "ziggfreedcommon:instance_score";

    /** Whether the run was won: 1 for a win, 0 for a loss. */
    public static final String INSTANCE_WIN = "ziggfreedcommon:instance_win";

    /** Who these registrations are attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    private LootFactors() {
    }

    /** One run's result, carried on a {@link FactorContext} payload for the two factors to read. */
    public record Outcome(int score, boolean win) {

        /** {@link #score} floored at 0, which is what the readings expose. */
        public int effectiveScore() {
            return Math.max(0, score);
        }
    }

    /** Register both readings into {@code registry}. */
    public static void registerInto(@Nonnull FactorRegistry registry, @Nullable String owner) {
        String attributed = owner == null || owner.isBlank() ? OWNER : owner;
        registry.register(INSTANCE_SCORE, attributed, scoreProvider());
        registry.register(INSTANCE_WIN, attributed, winProvider());
    }

    @Nonnull
    private static FactorProvider scoreProvider() {
        return ctx -> {
            Outcome outcome = ctx.payload(Outcome.class);
            return outcome == null ? null : (double) outcome.effectiveScore();
        };
    }

    @Nonnull
    private static FactorProvider winProvider() {
        return ctx -> {
            Outcome outcome = ctx.payload(Outcome.class);
            return outcome == null ? null : (outcome.win() ? 1.0 : 0.0);
        };
    }

    /**
     * A standalone lookup answering only these two readings, for a caller that has an outcome but no
     * registry - the pure-arithmetic path a score-tiered table takes. Every other factor id reads as
     * unanswerable, so a gate on one fails closed exactly as it would elsewhere.
     */
    @Nonnull
    public static FactorLookup lookupFor(int score, boolean win) {
        Outcome outcome = new Outcome(score, win);
        return (factorId, param) -> {
            if (INSTANCE_SCORE.equalsIgnoreCase(factorId)) {
                return (double) outcome.effectiveScore();
            }
            if (INSTANCE_WIN.equalsIgnoreCase(factorId)) {
                return outcome.win() ? 1.0 : 0.0;
            }
            return null;
        };
    }

    // ==================== the authored shapes ====================

    /** "At least this score." */
    @Nonnull
    public static FactorCondition atLeastScore(int minScore) {
        return FactorCondition.of(INSTANCE_SCORE, null, (double) minScore, null);
    }

    /** "Only on a win." */
    @Nonnull
    public static FactorCondition onWin() {
        return FactorCondition.of(INSTANCE_WIN, null, 1.0, null);
    }

    /** "Only on a loss." */
    @Nonnull
    public static FactorCondition onLoss() {
        return FactorCondition.of(INSTANCE_WIN, null, null, 0.0);
    }
}
