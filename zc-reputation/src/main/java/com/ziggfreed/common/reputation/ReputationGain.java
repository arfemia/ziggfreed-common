package com.ziggfreed.common.reputation;

import java.util.Collection;

import javax.annotation.Nonnull;

import com.ziggfreed.common.stats.StatAliases;
import com.ziggfreed.common.track.gain.TrackGainStats;

/**
 * How much more standing a player gains from what they wear: a percent bonus read off two stats any item's
 * native {@code StatModifiers} raise (Additive on the Max, as the library's {@code DamageBonus_<Type>} stats
 * are), both of the track gain kind skill XP reads (R911): {@value #GLOBAL_STAT}, the {@code Reputation}
 * category's line, for every reputation, and {@code TrackGain_<Id>} for one ({@link #statFor}, the id spelled as
 * the native {@code ReputationGroup} spells it), each in whole percent points (10 is +10%) and added together.
 * The all-track {@code TrackGain} (Bonus XP) is not among them, so it never speeds standing.
 *
 * <p>The bonus multiplies a GAIN at the one writer, {@link ReputationService#change}, before the reputation's
 * Cap cuts it, so every way standing is paid takes it (a kill, a Reputation reward on a quest, a contract, a
 * daily or anything else that pays the kind) and so does earned standing past the top rank, where Beyond
 * counts. A loss is never scaled, and the Cap still wins. The shared stat ships from zc-entity
 * ({@code Server/Entity/Stats/CategoryGain_Reputation.json}); a pack that wants gear to favour one of its own
 * reputations ships {@code TrackGain_<Id>.json} in the same shape. A reputation's track never runs zc-track's
 * {@code Bonus} factor ({@link ReputationTracks#GAIN_FACTORS}), so these stats count once, here.
 *
 * <p>The retired {@value #RETIRED_GLOBAL_STAT} and {@code ReputationGain_<Id>} ids read as these
 * ({@link #registerRetiredAliases}), so a saved roll or a write under an old name still lands.
 *
 * <p>{@link #held} and {@link #scale} are the one arithmetic, pure so a test reaches them without the engine:
 * the writer scales by the held total and the Reputation page shows it, so both read one rule.
 */
public final class ReputationGain {

    /** The stat every reputation's gain reads, the Reputation category's track gain stat: whole percent points. */
    public static final String GLOBAL_STAT = TrackGainStats.CATEGORY_PREFIX + ReputationTracks.CATEGORY;

    /** The per-reputation stat id prefix, the track gain stats': {@code TrackGain_<Id>}, whole percent points. */
    public static final String STAT_PREFIX = TrackGainStats.STAT_PREFIX;

    /** The retired shared stat, read as {@value #GLOBAL_STAT}. */
    public static final String RETIRED_GLOBAL_STAT = "ReputationGain";

    /** The retired per-reputation prefix: {@code ReputationGain_<Id>}, read as {@code TrackGain_<Id>}. */
    public static final String RETIRED_STAT_PREFIX = "ReputationGain_";

    /** The lowest total that counts: at -100 a gain shrinks to its floor of 1, never to nothing or a loss. */
    public static final long MIN_PERCENT = -100L;

    /**
     * A loose ceiling on the total, a safety rail rather than a balance tool: no worn gear should come near it,
     * and it keeps a runaway stat from multiplying a gain past reason.
     */
    public static final long MAX_PERCENT = 1_000L;

    private ReputationGain() {
    }

    /** The per-reputation stat for {@code reputationId}: {@code TrackGain_<Id>}, its track's own gain stat. */
    @Nonnull
    public static String statFor(@Nonnull String reputationId) {
        return TrackGainStats.statFor(reputationId);
    }

    /**
     * Read the retired ids as today's: {@value #RETIRED_GLOBAL_STAT} as {@value #GLOBAL_STAT}, and
     * {@code ReputationGain_<Id>} as {@code TrackGain_<Id>} for each of {@code reputationIds}. Registering again
     * replaces, so a refresh after a companion fold keeps the set current.
     */
    public static void registerRetiredAliases(@Nonnull Collection<String> reputationIds) {
        StatAliases.register(RETIRED_GLOBAL_STAT, GLOBAL_STAT);
        for (String id : reputationIds) {
            if (id != null && !id.isBlank()) {
                StatAliases.register(RETIRED_STAT_PREFIX + id.trim(), statFor(id));
            }
        }
    }

    /** The total of {@code percentPoints} that counts: held to [{@link #MIN_PERCENT}, {@link #MAX_PERCENT}]. */
    public static long held(long percentPoints) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percentPoints));
    }

    /**
     * {@code delta} with {@code percentPoints} of bonus applied: a gain times {@code 1 + percent / 100},
     * rounded half up, and never under 1, the total {@link #held}; a loss or a zero passes unchanged. The Cap
     * is the caller's, applied after this.
     */
    public static int scale(int delta, long percentPoints) {
        if (delta <= 0) {
            return delta;
        }
        long percent = held(percentPoints);
        if (percent == 0L) {
            return delta;
        }
        long scaled = Math.round((long) delta * (100L + percent) / 100.0);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, scaled));
    }
}
