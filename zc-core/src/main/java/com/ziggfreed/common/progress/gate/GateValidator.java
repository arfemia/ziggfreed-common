package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorTraits;
import com.ziggfreed.common.validation.Finding;

/**
 * Audits ONE {@code Requires} block for the mistakes that produce NO error at runtime: a half-typed
 * requirement that gates nothing, a prerequisite nobody can ever have finished, a requirement kind
 * nothing registered, a factor no installed mod can answer. Every one of them ships as content that
 * quietly never appears or never unlocks.
 *
 * <p><b>ONE gate audit for every domain that carries a {@code Requires} block.</b> The block is
 * shared - a quest, an achievement, a storefront, a contract board all decode the same
 * {@link GateSpec} - so its findings are shared too, with the same codes and the same wording. A
 * domain that grew its own copy would drift, and a server owner reading one report would have to
 * learn which validator phrased a lock which way.
 *
 * <p>Everything a caller cannot answer is simply SKIPPED rather than reported as unknown: pass null
 * for the prerequisite probe on a surface with no catalogue to check against, and that check does
 * not run. Findings are stamped with the CALLER's domain, so they fold into that domain's report
 * beside its own.
 *
 * <p><b>Every unknown id is a WARNING, never an error</b> - the standing library rule. Whichever mod
 * owns a factor, a requirement kind or a prerequisite registers it at its own setup, which may run
 * after this audit and may be a mod the author expects some servers not to install.
 *
 * <p>What a factor's Factors file declares is checked too ({@code ParamDataSet}, {@code AbsentReads}; see
 * {@code checkTraits}), so is a {@code Max}-only bound on a membership factor, and a registered
 * {@code Custom} kind's parameter names where the kind declares them ({@link GateKind#params()}). A domain
 * whose content changes what a gate could read (a gear set's tiers raising a stat) also asks
 * {@link #selfReads}, which names a block reading what its own content changes.
 */
public final class GateValidator {

    private GateValidator() {
    }

