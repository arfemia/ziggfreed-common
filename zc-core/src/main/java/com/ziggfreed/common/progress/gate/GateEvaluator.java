package com.ziggfreed.common.progress.gate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Answers an authored {@code Requires} block for one subject: factor bounds, a permission, a
 * finished-content list, and whatever requirement kinds a consumer registered.
 *
 * <p>ONE evaluator behind every gated thing on the server, so a requirement written on one kind of
 * content means exactly what it means on the next.
 *
 * <p>Everything is wired at setup, and every unwired seam REFUSES rather than passing. That
 * is deliberate: a gate that cannot be evaluated must never open, or the first server missing a
 * dependency hands out content the author gated. Content that authors no requirements needs no
 * wiring at all and is open to everyone.
 *
 * <p>A server has exactly one, {@link GateRuntime#evaluator()}, built over live seams: the factor vocabulary
 * (by default every contributed and file-defined factor), the subject's factor context (by default its live
 * player) and the registered requirement kinds. zc-progression's {@code ProgressionGates} fills those seams with
 * the shared progression runtime's parts and the completion probe, and answers the same instance, so a mod
 * feeds it by registering into the runtime at setup, never by building a second one:
 * <pre>{@code
 * ProgressionRuntime.registrar("yourmod")
 *         .factors(myFactorRegistry)
 *         .questFactorContext(subject -> FactorContext.builder()
 *                 .store(storeOf(subject)).subject(refOf(subject)).build());
 * ProgressionRuntime.gateKinds().register("yourmod:reputation", "yourmod", myGateKind);
 * GateEvaluator gates = GateRuntime.evaluator();
 * }</pre>
 * {@link #builder()} is only for a unit test or a genuinely private evaluator.
 *
 * <p><b>A {@code Permission} leaf is a factor bound spelled short</b>, resolved as
 * {@code {"Factor": "hytale:permission", "Param": <node>, "Min": 1}} through the very same registry
 * lookup every other factor condition takes. So there is nothing to wire for it beyond the factor
 * vocabulary itself, and one permission question has one answer however content spells it.
 *
 * <p>A refusal is an opaque REASON TOKEN naming what shut the gate ({@code "factor:yourmod:rank"},
 * {@code "permission"}, {@code "quest:intro_1"}, {@code "gate:yourmod:reputation"},
 * {@code "any_of"}, {@code "not"}), never a sentence: turning one into text a player reads is
 * {@link RefusalLines}' job.
 *
 * <p>There are THREE ways to ask. {@link #passes} and {@link #firstFailure} short-circuit at the first
 * unmet requirement, which is what a per-row boolean check in a loop wants. {@link #allFailures} and
 * {@link #allRefusals} walk the whole block and report every unmet requirement, which is what a detail panel
 * listing what still stands between a player and the content wants. {@link #check} asks with a
 * {@link GateQuery} (a moment's held item, item, target and block, an optional local vocabulary) through the
 * block's {@link CompiledGate}: its factor providers looked up once and kept while the vocabulary generation
 * stands, and its verdict kept between asks where every reading in it is declared {@code Server} or
 * {@code Player} ({@code Volatility} in its Factors file); {@link #refusals} is that question's full list. A
 * {@code factor:} token from a collect-all walk carries the condition's {@code Param} after an {@code @} when
 * one is authored ({@code "factor:yourmod:stat@mining"}), so two bounds on the SAME factor id stay two
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
     * The reason token a nested all-of group's record reads back as. The walks never report it at
     * top level - it exists only inside a composite {@link GateRefusal}'s children, where one
     * {@code AnyOf} route asks for several things at once.
     */
    public static final String REASON_ALL_OF = "all_of";

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

    /** The id a subject with nobody behind it carries; never kept against. */
    private static final UUID NOBODY = new UUID(0L, 0L);

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

    private GateEvaluator(@Nonnull Builder b) {
        this.factors = b.factors;
        this.factorContext = b.factorContext;
        this.completion = b.completion;
        this.gateKinds = b.gateKinds;
        this.warn = b.warn;
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

    /** The factor vocabulary this evaluator reads right now, or null when none is wired (every bound refuses). */
    @Nullable
    FactorRegistry vocabulary() {
        return factors.get();
    }

    // ==================== asking about a subject ====================

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
        return spec == null ? null : firstFailure(spec, about(subject));
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
        return tokensOf(allRefusals(subject, spec));
    }

    /**
     * EVERY unmet requirement in {@code spec} as structured {@link GateRefusal} records, in the
     * same order as {@link #allFailures} and empty exactly when it is - this is the walk, and the
     * token list is its projection. A surface with room for the rich line reads these: a factor
     * refusal carries the condition's authored {@code Param}, its BOUNDS, and the READING the walk
     * itself resolved (what the player currently has), none of which the token ever does, so both
     * "what does this ask for" and "where do they stand" are answerable without re-evaluating
     * anything.
     *
     * <p>A COMPOSITE refusal carries what its group asks for as children, described from the
     * block's own authored requirements rather than from the evaluation - the token says only that
     * no route passed, and describing what each route ASKS FOR is the useful sentence anyway: a
     * player wants to know their routes in, not which one the evaluator happened to try first. An
     * {@code AnyOf} record carries one child per route (a multi-ask route folds into one nested
     * {@code ALL_OF} child); a {@code Not} record carries the asks the subject currently
     * satisfies. Children carry authored bounds and no resolved reading.
     */
    @Nonnull
    public List<GateRefusal> allRefusals(@Nonnull Subject subject, @Nullable GateSpec spec) {
        return spec == null ? List.of() : allRefusals(spec, about(subject));
    }

    /** The shared factor answer, also handed to every desugaring requirement kind. */
    public boolean factorsPass(@Nonnull Subject subject, @Nullable List<FactorCondition> conditions) {
        return about(subject).factorsPass(subject, conditions);
    }

    // ==================== asking with a query ====================

    /**
     * Does the block pass for {@code query}, and if not, what shut it first: the same walk and token as
     * {@link #firstFailure}, through the block's {@link CompiledGate} (providers resolved once, the verdict kept
     * between asks where every reading is {@code Server} or {@code Player}). A null or empty block passes.
     */
    @Nonnull
    public GateVerdict check(@Nullable GateSpec spec, @Nonnull GateQuery query) {
        if (spec == null || spec.isEmpty()) {
            return GateVerdict.PASS;
        }
        return compile(spec).check(spec, query);
    }

    /** Every unmet requirement for {@code query}, as {@link #allRefusals} answers for a subject. Never kept. */
    @Nonnull
    public List<GateRefusal> refusals(@Nullable GateSpec spec, @Nonnull GateQuery query) {
        if (spec == null) {
            return List.of();
        }
        return allRefusals(spec, ask(query, null));
    }

    /**
     * The compiled form of {@code spec} for this evaluator, made once and kept on the block itself, so a hot
     * path asking {@link #check} pays for no lookup of its own.
     */
    @Nonnull
    CompiledGate compile(@Nonnull GateSpec spec) {
        CompiledGate held = spec.compiled;
        if (held != null && held.owner() == this) {
            return held;
        }
        CompiledGate made = new CompiledGate(this);
        spec.compiled = made;
        return made;
    }

    /** One question about a subject, read through this evaluator's own context seam. */
    @Nonnull
    private Ask about(@Nonnull Subject subject) {
        return new Ask(GateQuery.of(subject), subject, null);
    }

    /** One question as a query, reading through {@code providers} where a compiled gate supplies them. */
    @Nonnull
    Ask ask(@Nonnull GateQuery query, @Nullable Map<FactorCondition, FactorProvider> providers) {
        return new Ask(query, query.subject(), providers);
    }

    // ==================== the walks ====================

    /** The short-circuit walk over a whole block. */
    @Nullable
    String firstFailure(@Nonnull GateSpec spec, @Nonnull Ask ask) {
        String failure = clauseFailure(spec, ask);
        if (failure != null) {
            return failure;
        }
        for (GateClause clause : spec.allOfOrEmpty()) {
            failure = clauseFailure(clause, ask);
            if (failure != null) {
                return failure;
            }
        }
        // A Not group shuts the gate by PASSING. An empty group passes for everyone, so a Not on
        // one shuts the content for everyone - deliberate, and the validator is what warns about it.
        for (GateClause clause : spec.notOrEmpty()) {
            if (clause != null && clauseFailure(clause, ask) == null) {
                return REASON_NOT;
            }
        }
        GateClause[] anyOf = spec.anyOfOrEmpty();
        if (anyOf.length == 0) {
            return null;
        }
        for (GateClause clause : anyOf) {
            if (clauseFailure(clause, ask) == null) {
                return null;
            }
        }
        return REASON_ANY_OF;
    }

    /** Every requirement in ONE group, in leaf order. */
    @Nullable
    private String clauseFailure(@Nullable GateClause clause, @Nonnull Ask ask) {
        if (clause == null || clause.isEmpty()) {
            return null;
        }

        FactorCondition[] conditions = clause.factorsOrEmpty();
        if (conditions.length > 0) {
            if (ask.vocabulary == null) {
                return REASON_FACTOR + firstFactorId(conditions);
            }
            for (FactorCondition condition : conditions) {
                if (condition != null && !condition.isBlank() && !condition.accepts(ask.read(condition))) {
                    return REASON_FACTOR + condition.getFactor();
                }
            }
        }

        // The leaf IS the factor: same registry, same context, same fail-closed rules. The refusal
        // still names the leaf, because that is what the author wrote and what they can go and fix.
        String permission = clause.getPermission();
        if (permission != null && !ask.allPass(List.of(permissionCondition(permission)))) {
            return REASON_PERMISSION;
        }

        for (String questId : clause.questsOrEmpty()) {
            if (questId != null && !questId.isBlank() && !ask.completed(questId.trim())) {
                return REASON_QUEST + questId.trim();
            }
        }

        GateKindRegistry kinds = gateKinds();
        for (Map.Entry<String, Map<String, String>> entry : clause.customOrEmpty().entrySet()) {
            if (!ask.kindPasses(kinds, entry.getKey(), entry.getValue())) {
                return REASON_CUSTOM + entry.getKey();
            }
        }
        return null;
    }

    /** The collect-all walk over a whole block. */
    @Nonnull
    private List<GateRefusal> allRefusals(@Nonnull GateSpec spec, @Nonnull Ask ask) {
        List<GateRefusal> refusals = new ArrayList<>();
        clauseRefusals(spec, ask, refusals);
        for (GateClause clause : spec.allOfOrEmpty()) {
            clauseRefusals(clause, ask, refusals);
        }
        // A Not group shuts the gate by PASSING, exactly as in the short-circuit walk. Its record
        // carries what the group asks for - the met requirements standing in the way.
        for (GateClause clause : spec.notOrEmpty()) {
            if (clause != null && clauseFailure(clause, ask) == null) {
                refusals.add(GateRefusal.not(asksOf(clause)));
            }
        }
        GateClause[] anyOf = spec.anyOfOrEmpty();
        if (anyOf.length == 0) {
            return refusals;
        }
        for (GateClause clause : anyOf) {
            if (clauseFailure(clause, ask) == null) {
                return refusals;
            }
        }
        refusals.add(GateRefusal.anyOf(routesOf(anyOf)));
        return refusals;
    }

    /**
     * Each {@code AnyOf} route as ONE child: a route's single ask directly, several asks folded
     * into one all-of group so "either of these two bundles" never flattens into interchangeable
     * alternatives. A route describing nothing contributes nothing.
     */
    @Nonnull
    private static List<GateRefusal> routesOf(@Nonnull GateClause[] anyOf) {
        List<GateRefusal> routes = new ArrayList<>(anyOf.length);
        for (GateClause clause : anyOf) {
            if (clause == null) {
                continue;
            }
            List<GateRefusal> asks = asksOf(clause);
            if (asks.size() == 1) {
                routes.add(asks.get(0));
            } else if (!asks.isEmpty()) {
                routes.add(GateRefusal.allOf(asks));
            }
        }
        return routes;
    }

    /**
     * What one group ASKS FOR, described from its authored requirements without evaluating any of
     * them - the children a composite refusal carries. Bounds ride along; no reading is resolved,
     * because half the point of describing an {@code AnyOf}'s routes (or a {@code Not} group the
     * subject currently satisfies) is that they were not the ones that refused.
     */
    @Nonnull
    private static List<GateRefusal> asksOf(@Nonnull GateClause clause) {
        List<GateRefusal> asks = new ArrayList<>();
        for (FactorCondition condition : clause.factorsOrEmpty()) {
            if (condition != null && !condition.isBlank()) {
                asks.add(refusalOf(condition));
            }
        }
        String permission = clause.getPermission();
        if (permission != null && !permission.isBlank()) {
            asks.add(GateRefusal.PERMISSION);
        }
        for (String questId : clause.questsOrEmpty()) {
            if (questId != null && !questId.isBlank()) {
                asks.add(GateRefusal.quest(questId.trim()));
            }
        }
        for (String kindId : clause.customOrEmpty().keySet()) {
            if (kindId != null && !kindId.isBlank()) {
                asks.add(GateRefusal.custom(kindId));
            }
        }
        return asks;
    }

    /** Every unmet requirement in ONE group, in leaf order, appended to {@code out}. */
    private void clauseRefusals(@Nullable GateClause clause, @Nonnull Ask ask, @Nonnull List<GateRefusal> out) {
        if (clause == null || clause.isEmpty()) {
            return;
        }

        FactorCondition[] conditions = clause.factorsOrEmpty();
        if (conditions.length > 0) {
            if (ask.vocabulary == null) {
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
            }
            for (FactorCondition condition : conditions) {
                if (condition == null || condition.isBlank()) {
                    continue;
                }
                Double reading = ask.read(condition);
                if (!condition.accepts(reading)) {
                    out.add(GateRefusal.factor(condition.getFactor(), condition.getParam(),
                            condition.getMin(), condition.getMax(), reading));
                }
            }
        }

        String permission = clause.getPermission();
        if (permission != null && !ask.allPass(List.of(permissionCondition(permission)))) {
            out.add(GateRefusal.PERMISSION);
        }

        for (String questId : clause.questsOrEmpty()) {
            if (questId != null && !questId.isBlank() && !ask.completed(questId.trim())) {
                out.add(GateRefusal.quest(questId.trim()));
            }
        }

        GateKindRegistry kinds = gateKinds();
        for (Map.Entry<String, Map<String, String>> entry : clause.customOrEmpty().entrySet()) {
            if (!ask.kindPasses(kinds, entry.getKey(), entry.getValue())) {
                out.add(GateRefusal.custom(entry.getKey()));
            }
        }
    }

    /** One unmet bound as its record, carrying the condition's authored param and bounds. */
    @Nonnull
    private static GateRefusal refusalOf(@Nonnull FactorCondition condition) {
        return GateRefusal.factor(condition.getFactor(), condition.getParam(),
                condition.getMin(), condition.getMax());
    }

    @Nonnull
    private static List<String> tokensOf(@Nonnull List<GateRefusal> refusals) {
        if (refusals.isEmpty()) {
            return List.of();
        }
        List<String> failures = new ArrayList<>(refusals.size());
        for (GateRefusal refusal : refusals) {
            failures.add(refusal.token());
        }
        return failures;
    }

    @Nonnull
    private static String firstFactorId(@Nonnull FactorCondition[] conditions) {
        return Arrays.stream(conditions)
                .filter(c -> c != null && !c.isBlank())
                .map(FactorCondition::getFactor)
                .findFirst()
                .orElse("");
    }

    // ==================== one question ====================

    /**
     * One question being answered: who it is about, the context its readings take (built once, on first
     * need), the vocabulary read when it began, and the compiled providers a {@link CompiledGate} hands in.
     * It is also the factor seam a {@code Custom} kind is handed, so a desugaring kind reads the same moment.
     */
    final class Ask implements GateKind.GateSupport {

        @Nonnull private final GateQuery query;
        @Nullable private Subject subject;
        private boolean subjectRead;
        @Nullable private FactorContext ctx;
        @Nullable final FactorRegistry vocabulary;
        @Nullable private final FactorRegistry local;
        @Nullable private final Map<FactorCondition, FactorProvider> providers;

        private Ask(@Nonnull GateQuery query, @Nullable Subject subject,
                @Nullable Map<FactorCondition, FactorProvider> providers) {
            this.query = query;
            this.subject = subject;
            this.subjectRead = subject != null;
            this.vocabulary = factors.get();
            this.local = query.local();
            this.providers = providers;
        }

        /** The context every reading takes: the query's own, else the subject's, with the moment's leaves laid over. */
        @Nonnull
        FactorContext context() {
            FactorContext built = ctx;
            if (built == null) {
                FactorContext base = query.context();
                if (base == null) {
                    Subject who = query.subject();
                    base = who == null ? null : factorContext.apply(who);
                }
                built = query.over(base == null ? FactorContext.builder().build() : base);
                ctx = built;
            }
            return built;
        }

        /** Who the question is about: the query's subject, else the context's player; null for nobody. */
        @Nullable
        Subject subject() {
            if (!subjectRead) {
                subjectRead = true;
                subject = playerOf(context());
            }
            return subject;
        }

        /** The id a kept verdict is filed under, or null when there is nobody to keep it for. */
        @Nullable
        UUID subjectId() {
            Subject who = subject();
            return who == null || NOBODY.equals(who.id()) ? null : who.id();
        }

        /** True when the question carries a local vocabulary, whose readings belong to its moment. */
        boolean hasLocal() {
            return local != null;
        }

        /** One condition's reading: the local vocabulary for its own ids, a compiled provider, else the vocabulary. */
        @Nullable
        Double read(@Nonnull FactorCondition condition) {
            String id = condition.getFactor();
            FactorContext scoped = context().withParam(condition.getParam());
            if (local != null && local.answersLocally(id)) {
                return local.resolve(id, scoped);
            }
            FactorRegistry vocab = vocabulary;
            if (vocab == null) {
                return null;
            }
            if (providers != null && providers.containsKey(condition)) {
                FactorProvider provider = providers.get(condition);
                if (provider == null) {
                    return null;
                }
                try {
                    Double value = provider.resolve(scoped);
                    return value != null && Double.isFinite(value) ? value : null;
                } catch (Throwable t) {
                    // The vocabulary counts and warns a throwing provider; asking it is how this one is.
                    return vocab.resolve(id, scoped);
                }
            }
            return vocab.resolve(id, scoped);
        }

        /** Do ALL of {@code conditions} pass? False with no vocabulary; an empty list passes. */
        boolean allPass(@Nullable List<FactorCondition> conditions) {
            if (conditions == null || conditions.isEmpty()) {
                return true;
            }
            if (vocabulary == null) {
                return false;
            }
            for (FactorCondition condition : conditions) {
                if (condition != null && !condition.isBlank() && !condition.accepts(read(condition))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean factorsPass(@Nonnull Subject who, @Nonnull List<FactorCondition> conditions) {
            return allPass(conditions);
        }

        /** Has the subject finished {@code questId}? Nobody to ask refuses. */
        boolean completed(@Nonnull String questId) {
            Subject who = subject();
            return who != null && completion.hasCompleted(who, questId);
        }

        /** Does the registered kind {@code kindId} pass? Unregistered, throwing, or nobody to ask refuses. */
        boolean kindPasses(@Nonnull GateKindRegistry kinds, @Nonnull String kindId,
                @Nullable Map<String, String> authored) {
            GateKind kind = kinds.kind(kindId);
            Subject who = subject();
            if (kind == null || who == null) {
                return false;
            }
            Map<String, String> params = authored == null ? Map.of() : authored;
            try {
                return kind.passes(who, params, this);
            } catch (Exception e) {
                kinds.recordFailure(kindId, e.getMessage());
                warn.accept("requirement kind '" + kindId + "' threw, so the gate stays shut: " + e.getMessage());
                return false;
            }
        }
    }

    /** The subject for a context's live player, or null when it carries none. */
    @Nullable
    private static Subject playerOf(@Nonnull FactorContext ctx) {
        try {
            Store<EntityStore> store = ctx.store();
            Ref<EntityStore> ref = ctx.subject();
            if (store == null || ref == null || !ref.isValid()) {
                return null;
            }
            return Subject.of(store.getComponent(ref, PlayerRef.getComponentType()),
                    store.getComponent(ref, Player.getComponentType()));
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== building ====================

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
            this.factorContext = subject -> {
                FactorContext ctx = factorContext.apply(subject);
                return ctx == null ? FactorContext.builder().build() : ctx;
            };
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
