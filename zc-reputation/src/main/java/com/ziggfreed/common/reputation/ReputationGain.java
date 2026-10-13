package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;

/**
 * How much more standing a player gains from what they wear: a percent bonus read off two stats any item's
 * native {@code StatModifiers} raise (Additive on the Max, as the library's {@code DamageBonus_<Type>} stats
 * are), the global {@value #GLOBAL_STAT} for every reputation and {@code ReputationGain_<Id>} for one
 * ({@link #statFor}, the id spelled as the native {@code ReputationGroup} spells it), each in whole percent
 * points (10 is +10%) and added together.
 *
 * <p>The bonus multiplies a GAIN at the one writer, {@link ReputationService#change}, before the reputation's
 * Cap cuts it, so every way standing is paid takes it (a kill, a Reputation reward on a quest, a contract, a
 * daily or anything else that pays the kind) and so does earned standing past the top rank, where Beyond
 * counts. A loss is never scaled, and the Cap still wins. The global stat ships from zc-entity
 * ({@code Server/Entity/Stats/ReputationGain.json}); a pack that wants gear to favour one of its own
 * reputations ships {@code ReputationGain_<Id>.json} in the same shape.
 *
 * <p>{@link #scale} is the one arithmetic, pure so a test reaches it without the engine.
 */
public final class ReputationGain {

    /** The stat every reputation's gain reads: whole percent points. */
    public static final String GLOBAL_STAT = "ReputationGain";

    /** The per-reputation stat id prefix: {@code ReputationGain_<Id>}, whole percent points. */
    public static final String STAT_PREFIX = "ReputationGain_";

    /** The lowest total that counts: at -100 a gain shrinks to its floor of 1, never to nothing or a loss. */
    public static final long MIN_PERCENT = -100L;

    /**
     * A loose ceiling on the total, a safety rail rather than a balance tool: no worn gear should come near it,
     * and it keeps a runaway stat from multiplying a gain past reason.
     */
    public static final long MAX_PERCENT = 1_000L;

    private ReputationGain() {
    }

    /** The per-reputation stat for {@code reputationId}: {@code ReputationGain_<Id>}. */
    @Nonnull
    public static String statFor(@Nonnull String reputationId) {
        return STAT_PREFIX + reputationId.trim();
    }

    /**
     * {@code delta} with {@code percentPoints} of bonus applied: a gain times {@code 1 + percent / 100},
     * rounded half up, and never under 1, the total held to [{@link #MIN_PERCENT}, {@link #MAX_PERCENT}]; a
     * loss or a zero passes unchanged. The Cap is the caller's, applied after this.
     */
    public static int scale(int delta, long percentPoints) {
        if (delta <= 0) {
            return delta;
        }
        long percent = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percentPoints));
        if (percent == 0L) {
            return delta;
        }
        long scaled = Math.round((long) delta * (100L + percent) / 100.0);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, scaled));
    }
}
