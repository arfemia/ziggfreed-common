package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorConditions;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Answers an authored {@code Requires} block for one subject: factor bounds, a permission, a
 * finished-content list, and whatever requirement kinds a consumer registered.
 *
 * <p>ONE evaluator behind every gated thing in this module, so a requirement written on one kind of
 * content means exactly what it means on the next.
 *
 * <p>Everything is wired by the consumer, and every unwired seam REFUSES rather than passing. That
 * is deliberate: a gate that cannot be evaluated must never open, or the first server missing a
 * dependency hands out content the author gated. Content that authors no requirements needs no
 * wiring at all and is open to everyone.
 *
 * <p>Build one at setup and hand it to whichever engine-side gates adapter consults it:
 * <pre>{@code
 * GateEvaluator gates = GateEvaluator.builder()
 *         .factors(myFactorRegistry)
 *         .factorContext(subject -> FactorContext.builder()
 *                 .store(storeOf(subject)).subject(refOf(subject)).build())
 *         .gateKinds(myGateKinds)
 *         .build();
 * }</pre>
 *
 * <p><b>A {@code Permission} leaf is a factor bound spelled short</b>, resolved as
 * {@code {"Factor": "hytale:permission", "Param": <node>, "Min": 1}} through the very same registry
 * lookup every other factor condition takes. So there is nothing to wire for it beyond the factor
 * vocabulary itself, and one permission question has one answer however content spells it. Register
 * the portable {@code hytale:} standard library into the registry handed to {@link Builder#factors}
 * and give {@link Builder#factorContext} a context carrying the player, and the leaf works.
 *
 * <p>A refusal is an opaque REASON TOKEN naming what shut the gate ({@code "factor:yourmod:rank"},
 * {@code "permission"}, {@code "quest:intro_1"}, {@code "gate:yourmod:reputation"},
 * {@code "any_of"}, {@code "not"}), never a sentence: turning one into text a player reads is the
 * consumer's job.
 *
 * <p>There are TWO ways to ask. {@link #passes} and {@link #firstFailure} short-circuit at the first
 * unmet requirement, which is what a per-row boolean check in a loop wants. {@link #allFailures}
 * walks the whole block and reports every unmet requirement, which is what a detail panel listing
 * what still stands between a player and the content wants. A {@code factor:} token from the
 * collect-all walk carries the condition's {@code Param} after an {@code @} when one is authored
 * ({@code "factor:yourmod:stat@mining"}), so two bounds on the SAME factor id stay two
 * distinguishable answers.
 */
public final class GateEvaluator {

    /** Prefix of the reason token naming the factor whose bounds were not met. */
    public static final String REASON_FACTOR = "factor:";

    /** The reason token for a permission the player does not hold. */
    public static final String REASON_PERMISSION = "permission";

    /** Prefix of the reason token naming the prerequisite quest that is not finished. */
    public static final String REASON_QUEST = "quest:";

    /** Prefix of the reason token naming the registered requirement kind that refused. */
    public static final String REASON_CUSTOM = "gate:";

    /** The reason token for an {@code AnyOf} block where no group passed. */
    public static final String REASON_ANY_OF = "any_of";

    /** The reason token for a {@code Not} group that passed, which is what shuts the gate. */
    public static final String REASON_NOT = "not";

    /**
     * The factor a {@code Permission} leaf is evaluated as: {@code Param} is the node, and the
     * reading is 1 when the player holds it. It belongs to the portable {@code hytale:} vocabulary
     * because permissions are the engine's own paradigm rather than any one mod's invention.
     *
     * <p>It is named here as a STRING rather than by referencing the class that registers it,
     * because that class lives in a module this one sits below. Nothing is lost by that: the id is
     * looked up in the registry at evaluation time exactly as an authored one is, which is what
     * makes the leaf and the long spelling the same requirement rather than two that happen to
     * agree.
     */
    public static final String PERMISSION_FACTOR = "hytale:permission";

    /** The bound a {@code Permission} leaf carries: the node is held, or it is not. */
    private static final Double MUST_HOLD = Double.valueOf(1.0);

    /** Answers "has this player finished this quest?". */
    @FunctionalInterface
    public interface CompletionProbe {

        /** Refuses everything: an unwired probe cannot claim a quest was finished. */
        CompletionProbe NONE = (subject, questId) -> false;

        boolean hasCompleted(@Nonnull Subject subject, @Nonnull String questId);
    }

    /**
     * The factor condition a {@code Permission} leaf MEANS:
     * {@code {"Factor": "hytale:permission", "Param": node, "Min": 1}}. Write the leaf or write the
     * condition and a server sees one answer, because the leaf is evaluated as exactly this.
     *
     * <p>The node is passed through as authored; the factor's own provider trims it and reads a
     * blank one as nothing it can answer.
     */
    @Nonnull
    public static FactorCondition permissionCondition(@Nonnull String node) {
        return FactorCondition.of(PERMISSION_FACTOR, node, MUST_HOLD, null);
    }

    private final Supplier<FactorRegistry> factors;
    private final Function<Subject, FactorContext> factorContext;
    private volatile CompletionProbe completion;
    private final Supplier<GateKindRegistry> gateKinds;
    private final Consumer<String> warn;
    private final GateKind.GateSupport support;

    private GateEvaluator(@Nonnull Builder b) {
        this.factors = b.factors;
        this.factorContext = b.factorContext;
        this.completion = b.completion;
        this.gateKinds = b.gateKinds;
        this.warn = b.warn;
        this.support = (subject, conditions) -> factorsPass(subject, conditions);
    }

    /**
     * This evaluator's OWN empty vocabulary, for a live supplier that answers null. It is per
     * evaluator rather than shared: the registry is mutable, and one instance behind every
     * default-built evaluator in the process would be a table they all wrote into.
     */
    private final GateKindRegistry emptyKinds = new GateKindRegistry();

    /** The registered requirement vocabulary this evaluator reads. */
    @Nonnull
    public GateKindRegistry gateKinds() {
        GateKindRegistry kinds = gateKinds.get();
        return kinds == null ? emptyKinds : kinds;
    }

    /**
     * Replace who answers a {@code Quests} prerequisite. It is settable after the build because the
     * usual answer comes from an engine that cannot exist until its gates already do, so the engine
     * wires itself in afterwards. Set it once at setup, not per player.
     */
    public void completedQuests(@Nonnull CompletionProbe completion) {
        this.completion = completion;
    }

    /** Does {@code subject} satisfy {@code spec}? A null or empty block passes. */
    public boolean passes(@Nonnull Subject subject, @Nullable GateSpec spec) {
        return firstFailure(subject, spec) == null;
    }

    /**
     * The reason token for the FIRST requirement that was not met, or null when the whole block
     * passed. Evaluation order is leaves, then {@code AllOf}, then {@code Not}, then {@code AnyOf},
     * so the token names the most specific thing that shut the gate.
     */
    @Nullable
    public String firstFailure(@Nonnull Subject subject, @Nullable GateSpec spec) {
        if (spec == null) {
            return null;
        }
        String failure = clauseFailure(subject, spec);
        if (failure != null) {
            return failure;
        }
        for (GateClause clause : spec.allOfOrEmpty()) {
            failure = clauseFailure(subject, clause);
            if (failure != null) {
                return failure;
            }
        }
        // A Not group shuts the gate by PASSING. An empty group passes for everyone, so a Not on
        // one shuts the content for everyone - deliberate, and the validator is what warns about it.
        for (GateClause clause : spec.notOrEmpty()) {
            if (clause != null && clauseFailure(subject, clause) == null) {
                return REASON_NOT;
            }
        }
        GateClause[] anyOf = spec.anyOfOrEmpty();
        if (anyOf.length == 0) {
            return null;
        }
        for (GateClause clause : anyOf) {
            if (clauseFailure(subject, clause) == null) {
                return null;
            }
        }
        return REASON_ANY_OF;
    }

    /** Every requirement in ONE group, in leaf order. */
    @Nullable
    private String clauseFailure(@Nonnull Subject subject, @Nullable GateClause clause) {
        if (clause == null || clause.isEmpty()) {
            return null;
        }

        FactorCondition[] conditions = clause.factorsOrEmpty();
        if (conditions.length > 0) {
            FactorRegistry vocabulary = factors.get();
            if (vocabulary == null) {
                return REASON_FACTOR + firstFactorId(conditions);
            }
            String failed = FactorConditions.firstFailure(conditions, vocabulary, contextFor(subject));
            if (failed != null) {
                return REASON_FACTOR + failed;
            }
        }

        // The leaf IS the factor: same registry, same context, same fail-closed rules. The refusal
        // still names the leaf, because that is what the author wrote and what they can go and fix.
        String permission = clause.getPermission();
        if (permission != null && !factorsPass(subject, List.of(permissionCondition(permission)))) {
            return REASON_PERMISSION;
        }

        for (String questId : clause.questsOrEmpty()) {
            if (questId != null && !questId.isBlank() && !completion.hasCompleted(subject, questId.trim())) {
                return REASON_QUEST + questId.trim();
            }
        }

        GateKindRegistry kinds = gateKinds();
        for (Map.Entry<String, Map<String, String>> entry : clause.customOrEmpty().entrySet()) {
            String kindId = entry.getKey();
            GateKind kind = kinds.kind(kindId);
            if (kind == null) {
                return REASON_CUSTOM + kindId;
            }
            Map<String, String> params = entry.getValue() == null ? Map.of() : entry.getValue();
            try {
                if (!kind.passes(subject, params, support)) {
                    return REASON_CUSTOM + kindId;
                }
            } catch (Exception e) {
                kinds.recordFailure(kindId, e.getMessage());
                warn.accept("requirement kind '" + kindId + "' threw, so the gate stays shut: " + e.getMessage());
                return REASON_CUSTOM + kindId;
            }
        }
        return null;
    }

    /**
     * EVERY unmet requirement in {@code spec} as reason tokens, in the same leaf-then-{@code AllOf}
     * -then-{@code Not}-then-{@code AnyOf} order {@link #firstFailure} walks, empty when the block
     * passed. This is the list a locked-content panel shows; {@link #firstFailure} stays the cheap
     * answer for a boolean check. The tokens are DERIVED, one per record, from
     * {@link #allRefusals} - same walk, same order, today's exact spellings.
     *
     * <p>A {@code factor:} token here carries the condition's {@code Param} after an {@code @} when
     * one is authored, because a block may bound one factor id twice under two different
     * {@code Param}s, and a consumer looking the condition back up by bare id would render one of
     * them twice and drop the other. A consumer splits at the FIRST {@code @}, so a {@code Param}
     * may contain any number of its own and still round-trip; what the spelling relies on is that a
     * namespaced FACTOR ID never contains one, and nothing enforces that.
     *
     * <p>An {@code AnyOf} block contributes exactly ONE token however many routes it offers: the
     * routes are alternatives, so listing each one as its own unmet requirement would read as
     * several separate things to go and do rather than one choice.
     */
    @Nonnull
    public List<String> allFailures(@Nonnull Subject subject, @Nullable GateSpec spec) {
        List<GateRefusal> refusals = allRefusals(subject, spec);
        if (refusals.isEmpty()) {
            return List.of();
        }
        List<String> failures = new ArrayList<>(refusals.size());
        for (GateRefusal refusal : refusals) {
            failures.add(refusal.token());
        }
        return failures;
    }

    /**
     * EVERY unmet requirement in {@code spec} as structured {@link GateRefusal} records, in the
     * same order as {@link #allFailures} and empty exactly when it is - this is the walk, and the
     * token list is its projection. A surface with room for the rich line reads these: a factor
     * refusal carries the condition's authored {@code Param} and BOUNDS, which the token never
     * does, so "what does this ask for" is answerable without re-evaluating anything.
     */
    @Nonnull
    public List<GateRefusal> allRefusals(@Nonnull Subject subject, @Nullable GateSpec spec) {
        if (spec == null) {
            return List.of();
        }
        List<GateRefusal> refusals = new ArrayList<>();
        clauseRefusals(subject, spec, refusals);
        for (GateClause clause : spec.allOfOrEmpty()) {
            clauseRefusals(subject, clause, refusals);
        }
        // A Not group shuts the gate by PASSING, exactly as in the short-circuit walk.
        for (GateClause clause : spec.notOrEmpty()) {
            if (clause != null && clauseFailure(subject, clause) == null) {
                refusals.add(GateRefusal.NOT);
            }
        }
        GateClause[] anyOf = spec.anyOfOrEmpty();
        if (anyOf.length == 0) {
            return refusals;
        }
        for (GateClause clause : anyOf) {
            if (clauseFailure(subject, clause) == null) {
                return refusals;
            }
        }
        refusals.add(GateRefusal.ANY_OF);
        return refusals;
    }

    /** Every unmet requirement in ONE group, in leaf order, appended to {@code out}. */
    private void clauseRefusals(@Nonnull Subject subject, @Nullable GateClause clause,
            @Nonnull List<GateRefusal> out) {
        if (clause == null || clause.isEmpty()) {
            return;
        }

        FactorCondition[] conditions = clause.factorsOrEmpty();
        if (conditions.length > 0) {
            FactorRegistry vocabulary = factors.get();
            if (vocabulary == null) {
                // No vocabulary wired: every bound refuses, exactly as the short-circuit walk fails
                // closed, and each one is named so the answer stays the whole list.
                int before = out.size();
                for (FactorCondition condition : conditions) {
                    if (condition != null && !condition.isBlank()) {
                        out.add(refusalOf(condition));
                    }
                }
                if (out.size() == before) {
                    // Nothing here was nameable (every entry blank), and the short-circuit walk
                    // still refuses that clause. The two must agree about whether a block is shut,
                    // so name the same bare id it does rather than reading as open.
                    out.add(GateRefusal.factor(firstFactorId(conditions), null, null, null));
                }
                // Stop where the short-circuit walk stops. Reading on into the permission, quest and
                // custom leaves would report requirements that were never evaluated at all, and
                // would run every registered kind on a server whose vocabulary is not even wired.
                return;
            } else {
                for (FactorCondition condition
                        : FactorConditions.allFailures(conditions, vocabulary, contextFor(subject))) {
                    out.add(refusalOf(condition));
                }
            }
        }

        String permission = clause.getPermission();
        if (permission != null && !factorsPass(subject, List.of(permissionCondition(permission)))) {
            out.add(GateRefusal.PERMISSION);
        }

        for (String questId : clause.questsOrEmpty()) {
            if (questId != null && !questId.isBlank() && !completion.hasCompleted(subject, questId.trim())) {
                out.add(GateRefusal.quest(questId.trim()));
            }
        }

        GateKindRegistry kinds = gateKinds();
        for (Map.Entry<String, Map<String, String>> entry : clause.customOrEmpty().entrySet()) {
            String kindId = entry.getKey();
            GateKind kind = kinds.kind(kindId);
            if (kind == null) {
                out.add(GateRefusal.custom(kindId));
                continue;
            }
            Map<String, String> params = entry.getValue() == null ? Map.of() : entry.getValue();
            try {
                if (!kind.passes(subject, params, support)) {
                    out.add(GateRefusal.custom(kindId));
                }
            } catch (Exception e) {
                kinds.recordFailure(kindId, e.getMessage());
                warn.accept("requirement kind '" + kindId + "' threw, so the gate stays shut: " + e.getMessage());
                out.add(GateRefusal.custom(kindId));
            }
        }
    }

    /** One unmet bound as its record, carrying the condition's authored param and bounds. */
    @Nonnull
    private static GateRefusal refusalOf(@Nonnull FactorCondition condition) {
        return GateRefusal.factor(condition.getFactor(), condition.getParam(),
                condition.getMin(), condition.getMax());
    }

    /** The shared factor answer, also handed to every desugaring requirement kind. */
    public boolean factorsPass(@Nonnull Subject subject, @Nullable List<FactorCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        FactorRegistry vocabulary = factors.get();
        return vocabulary != null && FactorConditions.pass(conditions, vocabulary, contextFor(subject));
    }

    @Nonnull
    private FactorContext contextFor(@Nonnull Subject subject) {
        FactorContext ctx = factorContext.apply(subject);
        return ctx == null ? FactorContext.builder().build() : ctx;
    }

    @Nonnull
    private static String firstFactorId(@Nonnull FactorCondition[] conditions) {
        return Arrays.stream(conditions)
                .filter(c -> c != null && !c.isBlank())
                .map(FactorCondition::getFactor)
                .findFirst()
                .orElse("");
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Assembles a {@link GateEvaluator}; every seam has a fail-closed default. */
    public static final class Builder {

        private Supplier<FactorRegistry> factors = () -> null;
        private Function<Subject, FactorContext> factorContext = subject -> FactorContext.builder().build();
        private CompletionProbe completion = CompletionProbe.NONE;
        // Its OWN empty vocabulary, so every kind refuses until one is registered. One shared
        // instance would be a mutable table every default-built evaluator in the process wrote its
        // failures into.
        private final GateKindRegistry ownKinds = new GateKindRegistry();
        private Supplier<GateKindRegistry> gateKinds = () -> ownKinds;
        private Consumer<String> warn = msg -> SafeLog.warn("[quest-gate] " + msg);

        private Builder() {
        }

        /**
         * The vocabulary factor bounds are answered against. Unset means every bound refuses -
         * including a {@code Permission} leaf, which is one of those bounds.
         */
        @Nonnull
        public Builder factors(@Nullable FactorRegistry factors) {
            this.factors = () -> factors;
            return this;
        }

        /**
         * The same vocabulary, read LIVE per evaluation rather than captured at build.
         *
         * <p>An evaluator is usually built at setup, before the consumer that OWNS the vocabulary
         * has registered it, and a shared server has exactly one of each - so the way to have one
         * evaluator over one vocabulary is to look the vocabulary up when the question is asked
         * instead of holding whatever existed first.
         */
        @Nonnull
        public Builder factorsLive(@Nonnull Supplier<FactorRegistry> factors) {
            this.factors = factors;
            return this;
        }

        /**
         * How a subject becomes the context a factor provider reads (the entity, the store, the
         * world). Unset builds an empty context, which is enough for a provider that only needs the
         * authored {@code Param} - but not for one that reads the player, so a surface with
         * {@code Permission} leaves to answer supplies the store and the subject here.
         */
        @Nonnull
        public Builder factorContext(@Nonnull Function<Subject, FactorContext> factorContext) {
            this.factorContext = factorContext;
            return this;
        }

        /**
         * Who answers a {@code Quests} prerequisite leaf. The engine knows this by itself, so the
         * usual wiring is a one-liner over the engine's own status; unset refuses every one.
         */
        @Nonnull
        public Builder completedQuests(@Nonnull CompletionProbe completion) {
            this.completion = completion;
            return this;
        }

        /** The registered {@code Custom} requirement vocabulary. Unset means an empty one. */
        @Nonnull
        public Builder gateKinds(@Nonnull GateKindRegistry gateKinds) {
            this.gateKinds = () -> gateKinds;
            return this;
        }

        /** The same vocabulary, read LIVE per evaluation; see {@link #factorsLive}. */
        @Nonnull
        public Builder gateKindsLive(@Nonnull Supplier<GateKindRegistry> gateKinds) {
            this.gateKinds = gateKinds;
            return this;
        }

        /** Where a misbehaving requirement kind is reported. */
        @Nonnull
        public Builder warn(@Nonnull Consumer<String> warn) {
            this.warn = warn;
            return this;
        }

        @Nonnull
        public GateEvaluator build() {
            return new GateEvaluator(this);
        }
    }
}
