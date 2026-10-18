package com.ziggfreed.common.progress.gate;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.FactorTraits;
import com.ziggfreed.common.factor.Volatility;

/**
 * One {@code Requires} block as an evaluator answers it on a hot path ({@link GateEvaluator#check}): every
 * condition's factor provider looked up once, and the answer kept between asks where nothing in the block can
 * change unseen. Made on the first check and kept on the block itself, never authored.
 *
 * <p><b>Providers</b> are looked up against the evaluator's vocabulary and kept while
 * {@link FactorRegistry#generation()} and the vocabulary itself stand still; any registration, contribution or
 * Factors reload anywhere moves the generation, and the next check looks them up again. A provider that
 * throws is asked again through the vocabulary, which counts and warns it as any resolve would.
 *
 * <p><b>Verdicts</b> are kept by the block's volatility, the most volatile of its parts: each condition's
 * factor reads its Factors file's {@code Volatility} (unwritten is {@code Moment}), a {@code Permission}
 * leaf reads {@code hytale:permission}'s, and a {@code Quests} or {@code Custom} leaf is always {@code Moment}.
 * A {@code Moment} block is evaluated on every ask. A {@code Server} block keeps one verdict for everyone
 * while the server epoch and the generation stand; a {@code Player} block keeps one per player while the
 * server epoch, the generation and that player's epoch stand ({@link GateEpochs}). A question carrying a
 * local vocabulary is never kept, and nothing is kept before the server has booted. The moment's own leaves
 * (held item, item, target, block) never reach a {@code Server} or {@code Player} reading, by what those
 * words declare, so a question carrying them may still be answered from a kept verdict.
 */
final class CompiledGate {

    /** What one compile produced: the vocabulary and generation it was made against, the providers, the volatility. */
    private record Plan(@Nullable FactorRegistry vocabulary, long generation,
            @Nonnull Map<FactorCondition, FactorProvider> providers, @Nonnull Volatility volatility) {
    }

    /** One kept verdict and the clocks it was taken at. */
    private record Kept(long server, long generation, long player, @Nonnull GateVerdict verdict) {
    }

    @Nonnull private final GateEvaluator owner;
    @Nullable private volatile Plan plan;
    @Nullable private volatile Kept everyone;
    @Nonnull private final Map<GateEpochs.PlayerEpoch, Kept> players =
            Collections.synchronizedMap(new WeakHashMap<>());

    CompiledGate(@Nonnull GateEvaluator owner) {
        this.owner = owner;
    }

    @Nonnull
    GateEvaluator owner() {
        return owner;
    }

    /** The block's volatility as last compiled against the evaluator's vocabulary. */
    @Nonnull
    Volatility volatility(@Nonnull GateSpec spec) {
        return planFor(spec).volatility();
    }

    /** The verdict for {@code query}: kept where the block's volatility allows, else evaluated now. */
    @Nonnull
    GateVerdict check(@Nonnull GateSpec spec, @Nonnull GateQuery query) {
        Plan current = planFor(spec);
        GateEvaluator.Ask ask = owner.ask(query, current.providers());
        Volatility volatility = current.volatility();
        if (volatility == Volatility.MOMENT || ask.hasLocal() || !GateEpochs.keeping()) {
            return evaluate(spec, ask);
        }
        long server = GateEpochs.server();
        if (volatility == Volatility.SERVER) {
            Kept kept = everyone;
            if (kept != null && kept.server() == server && kept.generation() == current.generation()) {
                return kept.verdict();
            }
            GateVerdict verdict = evaluate(spec, ask);
            everyone = new Kept(server, current.generation(), 0L, verdict);
            return verdict;
        }
        UUID id = ask.subjectId();
        if (id == null) {
            return evaluate(spec, ask);
        }
        GateEpochs.PlayerEpoch epoch = GateEpochs.player(id);
        long mine = epoch.value();
        Kept kept = players.get(epoch);
        if (kept != null && kept.server() == server && kept.generation() == current.generation()
                && kept.player() == mine) {
            return kept.verdict();
        }
        GateVerdict verdict = evaluate(spec, ask);
        players.put(epoch, new Kept(server, current.generation(), mine, verdict));
        return verdict;
    }

    @Nonnull
    private GateVerdict evaluate(@Nonnull GateSpec spec, @Nonnull GateEvaluator.Ask ask) {
        return GateVerdict.of(owner.firstFailure(spec, ask));
    }

    /** The current plan, compiled again when the vocabulary or its generation moved. */
    @Nonnull
    private Plan planFor(@Nonnull GateSpec spec) {
        FactorRegistry vocabulary = owner.vocabulary();
        long generation = FactorRegistry.generation();
        Plan current = plan;
        if (current != null && current.generation() == generation && current.vocabulary() == vocabulary) {
            return current;
        }
        Map<FactorCondition, FactorProvider> providers = new IdentityHashMap<>();
        Volatility volatility = Volatility.SERVER;
        volatility = compile(spec, vocabulary, providers, volatility);
        for (GateClause clause : spec.allOfOrEmpty()) {
            volatility = compile(clause, vocabulary, providers, volatility);
        }
        for (GateClause clause : spec.anyOfOrEmpty()) {
            volatility = compile(clause, vocabulary, providers, volatility);
        }
        for (GateClause clause : spec.notOrEmpty()) {
            volatility = compile(clause, vocabulary, providers, volatility);
        }
        Plan made = new Plan(vocabulary, generation, Collections.unmodifiableMap(providers), volatility);
        plan = made;
        return made;
    }

    /** One group's providers into {@code providers}; answers the volatility with the group's folded in. */
    @Nonnull
    private static Volatility compile(@Nullable GateClause clause, @Nullable FactorRegistry vocabulary,
            @Nonnull Map<FactorCondition, FactorProvider> providers, @Nonnull Volatility so) {
        if (clause == null) {
            return so;
        }
        Volatility volatility = so;
        for (FactorCondition condition : clause.factorsOrEmpty()) {
            if (condition == null || condition.isBlank()) {
                continue;
            }
            providers.put(condition, vocabulary == null ? null : vocabulary.providerFor(condition.getFactor()));
            volatility = volatility.and(FactorTraits.of(condition.getFactor()).volatility());
        }
        if (clause.getPermission() != null) {
            volatility = volatility.and(FactorTraits.of(GateEvaluator.PERMISSION_FACTOR).volatility());
        }
        if (clause.questsOrEmpty().length > 0 || !clause.customOrEmpty().isEmpty()) {
            volatility = Volatility.MOMENT;
        }
        return volatility;
    }
}
