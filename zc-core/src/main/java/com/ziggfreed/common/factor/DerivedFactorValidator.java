package com.ziggfreed.common.factor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.validation.Finding;

/**
 * Audits asset-defined factors ({@link DerivedFactorAsset}) for the mistakes that produce NO error
 * at runtime - a definition that says nothing, one that leans on itself, one whose weight is a NaN.
 * Every one of them ends as content that quietly never appears or a number quietly stuck at its
 * base, which is far harder to chase than a finding at load.
 *
 * <p>Findings are shared {@link Finding} values, so a consumer maps them into its own reporting
 * alongside every other validator's.
 *
 * <p><b>An unknown term id is a WARNING, never an error.</b> A factor is registered by whichever
 * mod owns it, at that mod's own setup, which may well run after this audit and certainly may be a
 * mod the author expects some servers not to install. Reporting it as an error would make "this
 * bonus applies only where that mod is present" - the exact thing the value side is built to
 * express - look broken.
 */
public final class DerivedFactorValidator {

    /** The content family these findings belong to. */
    public static final String DOMAIN = "factor";

    private DerivedFactorValidator() {
    }

    /**
     * Audit every folded FILE - value definitions and naming overlays alike - in id order.
     *
     * <p>The file-level shapes are checked first: a file carrying both a {@code Factor} target and
     * a {@code Formula} is reported (the two halves address different ids, so one of them has to
     * go - drop {@code Factor} to define and name this file's own id, or drop {@code Formula} to
     * overlay the named factor); a naming-only file is VALID with no formula at all, and only warned
     * when it names nothing either. Every file that defines a value then takes the formula checks
     * below, with the cycle walk run over the defining files only.
     */
    @Nonnull
    public static List<Finding> validateAssets(@Nonnull Map<String, DerivedFactorAsset> files,
            @Nullable Predicate<String> registeredElsewhere) {
        Map<String, DerivedFactorAsset> byId = new TreeMap<>();
        for (Map.Entry<String, DerivedFactorAsset> e : files.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                byId.put(RegistryLedger.normalize(e.getKey()), e.getValue());
            }
        }
        Map<String, FactorFormula> formulas = new TreeMap<>();
        for (Map.Entry<String, DerivedFactorAsset> e : byId.entrySet()) {
            if (!e.getValue().isOverlay() && e.getValue().getFormula() != null) {
                formulas.put(e.getKey(), e.getValue().getFormula());
            }
        }

        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, DerivedFactorAsset> e : byId.entrySet()) {
            String id = e.getKey();
            DerivedFactorAsset asset = e.getValue();
            if (asset.isOverlay() && asset.getFormula() != null) {
                out.add(Finding.error(DOMAIN, "FACTOR_AND_FORMULA",
                        "this file carries both a Factor target and a Formula, which address two different "
                                + "ids - remove Factor to define and name this file's own id, or remove "
                                + "Formula to keep it a naming overlay on '" + asset.getFactor() + "'; until "
                                + "then it defines no value", id));
                continue;
            }
            if (asset.isOverlay()) {
                if (!asset.carriesNaming()) {
                    out.add(Finding.warning(DOMAIN, "NAMES_NOTHING",
                            "this file targets '" + asset.getFactor() + "' but carries no Text and no "
                                    + "ParamNames, so it changes nothing", id));
                }
                continue;
            }
            if (asset.getFormula() == null || asset.getFormula().isEmpty()) {
                if (asset.carriesNaming()) {
                    // A naming-only file addressing its own id: valid, the name simply rides a
                    // factor whose value comes from wherever else provides it.
                    continue;
                }
                out.add(Finding.error(DOMAIN, "EMPTY_FORMULA",
                        "Formula defines nothing (no Base and no usable term) and the file names nothing "
                                + "either, so this id answers nothing and everything gating on it stays "
                                + "shut", id));
                continue;
            }
            validate(id, asset.getFormula(), formulas, registeredElsewhere, out);
        }
        return out;
    }

    /** As {@link #validateAll(Map, Predicate)} with no knowledge of any registry's ids. */
    @Nonnull
    public static List<Finding> validateAll(@Nonnull Map<String, FactorFormula> definitions) {
        return validateAll(definitions, null);
    }

    /**
     * Audit every definition, in id order so a report reads the same twice running.
     *
     * @param definitions       the folded {@code factorId -> formula} set
     * @param registeredElsewhere answers "does some registry already provide this id?"; {@code null}
     *                            means nothing is known, and every non-derived term id is reported
     *                            as unknown
     */
    @Nonnull
    public static List<Finding> validateAll(@Nonnull Map<String, FactorFormula> definitions,
            @Nullable Predicate<String> registeredElsewhere) {
        Map<String, FactorFormula> byId = new TreeMap<>();
        for (Map.Entry<String, FactorFormula> e : definitions.entrySet()) {
            if (e.getKey() != null) {
                byId.put(RegistryLedger.normalize(e.getKey()), e.getValue());
            }
        }

        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, FactorFormula> e : byId.entrySet()) {
            validate(e.getKey(), e.getValue(), byId, registeredElsewhere, out);
        }
        return out;
    }

    /** Audit ONE definition against the whole set (the set is needed for the cycle walk). */
    @Nonnull
    public static List<Finding> validate(@Nonnull String factorId, @Nullable FactorFormula formula,
            @Nonnull Map<String, FactorFormula> definitions,
            @Nullable Predicate<String> registeredElsewhere) {
        List<Finding> out = new ArrayList<>();
        Map<String, FactorFormula> byId = new TreeMap<>();
        for (Map.Entry<String, FactorFormula> e : definitions.entrySet()) {
            if (e.getKey() != null) {
                byId.put(RegistryLedger.normalize(e.getKey()), e.getValue());
            }
        }
        validate(RegistryLedger.normalize(factorId), formula, byId, registeredElsewhere, out);
        return out;
    }

    private static void validate(@Nonnull String id, @Nullable FactorFormula formula,
            @Nonnull Map<String, FactorFormula> byId, @Nullable Predicate<String> registeredElsewhere,
            @Nonnull List<Finding> out) {

        if (formula == null || formula.isEmpty()) {
            out.add(Finding.error(DOMAIN, "EMPTY_FORMULA",
                    "Formula defines nothing (no Base and no usable term), so this id answers nothing and "
                            + "everything gating on it stays shut", id));
            return;
        }

        Double base = formula.getBase();
        if (base != null && !Double.isFinite(base)) {
            out.add(Finding.error(DOMAIN, "NON_FINITE",
                    "Base is not a finite number, so it is ignored and the formula starts from 0", id));
        }

        validateTerms(id, formula, byId, registeredElsewhere, out);
        validateClamp(id, formula.getClamp(), out);

        List<String> cycle = findCycle(id, byId);
        if (cycle != null) {
            out.add(Finding.error(DOMAIN, "CYCLE",
                    "this definition is reached from itself (" + String.join(" -> ", cycle)
                            + "), so it can never resolve and fails closed", id));
        }
    }

    private static void validateTerms(@Nonnull String id, @Nonnull FactorFormula formula,
            @Nonnull Map<String, FactorFormula> byId, @Nullable Predicate<String> registeredElsewhere,
            @Nonnull List<Finding> out) {

        boolean reportedBlank = false;
        Set<String> reportedUnknown = new HashSet<>();
        for (FactorFormula.Term term : formula.termsOrEmpty()) {
            if (term == null || term.isBlank()) {
                if (!reportedBlank) {
                    out.add(Finding.warning(DOMAIN, "BLANK_TERM",
                            "Factors contains an entry with no Factor id, which is skipped", id));
                    reportedBlank = true;
                }
                continue;
            }

            Double weight = term.getWeight();
            if (weight != null && !Double.isFinite(weight)) {
                out.add(Finding.error(DOMAIN, "NON_FINITE",
                        "term '" + term.getFactor() + "' has a Weight that is not a finite number, so it is "
                                + "read as 1.0", id));
            }

            String target = RegistryLedger.normalize(term.getFactor());
            if (target.equals(id)) {
                out.add(Finding.error(DOMAIN, "SELF_REFERENCE",
                        "term '" + term.getFactor() + "' reads the very factor this file defines, so it can "
                                + "never resolve and fails closed", id));
                continue;
            }
            if (byId.containsKey(target)) {
                continue;
            }
            boolean known = registeredElsewhere != null && registeredElsewhere.test(target);
            if (!known && reportedUnknown.add(target)) {
                out.add(Finding.warning(DOMAIN, "UNKNOWN_FACTOR",
                        "term '" + term.getFactor() + "' names a factor no registry provides and no other "
                                + "Factors file defines; it contributes 0 until whichever mod owns it is "
                                + "installed", id));
            }
        }
    }

    private static void validateClamp(@Nonnull String id, @Nullable FactorFormula.Clamp clamp,
            @Nonnull List<Finding> out) {
        if (clamp == null) {
            return;
        }
        Double min = clamp.getMin();
        Double max = clamp.getMax();
        if ((min != null && !Double.isFinite(min)) || (max != null && !Double.isFinite(max))) {
            out.add(Finding.error(DOMAIN, "NON_FINITE",
                    "a Clamp bound is not a finite number, so that bound is ignored", id));
        }
        if (clamp.isInverted()) {
            out.add(Finding.error(DOMAIN, "CLAMP_INVERTED",
                    "Clamp.Min sits above Clamp.Max, so every result collapses onto Max", id));
        }
    }

    /**
     * The shortest path from {@code start} back to itself through OTHER definitions, or {@code null}
     * when there is none. A direct self-reference is deliberately not reported here - it has its own
     * clearer finding - so the walk only ever returns a path of at least two definitions.
     *
     * <p>Breadth-first with a visited set, so the walk is linear in the graph however tangled the
     * definitions are.
     */
    @Nullable
    private static List<String> findCycle(@Nonnull String start, @Nonnull Map<String, FactorFormula> byId) {
        Deque<List<String>> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String next : targets(byId.get(start))) {
            if (!next.equals(start) && byId.containsKey(next) && visited.add(next)) {
                queue.addLast(List.of(start, next));
            }
        }
        while (!queue.isEmpty()) {
            List<String> path = queue.removeFirst();
            String tail = path.get(path.size() - 1);
            for (String next : targets(byId.get(tail))) {
                if (next.equals(start)) {
                    List<String> cycle = new ArrayList<>(path);
                    cycle.add(start);
                    return cycle;
                }
                if (byId.containsKey(next) && visited.add(next)) {
                    List<String> extended = new ArrayList<>(path);
                    extended.add(next);
                    queue.addLast(extended);
                }
            }
        }
        return null;
    }

    /** Every non-blank term id in {@code formula}, normalized. */
    @Nonnull
    private static List<String> targets(@Nullable FactorFormula formula) {
        if (formula == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (FactorFormula.Term term : formula.termsOrEmpty()) {
            if (term != null && !term.isBlank()) {
                out.add(RegistryLedger.normalize(term.getFactor()));
            }
        }
        return out;
    }
}