    /**
     * Audit {@code requires}, including its {@code AllOf}, {@code AnyOf} and {@code Not} groups.
     *
     * @param requires      the block, or null when the content authored none (nothing is reported)
     * @param domain        which content family the findings belong to
     * @param sourceId      the content id a finding names
     * @param noun          what the gated thing is CALLED in a message written for the author
     *                      ({@code "quest"}, {@code "offer"}, {@code "contract"})
     * @param gateKinds     the registered {@code Custom} vocabulary, or null to skip that check
     * @param knownFactors  answers "does anything provide this factor id?", or null to skip
     * @param knownContent  answers "is this a piece of content in the same pool?", for
     *                      {@code Requires.Quests}, or null to skip
     */
    @Nonnull
    public static List<Finding> validate(@Nullable GateSpec requires, @Nonnull String domain,
            @Nonnull String sourceId, @Nonnull String noun, @Nullable GateKindRegistry gateKinds,
            @Nullable Predicate<String> knownFactors, @Nullable Predicate<String> knownContent) {

        List<Finding> out = new ArrayList<>();
        if (requires == null) {
            return out;
        }

        List<GateClause> clauses = new ArrayList<>();
        clauses.add(requires);
        for (GateClause clause : requires.allOfOrEmpty()) {
            clauses.add(clause);
        }
        for (GateClause clause : requires.anyOfOrEmpty()) {
            clauses.add(clause);
        }
        for (GateClause clause : requires.notOrEmpty()) {
            clauses.add(clause);
            // A group asking for nothing passes for everyone, so a Not on it can never be satisfied
            // and the content is shut to every player forever. It reads as harmless in the file,
            // which is exactly why it has to be reported.
            if (clause != null && clause.isEmpty()) {
                out.add(Finding.warning(domain, "BLANK_REQUIREMENT",
                        "a Requires.Not group asks for nothing, and a group that asks for nothing always "
                                + "passes; negating it shuts this " + noun + " for everybody", sourceId));
            }
        }

        for (GateClause clause : clauses) {
            if (clause == null) {
                continue;
            }
            for (FactorCondition condition : clause.factorsOrEmpty()) {
                if (condition == null || condition.isBlank()) {
                    out.add(Finding.warning(domain, "BLANK_REQUIREMENT",
                            "a Factors entry names no factor, so it is skipped and gates nothing", sourceId));
                    continue;
                }
                String factorId = condition.getFactor();
                if (knownFactors != null && factorId != null && !knownFactors.test(factorId)) {
                    out.add(Finding.warning(domain, "UNKNOWN_FACTOR",
                            "Requires names the factor '" + factorId + "', which nothing on this server can "
                                    + "answer; the requirement fails closed, so this " + noun + " stays locked "
                                    + "until whichever mod owns it is installed", sourceId));
                }
                checkTraits(condition, domain, sourceId, noun, out);
            }
            if (knownContent != null) {
                for (String prerequisite : clause.questsOrEmpty()) {
                    if (prerequisite != null && !prerequisite.isBlank() && !knownContent.test(prerequisite)) {
                        out.add(Finding.warning(domain, "UNKNOWN_PREREQUISITE",
                                "Requires.Quests names '" + prerequisite + "', which is not a quest in this "
                                        + "pool; nobody can ever have finished it, so this " + noun
                                        + " stays locked", sourceId));
                    }
                }
            }
            if (gateKinds != null) {
                for (Map.Entry<String, Map<String, String>> entry : clause.customOrEmpty().entrySet()) {
                    String kindId = entry.getKey();
                    if (!gateKinds.isRegistered(kindId)) {
                        out.add(Finding.warning(domain, "UNKNOWN_GATE_KIND",
                                "Requires.Custom names '" + kindId + "', which nothing registered; the "
                                        + "requirement refuses, so this " + noun + " stays locked until "
                                        + "whichever mod owns it is installed", sourceId));
                        continue;
                    }
                    checkParams(kindId, gateKinds.kind(kindId), entry.getValue(), domain, sourceId, noun, out);
                }
            }
        }
        return out;
    }

    /**
     * What a condition's factor declares in its Factors file ({@link FactorTraits}), checked against the
     * condition: a {@code Param} outside its {@code ParamDataSet} (a pattern with {@code *} is never checked),
     * a condition with no {@code Max} that the factor's definite absent reading ({@code AbsentReads}) meets,
     * which gates nothing, and a {@code Max}-only bound on a membership factor ({@link RefusalLines#isMemberFactor}),
     * whose lock line reads as asking for the very thing the bound forbids.
     */
    private static void checkTraits(@Nonnull FactorCondition condition, @Nonnull String domain,
            @Nonnull String sourceId, @Nonnull String noun, @Nonnull List<Finding> out) {
        String factorId = condition.getFactor();
        FactorTraits traits = FactorTraits.of(factorId);
        String param = condition.getParam() == null ? null : condition.getParam().trim();
        if (traits.paramDataSet() != null && param != null && !param.isEmpty() && param.indexOf('*') < 0) {
            Collection<String> values = EditorDataSets.values(traits.paramDataSet());
            if (values != null && !values.isEmpty() && !containsIgnoreCase(values, param)) {
                out.add(Finding.warning(domain, "UNKNOWN_FACTOR_PARAM",
                        "Requires asks '" + factorId + "' about '" + param + "', which is not one of its values on "
                                + "this server; the reading answers nothing, so this " + noun + " stays locked",
                        sourceId));
            }
        }
        Double absent = traits.absentReads();
        if (absent != null && condition.getMax() == null && condition.accepts(absent)) {
            out.add(Finding.warning(domain, "ABSENT_READING_PASSES",
                    "Requires bounds '" + factorId + "' so that its reading when absent (" + absent + ") passes, so "
                            + "the condition gates nothing; write a Min above " + absent, sourceId));
        }
        if (condition.getMax() != null && condition.getMin() == null && RefusalLines.isMemberFactor(factorId)) {
            out.add(Finding.warning(domain, "MAX_ONLY_MEMBERSHIP",
                    "Requires bounds '" + factorId + "' with a Max alone, meaning 'not this'; a player it refuses "
                            + "is told to go and get it. Write the negative as a Not group holding the plain "
                            + "condition (Min 1)", sourceId));
        }
    }

