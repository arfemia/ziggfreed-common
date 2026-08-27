package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.factor.ModFactors;

/**
 * Lifts a TOP-LEVEL feature or mod-presence condition out of a {@code Requires} block and into the
 * owning definition's feature list - the one transformation a consumer with a HIDE axis applies
 * before evaluating a gate.
 *
 * <p>Everything else in the block is already the model {@link GateEvaluator} reads, so there is
 * nothing to convert. A feature toggle and a mod-presence check are the exception because they
 * answer a different question from every other requirement: they decide whether the content EXISTS
 * on this server, not whether one player has earned their way to it. Content whose feature is off,
 * or whose companion mod is not installed, should vanish from every listing rather than sit there
 * locked, and the feature list is what does that.
 *
 * <p>ONE lift for every content kind a consumer folds, so a quest and an achievement asking for
 * the same feature or mod can never be treated two different ways.
 *
 * <h2>Two factor ids, one hide axis</h2>
 *
 * <p>The lifted set is the caller's own feature factor ({@code <namespace>:feature}, the id
 * {@link FeatureFlags} contributes for a declaring mod - {@code Param} a feature id) and
 * {@link ModFactors#MOD_INSTALLED} ({@code hytale:mod_installed}, {@code Param} a mod's
 * {@code Group:Name}). Both land in the SAME {@link Result#features} list, mixed in authored
 * order, for the caller's own availability predicate to answer. Every other factor id is left
 * exactly where it was authored; this is a fixed, explicit pair, never a mode a condition selects
 * between.
 *
 * <h2>Only the top level, and only the plain form</h2>
 *
 * <p>A lifted-id condition nested inside an {@code AnyOf} means "either this feature/mod or that
 * other route", which is a genuine either-or a player can satisfy, so it stays in the gate and is
 * answered live. An upper bound means "only while it is OFF" (or "only where that mod is NOT
 * installed"), which is a real requirement too and equally stays put. Only the plain "that feature
 * is on" / "that mod is installed" form at the top level is lifted, written either bounds-less or
 * with an explicit {@code Min: 1} (both read the same way here) - {@code Max} present at all, or
 * {@code Min} above 1, takes it out of the lift and it stays a live requirement instead.
 *
 * <p><b>A nested or upper-bounded condition on either id needs its bound written explicitly.</b>
 * The feature factor and {@link ModFactors#MOD_INSTALLED} both answer a definite {@code 0} for
 * "off"/"not installed" rather than the unanswerable {@code null} most of this vocabulary reads
 * for its absent case - so once a condition on either id is NOT lifted (nested in
 * {@code AnyOf}/{@code Not}, or carrying its own bound), a BOUNDS-LESS form passes trivially on
 * that {@code 0} (see {@code FactorCondition#accepts}'s "no bounds plus any non-null value passes"
 * rule) and gates nothing at all. Author {@code Min: 1} explicitly whenever one of these ids is
 * used anywhere other than the plain top-level form this class lifts.
 *
 * <p>The lifted condition is REMOVED from the block it came from. Leaving it would evaluate the
 * same fact twice for one answer, and put a redundant lock reason in front of a player for content
 * the feature list has already hidden.
 */
public final class FeatureLift {

    private FeatureLift() {
    }

    /**
     * The gate as the consumer evaluates it, plus what was lifted out of it.
     *
     * @param requires   the block with any lifted feature/mod conditions removed, or null when what
     *                   is left asks for nothing
     * @param features   feature ids AND mod {@code Group:Name} ids lifted from top-level conditions
     *                   on the lifted pair, in authored order (mixed together, since both land in
     *                   one hide-axis list the caller's availability predicate answers)
     * @param permission the top-level permission node, or null when none was authored
     */
    public record Result(@Nullable GateSpec requires, @Nonnull List<String> features,
                         @Nullable String permission) {

        /** The empty result, for content that authored no requirements at all. */
        public static final Result OPEN = new Result(null, List.of(), null);

        public Result {
            features = List.copyOf(features);
        }
    }

    /**
     * Lift one {@code Requires} block.
     *
     * @param featureFactorId the caller's own feature factor id ({@code <namespace>:feature}); a
     *                        top-level plain condition on it or on
     *                        {@link ModFactors#MOD_INSTALLED} is what gets lifted
     * @param contentId       the owning content id, for anything that has to be reported by name
     * @param warn            where a finding about this content goes (an unanswerable custom kind)
     */
    @Nonnull
    public static Result lift(@Nullable GateSpec requires, @Nonnull String featureFactorId,
            @Nonnull String contentId, @Nonnull Consumer<String> warn) {
        if (requires == null || requires.isEmpty()) {
            return Result.OPEN;
        }
        for (String kind : requires.customOrEmpty().keySet()) {
            warn.accept("'" + contentId + "' requires the custom gate '" + kind + "', which no"
                    + " installed mod provides, so it stays locked. Install the mod that owns it, or"
                    + " remove the entry.");
        }

        List<String> features = new ArrayList<>();
        List<FactorCondition> kept = new ArrayList<>();
        for (FactorCondition condition : requires.factorsOrEmpty()) {
            if (condition == null || condition.isBlank()) {
                continue;
            }
            String feature = liftedFeature(condition, featureFactorId);
            if (feature != null) {
                features.add(feature);
            } else {
                kept.add(condition);
            }
        }

        GateSpec remaining = features.isEmpty() ? requires
                : GateSpec.of(kept.toArray(new FactorCondition[0]), requires.getPermission(),
                        requires.getQuests(), requires.getCustom(), requires.getAllOf(),
                        requires.getAnyOf(), requires.getNot());
        return new Result(remaining.isEmpty() ? null : remaining, features,
                trimToNull(requires.getPermission()));
    }

    /**
     * The feature id (or mod {@code Group:Name}) this condition is the plain "that is on" /
     * "that mod is installed" form of, or null when it is anything else (a factor outside the
     * lifted pair, an upper bound, a condition naming no param).
     *
     * <p>A feature id is folded to lower case, matching the case-insensitive way a feature table is
     * read. A mod's {@code Group:Name} is kept exactly as authored: the engine's plugin table
     * matches both halves case-sensitively (see {@link ModFactors}), so lower-casing it here would
     * make a correctly-installed mod read as absent.
     */
    @Nullable
    private static String liftedFeature(@Nonnull FactorCondition condition,
            @Nonnull String featureFactorId) {
        String factorId = condition.getFactor();
        if (factorId == null
                || !(featureFactorId.equalsIgnoreCase(factorId)
                        || ModFactors.MOD_INSTALLED.equalsIgnoreCase(factorId))) {
            return null;
        }
        if (condition.getMax() != null) {
            return null;
        }
        Double min = condition.getMin();
        if (min != null && min > 1.0) {
            return null;
        }
        String param = trimToNull(condition.getParam());
        if (param == null) {
            return null;
        }
        return ModFactors.MOD_INSTALLED.equalsIgnoreCase(factorId) ? param : param.toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
