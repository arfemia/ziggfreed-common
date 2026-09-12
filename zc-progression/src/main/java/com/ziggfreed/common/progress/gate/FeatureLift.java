package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FeatureFlags;
import com.ziggfreed.common.factor.ModFactors;

/**
 * Lifts a TOP-LEVEL feature or mod-presence condition out of a {@code Requires} block and into the
 * owning definition's feature list - the one transformation a HIDE axis applies before evaluating
 * a gate.
 *
 * <p>Everything else in the block is already the model {@link GateEvaluator} reads, so there is
 * nothing to convert. A feature toggle and a mod-presence check are the exception because they
 * answer a different question from every other requirement: they decide whether the content EXISTS
 * on this server, not whether one player has earned their way to it. Content whose feature is off,
 * or whose companion mod is not installed, should vanish from every listing rather than sit there
 * locked, and the feature list is what does that.
 *
 * <p>ONE lift for every content kind a fold produces, so a quest and an achievement asking for
 * the same feature or mod can never be treated two different ways.
 *
 * <h2>Two shapes of lifted id, one hide axis</h2>
 *
 * <p>The lifted set is a feature factor ({@code <namespace>:feature}, the id {@link FeatureFlags}
 * contributes for a declaring mod - {@code Param} a feature id) and
 * {@link ModFactors#MOD_INSTALLED} ({@code hytale:mod_installed}, {@code Param} a mod's
 * {@code Group:Name}). Both land in the SAME {@link Result#lifted} list, mixed in authored order,
 * for the availability read to answer. Every other factor id is left exactly where it was
 * authored; this is a fixed, explicit pair, never a mode a condition selects between.
 *
 * <p>Which feature factors count is the caller's choice between two entry points. A consumer with
 * a hide axis of its own names ITS OWN factor ({@link #lift(GateSpec, String, String, Consumer)});
 * the shared folds lift for EVERY namespace that has declared features
 * ({@link #liftKnown(GateSpec)}), so a file written for any mod on the server hides where that
 * mod's feature is off. A feature factor whose namespace nothing has declared is left in the
 * gate, where the standing fail-closed rule keeps the content locked rather than hiding it - the
 * honest answer for a namespace that may simply not be installed yet.
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
 *
 * <h2>The live read</h2>
 *
 * <p>A lifted condition is answered by {@link Lifted#isOn()} at the moment it is asked, through
 * the SAME readings the factor providers use ({@link FeatureFlags#read} and
 * {@link ModFactors#installed}), so a feature toggled while the server is up moves the content on
 * the next look and a gate on the same id could never have disagreed. {@link #allOn} is the whole
 * availability answer for one definition's lifted list.
 */
public final class FeatureLift {

    private FeatureLift() {
    }

    /**
     * One condition lifted out of a block: which factor it was on and what it named.
     *
     * @param factorId the factor the condition was authored on - a declared namespace's feature
     *                 factor, or {@link ModFactors#MOD_INSTALLED}
     * @param param    the condition's {@code Param} as authored, trimmed: a feature id, or a mod's
     *                 {@code Group:Name}
     */
    public record Lifted(@Nonnull String factorId, @Nonnull String param) {

        /** Is this the presence of a mod, rather than a feature switch? */
        public boolean isModPresence() {
            return ModFactors.MOD_INSTALLED.equalsIgnoreCase(factorId);
        }

        /**
         * The id the flat hide-axis list carries: a feature id folded to lower case, matching the
         * case-insensitive way a feature table is read, or a mod's {@code Group:Name} kept exactly
         * as authored, because the engine's plugin table matches both halves case-sensitively (see
         * {@link ModFactors}) and lower-casing it would make a correctly-installed mod read as
         * absent.
         */
        @Nonnull
        public String featureId() {
            return isModPresence() ? param : param.toLowerCase(Locale.ROOT);
        }

        /**
         * Does the condition hold RIGHT NOW? A feature reads on through
         * {@link FeatureFlags#read}, a mod through {@link ModFactors#installed}; either answering
         * anything but a definite 1 reads as off, so content whose namespace vanished or whose mod
         * cannot be looked up yet hides rather than appearing on a guess.
         */
        public boolean isOn() {
            Double reading = isModPresence()
                    ? ModFactors.installed(param)
                    : FeatureFlags.read(FeatureFlags.namespaceOf(factorId), param);
            return reading != null && reading >= 1.0;
        }
    }

