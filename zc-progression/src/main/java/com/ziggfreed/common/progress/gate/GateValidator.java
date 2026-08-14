package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
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
 */
public final class GateValidator {

    private GateValidator() {
    }

    /**
     * Audit {@code requires}, including its {@code AllOf} and {@code AnyOf} groups.
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
                for (String kindId : clause.customOrEmpty().keySet()) {
                    if (!gateKinds.isRegistered(kindId)) {
                        out.add(Finding.warning(domain, "UNKNOWN_GATE_KIND",
                                "Requires.Custom names '" + kindId + "', which nothing registered; the "
                                        + "requirement refuses, so this " + noun + " stays locked until "
                                        + "whichever mod owns it is installed", sourceId));
                    }
                }
            }
        }
        return out;
    }
}
