package com.ziggfreed.common.progress.gate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * {@code "any_of"}), never a sentence: turning one into text a player reads is the consumer's job.
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

    @Nullable private final FactorRegistry factors;
    private final Function<Subject, FactorContext> factorContext;
    private volatile CompletionProbe completion;
    private final GateKindRegistry gateKinds;
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

    /** The registered requirement vocabulary this evaluator reads. */
    @Nonnull
    public GateKindRegistry gateKinds() {
        return gateKinds;
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
     * passed. Evaluation order is leaves, then {@code AllOf}, then {@code AnyOf}, so the token
     * names the most specific thing that shut the gate.
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
            if (factors == null) {
                return REASON_FACTOR + firstFactorId(conditions);
            }
            String failed = FactorConditions.firstFailure(conditions, factors, contextFor(subject));
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

        for (Map.Entry<String, Map<String, String>> entry : clause.customOrEmpty().entrySet()) {
            String kindId = entry.getKey();
            GateKind kind = gateKinds.kind(kindId);
            if (kind == null) {
                return REASON_CUSTOM + kindId;
            }
            Map<String, String> params = entry.getValue() == null ? Map.of() : entry.getValue();
            try {
                if (!kind.passes(subject, params, support)) {
                    return REASON_CUSTOM + kindId;
                }
            } catch (Exception e) {
                gateKinds.recordFailure(kindId, e.getMessage());
                warn.accept("requirement kind '" + kindId + "' threw, so the gate stays shut: " + e.getMessage());
                return REASON_CUSTOM + kindId;
            }
        }
        return null;
    }

    /** The shared factor answer, also handed to every desugaring requirement kind. */
    public boolean factorsPass(@Nonnull Subject subject, @Nullable List<FactorCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        return factors != null && FactorConditions.pass(conditions, factors, contextFor(subject));
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

        @Nullable private FactorRegistry factors;
        private Function<Subject, FactorContext> factorContext = subject -> FactorContext.builder().build();
        private CompletionProbe completion = CompletionProbe.NONE;
        private GateKindRegistry gateKinds = new GateKindRegistry();
        private Consumer<String> warn = msg -> SafeLog.warn("[quest-gate] " + msg);

        private Builder() {
        }

        /**
         * The vocabulary factor bounds are answered against. Unset means every bound refuses -
         * including a {@code Permission} leaf, which is one of those bounds.
         */
        @Nonnull
        public Builder factors(@Nullable FactorRegistry factors) {
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
