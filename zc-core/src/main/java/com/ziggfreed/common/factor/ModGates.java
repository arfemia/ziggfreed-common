package com.ziggfreed.common.factor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * Whether a FILE loads on this server at all: false only when a plain top-level
 * {@code hytale:mod_installed} condition with {@code Min} of 1 or more names a mod this server does not
 * run. Every keyed store applies it in its load handler, so such a file never reaches a fold, an index,
 * a draw, a contribution, a validator or an audit: content that pays a companion mod is simply not
 * there without it, and nothing logs about it.
 *
 * <p><b>Only a definite absence drops.</b> {@link ModFactors#installed} answers {@code 0} for a mod no
 * table knows and {@code null} when it cannot tell (a malformed {@code Param}, no plugin table yet). A
 * {@code null} keeps the file: the live hide axis ({@code FeatureLift} in zc-progression) still reads it
 * as off, so it hides rather than loads on a guess.
 *
 * <p><b>Only the plain form is a gate.</b> A bounds-less condition passes on the {@code 0} an absent mod
 * reads, so it gates nothing and drops nothing; author {@code Min: 1}. A condition with a {@code Max}, or
 * a {@code Min} below 1, is a requirement (the "only where it is NOT installed" half among them), and
 * stays where it was written. A condition nested in {@code AnyOf} or {@code Not} is never handed here:
 * a store passes its TOP-LEVEL {@code Factors} only.
 *
 * <p><b>A reward row gates the same way.</b> One row of a {@code Rewards} list may carry its own block of
 * this plain form, read through {@link #missingMod} exactly as a file's is: where its mod reads a definite
 * 0 the row is simply absent (it pays nothing, shows nowhere and no validator reads it) while the rest of
 * its file loads.
 *
 * <p><b>A drop is logged, never an id.</b> Each store's fold reports what it dropped through
 * {@link #reportPackFiles}, {@link #reportOwnerOverrides} and {@link #reportRewardRows}: one INFO line per
 * store per missing mod, only when something was dropped, counting the files (or the rows) and naming the
 * mod. The wording is a contract (a season boot check parses it), and a line naming a dropped file would
 * put the missing mod's content ids into the log of the very server that lacks it.
 */
public final class ModGates {

    /**
     * What a dropped file's mod reads as when nothing named it: a condition with no {@code Param}, or a
     * caller that refused an id without naming its mod (a test's plain id set).
     */
    private static final String UNNAMED_MOD = "?";

    /** What a mod's presence reads as: the factor's own reading, unless a test swapped it. */
    private static volatile Function<String, Double> probe = ModFactors::installed;

    /** Where a drop line goes: the library's log, unless a test swapped it. */
    private static volatile Consumer<String> reports = SafeLog::info;

    private ModGates() {
    }

    /** {@link #keep(FactorCondition[])} over a list. */
    public static boolean keep(@Nullable List<FactorCondition> topLevelFactors) {
        return missingMod(topLevelFactors) == null;
    }

    /**
     * Does a file whose top-level {@code Factors} are {@code topLevelFactors} load here? False when any
     * of them is a presence gate ({@link #isPresenceGate}) on a mod that reads a definite 0.
     */
    public static boolean keep(@Nullable FactorCondition[] topLevelFactors) {
        return missingMod(topLevelFactors) == null;
    }

    /** {@link #missingMod(FactorCondition[])} over a list. */
    @Nullable
    public static String missingMod(@Nullable List<FactorCondition> topLevelFactors) {
        return topLevelFactors == null ? null : missingMod(topLevelFactors.toArray(new FactorCondition[0]));
    }

    /**
     * The mod that keeps a file with these top-level {@code Factors} out of this server, as its condition
     * wrote it ({@code Group:Name}, trimmed): the first presence gate ({@link #isPresenceGate}) whose mod
     * reads a definite 0. Null exactly when {@link #keep} is true, so a store's drop line can say which
     * mod a file waits for.
     */
    @Nullable
    public static String missingMod(@Nullable FactorCondition[] topLevelFactors) {
        if (topLevelFactors == null) {
            return null;
        }
        for (FactorCondition condition : topLevelFactors) {
            if (isPresenceGate(condition) && isAbsent(condition.getParam())) {
                return modName(condition.getParam());
            }
        }
        return null;
    }

    /**
     * Log what a store's pack fold dropped: one line per missing mod, counting the files, never naming
     * one. Nothing at all when {@code missingMods} is empty.
     *
     * @param store       the registrar's name for the store ({@code Quests}, {@code GearSets}, ...)
     * @param missingMods one entry per dropped file: the mod it waited for
     */
    public static void reportPackFiles(@Nonnull String store, @Nonnull Collection<String> missingMods) {
        report(store, "pack file(s)", missingMods);
    }

    /**
     * Log what a store's owner layer dropped (an owner entry gated on a missing mod, or an override of
     * a pack file the gate refused): one line per missing mod, counting the entries, never naming one.
     *
     * @param missingMods one entry per dropped owner entry: the mod it, or the pack file it follows,
     *                    waited for
     */
    public static void reportOwnerOverrides(@Nonnull String store, @Nonnull Collection<String> missingMods) {
        report(store, "owner override(s)", missingMods);
    }

    /**
     * Log what a store's fold left out of the reward rows its files carry (a row whose own {@code Requires}
     * gates on a missing mod): one line per missing mod, counting the rows, never naming one or its file.
     * Nothing at all when {@code missingMods} is empty. Each fold logs its own total, so a re-fold or a hot
     * re-import repeats the line and the last one wins. A row read outside any store's fold (a dialogue
     * action's or an interaction's inline {@code Rewards}) is absent the same way, and never counted.
     *
     * @param store       the store's contract label (its {@code MOD_GATE_STORE})
     * @param missingMods one entry per row left out: the mod it waited for
     */
    public static void reportRewardRows(@Nonnull String store, @Nonnull Collection<String> missingMods) {
        report(store, "reward row(s)", missingMods);
    }

    /** Send the drop lines to {@code sink} instead of the log; null puts the log back. */
    public static void reportIntoForTests(@Nullable Consumer<String> sink) {
        reports = sink == null ? SafeLog::info : sink;
    }

    private static void report(@Nonnull String store, @Nonnull String what, @Nonnull Collection<String> missingMods) {
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String mod : missingMods) {
            counts.merge(modName(mod), 1, Integer::sum);
        }
        Consumer<String> sink = reports;
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            sink.accept("[zc] mod gate: " + store + " dropped " + count.getValue() + " " + what
                    + " gated on a missing mod (" + count.getKey() + ")");
        }
    }

    @Nonnull
    private static String modName(@Nullable String param) {
        return param == null || param.isBlank() ? UNNAMED_MOD : param.trim();
    }

    /**
     * Is {@code condition} the plain "that mod is installed" form: {@code hytale:mod_installed} (any
     * case), no {@code Max}, and a {@code Min} of 1 or more?
     */
    public static boolean isPresenceGate(@Nullable FactorCondition condition) {
        if (condition == null || condition.isBlank() || condition.getMax() != null) {
            return false;
        }
        Double min = condition.getMin();
        return min != null && min >= 1.0
                && ModFactors.MOD_INSTALLED.equalsIgnoreCase(condition.getFactor().trim());
    }

    /** A definite 0 from the probe; a null, a throw or anything else is "cannot tell". */
    private static boolean isAbsent(@Nullable String param) {
        Double reading;
        try {
            reading = probe.apply(param);
        } catch (Throwable t) {
            return false;
        }
        return reading != null && reading.doubleValue() == 0.0;
    }

    /** Answer presence through {@code testProbe} instead; null puts the factor's own reading back. */
    public static void useProbeForTests(@Nullable Function<String, Double> testProbe) {
        probe = testProbe == null ? ModFactors::installed : testProbe;
    }
}
