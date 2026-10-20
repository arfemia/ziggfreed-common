package com.ziggfreed.common.track.gain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The gain stats (R734): how much more a player earns on a track from what they wear, eat or picked, read as native
 * entity stats the way a reputation's {@code ReputationGain} is. Three stats, each in whole percent points (10 is
 * +10%) and added together: {@value #GLOBAL_STAT} for every track, {@code TrackGain_<Category>} for the tracks of one
 * category and {@code TrackGain_<Track>} for one track ({@link #statFor}, spelled as the category and the track file
 * are named). The library ships {@value #GLOBAL_STAT} and the ceiling {@value #CEILING_STAT} (zc-entity's
 * {@code Server/Entity/Stats/}); a pack that wants gear to favour a category or a track ships that stat in the same
 * shape, and one nobody ships counts nothing.
 *
 * <p>A consumer whose older stats already meant "more XP on these tracks" names them as sources
 * ({@link #registerSource}), so its items keep working: their totals add to the same sum, under the same ceiling.
 *
 * <p>{@link BonusGainFactor} is the one reader, as the gain pipeline's {@value GainFactors#BONUS} factor; {@link #held}
 * and {@link #multiplier} are its arithmetic, pure so a test and a page read one rule.
 */
public final class TrackGainStats {

    /** The stat every track's gain reads: whole percent points. */
    public static final String GLOBAL_STAT = "TrackGain";

    /** The per-category and per-track stat id prefix: {@code TrackGain_<Id>}. */
    public static final String STAT_PREFIX = "TrackGain_";

    /** The ceiling stat: the most the summed gain stats lift a gain, whole percent points. */
    public static final String CEILING_STAT = "TrackGainMax";

    /** The ceiling a player gets when the ceiling stat cannot be read: the shipped {@code TrackGainMax.json}'s Max. */
    public static final double DEFAULT_CEILING = 1000.0;

    /** The lowest total that counts: at -100 a gain earns nothing, never a loss. */
    public static final double MIN_PERCENT = -100.0;

    /** Each consumer's sources, by owner (one registration per owner; registering again replaces it). */
    private static final Map<String, Function<GainScope, ? extends Collection<String>>> SOURCES =
            new ConcurrentHashMap<>();

    private TrackGainStats() {
    }

    /** The per-category or per-track stat for {@code id}: {@code TrackGain_<Id>}. */
    @Nonnull
    public static String statFor(@Nonnull String id) {
        return STAT_PREFIX + id.trim();
    }

    /**
     * Name the stats that add to a track's gain beyond the library's own, for each track ({@code stats} answers the
     * stat ids for a track's scope, or an empty list for a track that is not the consumer's), under {@code owner}
     * (registering again under the same owner replaces it). Every compiled gain plan is built again. Setup, or
     * whenever the consumer's roster changes. A blank owner or a null function is ignored.
     */
    public static void registerSource(@Nullable String owner,
            @Nullable Function<GainScope, ? extends Collection<String>> stats) {
        if (owner == null || owner.isBlank() || stats == null) {
            return;
        }
        SOURCES.put(owner.trim().toLowerCase(Locale.ROOT), stats);
        GainPlans.invalidateAll();
    }

    /**
     * Every stat id that adds to {@code scope}'s gain, each once in the order the reading is explained: the global
     * stat, the category's, the track's, then each consumer's sources. A source that throws adds nothing, warned.
     */
    @Nonnull
    public static List<String> statIdsFor(@Nonnull GainScope scope) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(GLOBAL_STAT);
        String category = scope.categoryId();
        if (category != null && !category.isBlank()) {
            ids.add(statFor(category));
        }
        String track = scope.def().asset().getId();
        if (track != null && !track.isBlank()) {
            ids.add(statFor(track));
        }
        for (Map.Entry<String, Function<GainScope, ? extends Collection<String>>> source : SOURCES.entrySet()) {
            try {
                Collection<String> named = source.getValue().apply(scope);
                if (named == null) {
                    continue;
                }
                for (String id : named) {
                    if (id != null && !id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
            } catch (Throwable t) {
                SafeLog.warn("[track] the gain stat source '" + source.getKey() + "' failed, so it adds nothing: "
                        + t.getMessage());
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The total of {@code percentPoints} that counts: held to [{@value #MIN_PERCENT}, {@code ceiling}], the ceiling
     * itself never below {@value #MIN_PERCENT}; a total or a ceiling that is not a number counts as none and as the
     * {@linkplain #DEFAULT_CEILING default} respectively.
     */
    public static double held(double percentPoints, double ceiling) {
        double top = Double.isFinite(ceiling) ? Math.max(MIN_PERCENT, ceiling) : DEFAULT_CEILING;
        double points = Double.isFinite(percentPoints) ? percentPoints : 0.0;
        return Math.max(MIN_PERCENT, Math.min(top, points));
    }

    /** What a gain is multiplied by for {@code percentPoints} of gain stats under {@code ceiling}: 1 plus the held percent. */
    public static double multiplier(double percentPoints, double ceiling) {
        return 1.0 + held(percentPoints, ceiling) / 100.0;
    }
}
