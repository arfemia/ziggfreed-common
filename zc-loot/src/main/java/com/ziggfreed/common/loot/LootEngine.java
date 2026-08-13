package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.WeightedPick;

/**
 * DOES what {@link RollEvaluator} decided: resolves a {@link LootRef} to the rolls and pools it
 * evaluates, decides the pass against a single {@link FactorSnapshot} for the batch, and applies
 * whatever it settled on.
 *
 * <p>Deciding and doing are separate calls, not just separate classes. {@link #select} answers what
 * a pass WOULD hand over and touches nothing; {@link #rollAndGrant} is that answer applied. A site
 * that pays out later - an end-of-run spoils screen, a claim waiting for the player to come back -
 * rolls once with {@link #select} while the inputs are known and grants the saved answer whenever
 * they turn up, so what was shown and what was handed over cannot disagree.
 *
 * <p>Every side effect leaves through a SEAM rather than a hard call, and that is the whole design.
 * This class never imports an inventory, a world, a presentation layer, or an effect system: it is
 * handed an item sink, a drop-list roller, a command dispatcher, and a reward registry, and it uses
 * whichever of those the caller supplied. A caller that supplies none gets a full evaluation with no
 * effects, which is exactly what a preview or a dry run wants.
 *
 * <p>{@link Result} reports what actually LANDED rather than what was attempted, because the
 * difference is what the smart-cue rule turns on: a drop table whose own weights rolled empty
 * produced nothing, and a cue authored beside it must stay silent rather than celebrate an empty
 * hand. That is also why {@link #applyGrants} answers a boolean instead of returning void.
 */
public final class LootEngine {

    private LootEngine() {
    }

    // ==================== seams ====================

    /**
     * Hands over {@code count} of {@code itemId}, answering how many actually reached the player.
     * A short answer is a real outcome (a full inventory), not a failure.
     */
    @FunctionalInterface
    public interface ItemSink {
        int deliver(@Nonnull String itemId, int count);
    }

    /**
     * Rolls ONE native drop list and delivers whatever it produced, answering what landed
     * ({@code itemId -> quantity}). An EMPTY answer covers all three ways a table pays nothing: it
     * rolled its own empty branch, the id resolved to no asset, or nothing could be delivered.
     */
    @FunctionalInterface
    public interface DropListSink {
        @Nonnull
        Map<String, Integer> roll(@Nonnull String dropListId);
    }

    // ==================== result ====================

    /** The tally of one {@link #rollAndGrant} pass. */
    public static final class Result {

        private final Map<String, Integer> items = new LinkedHashMap<>();
        private final List<String> cues = new ArrayList<>();
        private int commandsRun;
        private int rewardsPaid;
        private int rewardsLost;

        /** Every item that reached the player this pass ({@code itemId -> quantity}), merged. */
        @Nonnull
        public Map<String, Integer> getItems() {
            return items;
        }

        /**
         * The EARNED cues, in evaluation order, roll-level before floor-level within one roll. Earned
         * is the load-bearing word: a cue with no grants beside it rides on the plain hit, and a cue
         * beside grants rides only once those grants genuinely produced something.
         */
        @Nonnull
        public List<String> getCues() {
            return cues;
        }

        public int getCommandsRun() {
            return commandsRun;
        }

        /** Registered-kind rewards that paid out (or were queued for a later attempt). */
        public int getRewardsPaid() {
            return rewardsPaid;
        }

        /** Registered-kind rewards that could be neither paid nor queued - genuinely lost. */
        public int getRewardsLost() {
            return rewardsLost;
        }

        /** True when this pass produced anything at all. */
        public boolean anyGranted() {
            return !items.isEmpty() || commandsRun > 0 || rewardsPaid > 0;
        }
    }

    // ==================== resolution ====================

    /** Everything a {@link LootRef} evaluates, once its referenced tables have been looked up. */
    public record Resolved(@Nonnull List<Roll> rolls, @Nonnull List<LootPool> pools) {

        /** Nothing to evaluate at all. */
        @Nonnull
        public static Resolved empty() {
            return new Resolved(List.of(), List.of());
        }
    }

    /**
     * The rolls a {@link LootRef} actually evaluates: every referenced table's rolls in authored
     * order, then the ref's own inline rolls. A referenced table's POOL is not included - use
     * {@link #resolve} for the whole of what a ref evaluates.
     *
     * <p>An id no table answers to is SKIPPED and reported to {@code unknownSink} rather than
     * failing the pass - one bad reference must not cost a player the rest of the loot, and the
     * validator catches the same mistake at authoring time where it is cheap to fix.
     */
    @Nonnull
    public static List<Roll> resolveRolls(@Nullable LootRef ref, @Nullable Consumer<String> unknownSink) {
        return resolve(ref, unknownSink).rolls();
    }