    /**
     * The gate as it is evaluated afterwards, plus what was lifted out of it.
     *
     * @param requires   the block with any lifted feature/mod conditions removed, or null when what
     *                   is left asks for nothing
     * @param lifted     every lifted condition, in authored order (feature switches and mod
     *                   presence mixed together, since both land in one hide-axis list)
     * @param permission the top-level permission node, or null when none was authored
     */
    public record Result(@Nullable GateSpec requires, @Nonnull List<Lifted> lifted,
                         @Nullable String permission) {

        /** The empty result, for content that authored no requirements at all. */
        public static final Result OPEN = new Result(null, List.of(), null);

        public Result {
            lifted = List.copyOf(lifted);
        }

        /**
         * The flat hide-axis list: feature ids AND mod {@code Group:Name} ids, in authored order,
         * each spelled as {@link Lifted#featureId()} says. The shape a consumer's own availability
         * predicate has always read.
         */
        @Nonnull
        public List<String> features() {
            List<String> out = new ArrayList<>(lifted.size());
            for (Lifted entry : lifted) {
                out.add(entry.featureId());
            }
            return List.copyOf(out);
        }
    }

    /**
     * Lift one {@code Requires} block for a consumer with a hide axis of its own.
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
        return lift(requires, factorId -> featureFactorId.equalsIgnoreCase(factorId));
    }

    /**
     * Lift one {@code Requires} block for EVERY feature namespace declared on this server (the ones
     * {@link FeatureFlags#namespaces()} lists) plus {@link ModFactors#MOD_INSTALLED} - the shared
     * folds' entry point, so a file gating on any installed mod's feature hides where that feature
     * is off. Which namespaces count is read at the moment of the call, so fold after every mod has
     * declared its features. Nothing is reported here; the content audit already names an
     * unanswerable custom kind.
     */
    @Nonnull
    public static Result liftKnown(@Nullable GateSpec requires) {
        if (requires == null || requires.isEmpty()) {
            return Result.OPEN;
        }
        return lift(requires, FeatureFlags::isFeatureFactor);
    }

    /** Does every lifted condition hold right now? An empty list holds trivially. */
    public static boolean allOn(@Nonnull List<Lifted> lifted) {
        for (Lifted entry : lifted) {
            if (!entry.isOn()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lift itself: a top-level plain condition on {@link ModFactors#MOD_INSTALLED} or on any
     * factor {@code isFeatureFactor} accepts moves out; everything else stays exactly where it was.
     */
    @Nonnull
    private static Result lift(@Nonnull GateSpec requires, @Nonnull Predicate<String> isFeatureFactor) {
        List<Lifted> lifted = new ArrayList<>();
        List<FactorCondition> kept = new ArrayList<>();
        for (FactorCondition condition : requires.factorsOrEmpty()) {
            if (condition == null || condition.isBlank()) {
                continue;
            }
            Lifted entry = liftedEntry(condition, isFeatureFactor);
            if (entry != null) {
                lifted.add(entry);
            } else {
                kept.add(condition);
            }
        }

        GateSpec remaining = lifted.isEmpty() ? requires
                : GateSpec.of(kept.toArray(new FactorCondition[0]), requires.getPermission(),
                        requires.getQuests(), requires.getCustom(), requires.getAllOf(),
                        requires.getAnyOf(), requires.getNot());
        return new Result(remaining.isEmpty() ? null : remaining, lifted,
                trimToNull(requires.getPermission()));
    }

    /**
     * The lifted entry this condition is the plain "that is on" / "that mod is installed" form of,
     * or null when it is anything else (a factor outside the lifted set, an upper bound, a
     * condition naming no param).
     */
    @Nullable
    private static Lifted liftedEntry(@Nonnull FactorCondition condition,
            @Nonnull Predicate<String> isFeatureFactor) {
        String factorId = condition.getFactor();
        if (factorId == null
                || !(isFeatureFactor.test(factorId) || ModFactors.MOD_INSTALLED.equalsIgnoreCase(factorId))) {
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
        return new Lifted(factorId.trim(), param);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