    /** The code a gate reading what the gated content itself changes is reported under ({@link #selfReads}). */
    public static final String GATE_READS_OWN_STAT = "GATE_READS_OWN_STAT";

    /**
     * A WARNING for each {@code Param} the block's conditions on any of {@code readers} name (in any group) that
     * {@code own} also lists, matched without regard to case: content gated on a reading it changes itself. A
     * gear set gated on {@code hytale:stat Health} whose own tiers raise Health holds itself open once a tier
     * applies (or shuts itself as a tier falls away), so whether it applies depends on whether it already did.
     *
     * @param readers the factor ids whose Param names the changed thing ({@code hytale:stat},
     *                {@code hytale:stat_percent})
     * @param own     what the content itself changes (the stat ids its tiers modify)
     */
    @Nonnull
    public static List<Finding> selfReads(@Nullable GateSpec requires, @Nonnull Collection<String> readers,
            @Nonnull Collection<String> own, @Nonnull String domain, @Nonnull String sourceId,
            @Nonnull String noun) {
        List<Finding> out = new ArrayList<>();
        if (requires == null || own.isEmpty()) {
            return out;
        }
        Set<String> reported = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (GateClause clause : clausesOf(requires)) {
            if (clause == null) {
                continue;
            }
            for (FactorCondition condition : clause.factorsOrEmpty()) {
                String factorId = condition == null ? null : condition.getFactor();
                String param = condition == null || condition.getParam() == null ? null : condition.getParam().trim();
                if (factorId == null || param == null || param.isEmpty()
                        || !containsIgnoreCase(readers, factorId.trim()) || !containsIgnoreCase(own, param)
                        || !reported.add(param)) {
                    continue;
                }
                out.add(Finding.warning(domain, GATE_READS_OWN_STAT, "Requires reads '" + param + "' through '"
                        + factorId.trim() + "', which this " + noun + " changes itself; once it applies, its own "
                        + "change can hold the gate open or shut it, so gate on something it does not change",
                        sourceId));
            }
        }
        return out;
    }

    /** The block and each of its groups, the walk every check here makes. */
    @Nonnull
    private static List<GateClause> clausesOf(@Nonnull GateSpec requires) {
        List<GateClause> clauses = new ArrayList<>();
        clauses.add(requires);
        clauses.addAll(Arrays.asList(requires.allOfOrEmpty()));
        clauses.addAll(Arrays.asList(requires.anyOfOrEmpty()));
        clauses.addAll(Arrays.asList(requires.notOrEmpty()));
        return clauses;
    }

    /** A registered kind's parameters against the names it declares reading ({@link GateKind#params()}). */
    private static void checkParams(@Nonnull String kindId, @Nullable GateKind kind,
            @Nullable Map<String, String> authored, @Nonnull String domain, @Nonnull String sourceId,
            @Nonnull String noun, @Nonnull List<Finding> out) {
        Set<String> declared = kind == null ? null : kind.params();
        if (declared == null || authored == null) {
            return;
        }
        for (String name : authored.keySet()) {
            if (name != null && !containsIgnoreCase(declared, name)) {
                out.add(Finding.warning(domain, "UNKNOWN_GATE_PARAM",
                        "Requires.Custom." + kindId + " writes '" + name + "', which that kind does not read (it "
                                + "reads " + String.join(", ", new TreeSet<>(declared)) + "); this " + noun
                                + " is gated as if it were not written", sourceId));
            }
        }
    }

    private static boolean containsIgnoreCase(@Nonnull Collection<String> values, @Nonnull String wanted) {
        for (String value : values) {
            if (value != null && value.trim().equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }
}