    /**
     * Everything a {@link LootRef} evaluates: each referenced table's rolls THEN its pool, in the
     * order the ids were written, followed by the ref's own inline rolls.
     *
     * <p>Each referenced table keeps its OWN pool rather than the pools being merged, because a pool
     * is a bag whose entries compete: pouring two tables' bags together would change the odds inside
     * both. Two referenced tables draw twice, once each.
     *
     * <p>Tables resolve through {@link LootableConfig#resolve}, so whatever other packs contributed
     * to a referenced table is already part of what comes back.
     */
    @Nonnull
    public static Resolved resolve(@Nullable LootRef ref, @Nullable Consumer<String> unknownSink) {
        if (ref == null) {
            return Resolved.empty();
        }
        List<Roll> rolls = new ArrayList<>();
        List<LootPool> pools = new ArrayList<>();
        String[] lootables = ref.getLootables();
        if (lootables != null) {
            for (String tableId : lootables) {
                if (tableId == null || tableId.isBlank()) {
                    continue;
                }
                LootableAsset table = LootableConfig.getInstance().resolve(tableId);
                if (table == null) {
                    report(unknownSink, tableId);
                    continue;
                }
                rolls.addAll(table.rollsOrEmpty());
                pools.addAll(table.poolOrEmpty());
            }
        }
        if (ref.getRolls() != null) {
            rolls.addAll(Arrays.asList(ref.getRolls()));
        }
        return new Resolved(rolls, pools);
    }

    // ==================== selection ====================

    /**
     * ONE payout a pass decided on: the grants group to hand over and the cue authored beside it.
     *
     * <p>It exists so a caller can learn what a pass WOULD hand over without handing it over. A
     * granting site applies each in order and is done; a site that pays out LATER (an end-of-run
     * spoils screen, a claim the player has to walk back for) rolls once at the moment the inputs
     * are known, keeps the answer, and grants it whenever the player turns up. Rolling twice would
     * mean showing one reward and handing over another.
     */
    public record Selected(@Nullable LootGrants grants, @Nullable String cue) {
    }

    /**
     * Decide the whole pass without applying any of it: every roll that answers to {@code trigger}
     * (null asks for all of them) and hit, then each pool's draws, against ONE {@code lookup}.
     *
     * <p>A roll contributes up to two entries in evaluation order - its own grants and cue, then
     * whichever ladder floor it reached - because those two are judged separately by the cue rule.
     * A pool contributes one entry per pick, in pick order. Nothing that would hand over nothing and
     * play nothing is returned at all.
     *
     * <p>Pools are drawn only on the site's DEFAULT moment: a pool cannot name a trigger, so asking
     * for a particular one asks only for the rolls that answer to it.
     *
     * @param sample a fresh {@code [0,1)} number per draw, chance and pick alike; inject a pinned
     *               one to test, or a seeded one for a payout that has to be reproducible
     */
    @Nonnull
    public static List<Selected> select(@Nonnull List<Roll> rolls, @Nonnull List<LootPool> pools,
            @Nullable String trigger, @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier sample) {
        List<Selected> out = new ArrayList<>();
        for (Roll roll : rolls) {
            if (roll == null || !roll.answersTo(trigger)) {
                continue;
            }
            RollEvaluator.Outcome outcome = RollEvaluator.evaluate(roll, lookup, sample);
            if (!outcome.isHit()) {
                continue;
            }
            add(out, outcome.getTopGrants(), outcome.getTopCue(), sample);
            add(out, outcome.getFloorGrants(), outcome.getFloorCue(), sample);
        }
        if (trigger == null || Roll.DEFAULT_TRIGGER.equalsIgnoreCase(trigger)) {
            for (LootPool pool : pools) {
                drawPool(out, pool, lookup, sample);
            }
        }
        return out;
    }

    /** Draw one pool: as many picks as it earns, among the entries currently competing. */
    private static void drawPool(@Nonnull List<Selected> out, @Nullable LootPool pool,
            @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier sample) {
        if (pool == null) {
            return;
        }
        int picks = pool.pickCount(lookup);
        if (picks <= 0) {
            return;
        }
        List<LootPool.Entry> eligible = pool.eligible(lookup);
        if (eligible.isEmpty()) {
            return;
        }
        for (LootPool.Entry entry : WeightedPick.some(eligible, LootPool.Entry::effectiveWeight,
                picks, false, sample)) {
            add(out, entry.getGrants(), null, sample);
        }
    }

