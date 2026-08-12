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

/**
 * DOES what {@link RollEvaluator} decided: resolves a {@link LootRef} to its rolls, evaluates each
 * one against a single {@link FactorSnapshot} for the batch, and applies whatever they granted.
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

    /**
     * The rolls a {@link LootRef} actually evaluates: every referenced table's rolls in authored
     * order, then the ref's own inline rolls.
     *
     * <p>An id no table answers to is SKIPPED and reported to {@code unknownSink} rather than
     * failing the pass - one bad reference must not cost a player the rest of the loot, and the
     * validator catches the same mistake at authoring time where it is cheap to fix.
     */
    @Nonnull
    public static List<Roll> resolveRolls(@Nullable LootRef ref, @Nullable Consumer<String> unknownSink) {
        List<Roll> out = new ArrayList<>();
        if (ref == null) {
            return out;
        }
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
                if (table.getRolls() != null) {
                    out.addAll(Arrays.asList(table.getRolls()));
                }
            }
        }
        if (ref.getRolls() != null) {
            out.addAll(Arrays.asList(ref.getRolls()));
        }
        return out;
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
        Result result = new Result();
        for (Roll roll : rolls) {
            if (roll == null || !roll.answersTo(trigger)) {
                continue;
            }
            RollEvaluator.Outcome outcome = RollEvaluator.evaluate(roll, lookup, chanceSample);
            if (!outcome.isHit()) {
                continue;
            }
            boolean topProduced = applyGrants(outcome.getTopGrants(), sinks, result);
            boolean floorProduced = applyGrants(outcome.getFloorGrants(), sinks, result);
            collectEarnedCue(result, outcome.getTopCue(), outcome.getTopGrants(), topProduced);
            collectEarnedCue(result, outcome.getFloorCue(), outcome.getFloorGrants(), floorProduced);
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