    private static void add(@Nonnull List<Selected> out, @Nullable LootGrants grants, @Nullable String cue,
            @Nonnull DoubleSupplier sample) {
        boolean hasCue = cue != null && !cue.isBlank();
        if (grants == null && !hasCue) {
            return;
        }
        // Varying quantities are drawn HERE, so what a pass decided on is a concrete payout even when
        // the handing over happens much later.
        out.add(new Selected(grants == null ? null : grants.drawQuantities(sample), hasCue ? cue : null));
    }

    // ==================== the pass ====================

    /**
     * Evaluate and apply every roll in {@code rolls} that answers to {@code trigger} (null asks for
     * all of them), against ONE {@code lookup} for the whole batch.
     *
     * @param chanceSample a fresh {@code [0,1)} number per draw; inject a pinned one to test
     * @param sinks        where the effects go; every leaf is optional
     */
    @Nonnull
    public static Result rollAndGrant(@Nonnull List<Roll> rolls, @Nullable String trigger,
            @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier chanceSample, @Nonnull Sinks sinks) {
        return rollAndGrant(rolls, List.of(), trigger, lookup, chanceSample, sinks);
    }

    /**
     * The whole pass: every roll that answers to {@code trigger}, then each pool's draws, applied in
     * that order through {@code sinks}.
     *
     * @param sample a fresh {@code [0,1)} number per draw, chance and pick alike
     * @param sinks  where the effects go; every leaf is optional
     */
    @Nonnull
    public static Result rollAndGrant(@Nonnull List<Roll> rolls, @Nonnull List<LootPool> pools,
            @Nullable String trigger, @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier sample,
            @Nonnull Sinks sinks) {
        Result result = new Result();
        for (Selected selected : select(rolls, pools, trigger, lookup, sample)) {
            boolean produced = applyGrants(selected.grants(), sinks, result);
            collectEarnedCue(result, selected.cue(), selected.grants(), produced);
        }
        return result;
    }

    /**
     * Apply ONE grants group, answering whether it PRODUCED anything - the measurement the smart-cue
     * rule reads. Produced means an item reached the player, a command ran, or a registered reward
     * paid out. A group whose every leaf went to an absent sink produces nothing, which is correct:
     * nothing happened.
     */
    public static boolean applyGrants(@Nullable LootGrants grants, @Nonnull Sinks sinks,
            @Nonnull Result result) {
        if (grants == null) {
            return false;
        }
        boolean produced = false;

        if (sinks.items != null) {
            for (LootGrants.Item item : grants.itemsOrEmpty()) {
                int delivered = deliver(sinks, item.getItem(), item.effectiveCount());
                if (delivered > 0) {
                    result.items.merge(item.getItem(), delivered, Integer::sum);
                    produced = true;
                }
            }
        }

        String[] dropLists = grants.getDropLists();
        if (dropLists != null && sinks.dropLists != null) {
            for (String dropListId : dropLists) {
                if (dropListId == null || dropListId.isBlank()) {
                    continue;
                }
                Map<String, Integer> landed = rollDropList(sinks, dropListId);
                for (Map.Entry<String, Integer> entry : landed.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                        result.items.merge(entry.getKey(), entry.getValue(), Integer::sum);
                        produced = true;
                    }
                }
            }
        }

        String[] commands = grants.getCommands();
        if (commands != null && sinks.commandDispatcher != null) {
            int ran = CommandRunner.runAllWith(sinks.commandDispatcher, Arrays.asList(commands),
                    sinks.commandPlaceholders, sinks.warn);
            if (ran > 0) {
                result.commandsRun += ran;
                produced = true;
            }
        }

        List<RewardSpec> specs = grants.rewardSpecs();
        if (!specs.isEmpty() && sinks.kinds != null && sinks.subject != null) {
            RewardGrants.GrantOutcome outcome = RewardGrants.grantAll(specs, sinks.subject,
                    sinks.sourceId, sinks.kinds, sinks.retryQueue,
                    sinks.warn != null ? sinks.warn : message -> { });
            int paid = outcome.granted() + outcome.queued();
            result.rewardsPaid += paid;
            result.rewardsLost += outcome.failed();
            if (paid > 0) {
                produced = true;
            }
        }
        return produced;
    }

    /**
     * The smart-cue decision, applied identically at the roll and floor altitudes: collect
     * {@code cue} when it is PURE (no grants group beside it, or an empty one) or when the group
     * beside it genuinely produced something.
     */
    private static void collectEarnedCue(@Nonnull Result result, @Nullable String cue,
            @Nullable LootGrants grants, boolean produced) {
        if (cue == null || cue.isBlank()) {
            return;
        }
        if (grants == null || grants.isEmpty() || produced) {
            result.cues.add(cue);
        }
    }

    private static int deliver(@Nonnull Sinks sinks, @Nullable String itemId, int count) {
        if (itemId == null || itemId.isBlank() || count <= 0 || sinks.items == null) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(count, sinks.items.deliver(itemId, count)));
        } catch (Throwable t) {
            report(sinks.warn, "item grant '" + itemId + "' failed: " + t);
            return 0;
        }
    }

    @Nonnull
    private static Map<String, Integer> rollDropList(@Nonnull Sinks sinks, @Nonnull String dropListId) {
        try {
            Map<String, Integer> landed = sinks.dropLists.roll(dropListId);
            return landed == null ? Map.of() : landed;
        } catch (Throwable t) {
            report(sinks.warn, "drop list '" + dropListId + "' failed: " + t);
            return Map.of();
        }
    }

    private static void report(@Nullable Consumer<String> sink, @Nonnull String message) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(message);
        } catch (Throwable ignored) {
            // A sink that throws costs its own line, never the pass that called it.
        }
    }

    // ==================== sinks ====================

    /**
     * Where a pass's effects go. Every leaf is independently optional, so a caller wires up only
     * what it can actually do: a preview supplies nothing, a chest supplies items, a full grant site
     * supplies all four.
     */
    public static final class Sinks {

        /** A pass that performs no effects at all - a dry run that still reports what WOULD land. */
        public static final Sinks NONE = builder().build();

        @Nullable private final ItemSink items;
        @Nullable private final DropListSink dropLists;
        @Nullable private final CommandRunner.Dispatcher commandDispatcher;
        @Nullable private final Map<String, String> commandPlaceholders;
        @Nullable private final RewardKindRegistry kinds;
        @Nullable private final Subject subject;
        @Nonnull private final String sourceId;
        @Nullable private final BiConsumer<Subject, String> retryQueue;
        @Nullable private final Consumer<String> warn;

        private Sinks(@Nonnull Builder b) {
            this.items = b.items;
            this.dropLists = b.dropLists;
            this.commandDispatcher = b.commandDispatcher;
            this.commandPlaceholders = b.commandPlaceholders;
            this.kinds = b.kinds;
            this.subject = b.subject;
            this.sourceId = b.sourceId;
            this.retryQueue = b.retryQueue;
            this.warn = b.warn;
        }

        @Nonnull
        public static Builder builder() {
            return new Builder();
        }

        /** Fluent builder; an unset leaf simply means that kind of grant does nothing. */
        public static final class Builder {

            @Nullable private ItemSink items;
            @Nullable private DropListSink dropLists;
            @Nullable private CommandRunner.Dispatcher commandDispatcher;
            @Nullable private Map<String, String> commandPlaceholders;
            @Nullable private RewardKindRegistry kinds;
            @Nullable private Subject subject;
            @Nonnull private String sourceId = "loot";
            @Nullable private BiConsumer<Subject, String> retryQueue;
            @Nullable private Consumer<String> warn;

            private Builder() {
            }

            @Nonnull
            public Builder items(@Nullable ItemSink items) {
                this.items = items;
                return this;
            }

            @Nonnull
            public Builder dropLists(@Nullable DropListSink dropLists) {
                this.dropLists = dropLists;
                return this;
            }

            /** The dispatcher command grants run through, plus the placeholders substituted first. */
            @Nonnull
            public Builder commands(@Nullable CommandRunner.Dispatcher dispatcher,
                    @Nullable Map<String, String> placeholders) {
                this.commandDispatcher = dispatcher;
                this.commandPlaceholders = placeholders;
                return this;
            }

            /** The vocabulary registered-kind rewards are looked up in, and who they are paid to. */
            @Nonnull
            public Builder rewards(@Nullable RewardKindRegistry kinds, @Nullable Subject subject) {
                this.kinds = kinds;
                this.subject = subject;
                return this;
            }

            /** Labels this payout in logs and retry commands, conventionally {@code "loot:<tableId>"}. */
            @Nonnull
            public Builder sourceId(@Nullable String sourceId) {
                this.sourceId = sourceId == null || sourceId.isBlank() ? "loot" : sourceId;
                return this;
            }

            /** Where a failed reward's replayable command is parked for the player's next connect. */
            @Nonnull
            public Builder retryQueue(@Nullable BiConsumer<Subject, String> retryQueue) {
                this.retryQueue = retryQueue;
                return this;
            }

            @Nonnull
            public Builder warn(@Nullable Consumer<String> warn) {
                this.warn = warn;
                return this;
            }

            @Nonnull
            public Sinks build() {
                return new Sinks(this);
            }
        }
    }
}
