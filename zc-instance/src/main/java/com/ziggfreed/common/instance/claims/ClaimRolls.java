package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootFactors;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one way a payout writes a Claim from loot tables (a dungeon's clear, an endless run's waves, a reward chest's
 * {@code Contents}, {@code /zigclaims give}): each table decided once ({@code LootEngine.select}), kept as rewards to
 * hand over later ({@link DeferredRewards}), each command reward keeping {@code {player}} for whoever claims it,
 * repeated picks of one item read as one reward, and what that lands written as the payout's Claim through
 * {@link ClaimStore#award}. The Claim pays exactly that when it is taken.
 *
 * <p><b>Who the tables roll for.</b> A player who is online rolls as themselves: the question is the installed
 * vocabulary about their live entity ({@link LootRewardKinds#lookupAbout}), so a table's live {@code Requires}, a
 * roll's {@code Conditions} and a chance's terms read their levels, ranks and quests, with the run's own two
 * readings laid over it ({@link LootFactors#over}: a won run with no score, so a table gated on a win pays its
 * floor). The roll runs on their world's thread: inline when that is the thread calling, else queued there, and
 * a player between worlds is waited for a few seconds. A player who is not online (an admin's give to one, a member
 * who left the server before the clear) rolls with nobody to ask: the run's two readings alone
 * ({@link LootFactors#lookupFor}), so a table or row gated on anything about a player pays nothing for them, as it
 * fails closed everywhere else. A site's audit warns of a table it names that gates on the player
 * ({@link LootableAsset#gatesOnPlayer}).
 */
public final class ClaimRolls {

    /** The name a deferred command keeps for whoever presses Claim. */
    public static final String CLAIMER = "{player}";

    private static final String LOG = "[claims] ";

    /** How many times a payout waits for its player to reach a world thread before it rolls with nobody. */
    static final int HOPS = 10;

    /** How long a payout waits for a player between worlds before it looks again. */
    static final long BETWEEN_WORLDS_RETRY_MS = 1000L;

    /** One table a payout rolls, and how many times (one pass each). */
    public record Draw(@Nonnull LootableAsset table, int times) {

        public Draw {
            Objects.requireNonNull(table, "table");
            times = Math.max(0, times);
        }

        /** {@code table}, rolled once. */
        @Nonnull
        public static Draw once(@Nonnull LootableAsset table) {
            return new Draw(table, 1);
        }
    }

    /**
     * What a payout writes: whose Claim, the Claim as it reads with no rewards yet ({@code draft}: its id, its source,
     * which the rolls are kept under too, its title, difficulty, time and fight), the tables it rolls, the returns
     * that count it ({@code diminish}, null for every payout in full, under {@code diminishKey}), and where a reward
     * that cannot be kept is reported.
     */
    public record Payout(@Nonnull UUID player, @Nonnull Claim draft, @Nonnull List<Draw> draws,
            @Nullable DiminishSpec diminish, @Nonnull String diminishKey, @Nonnull Consumer<String> warn) {

        public Payout {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(draft, "draft");
            draws = List.copyOf(draws);
            Objects.requireNonNull(diminishKey, "diminishKey");
            Objects.requireNonNull(warn, "warn");
        }
    }

    private ClaimRolls() {
    }

    /**
     * Roll {@code payout}'s tables for its player and write what they land as its Claim, then hand {@code then} the
     * Claim as the store holds it (scaled by its returns), or null when nothing was written (the rolls landed nothing,
     * the player still holds a Claim of that id, or the write failed). Never throws, and {@code then} runs exactly
     * once: inline when the player is offline or this is their world's thread (answering true), else later on their
     * world's thread (answering false).
     */
    public static boolean award(@Nonnull Payout payout, @Nonnull Consumer<Claim> then) {
        return onPlayersThread(payout.player(), HOPS, ref -> {
            Claim written = write(payout, ref);
            try {
                then.accept(written);
            } catch (Throwable t) {
                SafeLog.warn(LOG + "what follows the Claim '" + payout.draft().id() + "' failed", t);
            }
        });
    }

    /** The rolls and the write, for {@code ref} (null: nobody to ask); the Claim as held, or null. Never throws. */
    @Nullable
    private static Claim write(@Nonnull Payout payout, @Nullable Ref<EntityStore> ref) {
        Claim draft = payout.draft();
        try {
            FactorLookup lookup = lookupFor(ref);
            List<InstanceReward> rewards = new ArrayList<>();
            for (Draw draw : payout.draws()) {
                for (int i = 0; i < draw.times(); i++) {
                    rewards.addAll(roll(draw.table(), payout.player(), draft.source(), lookup, payout.warn()));
                }
            }
            if (rewards.isEmpty()) {
                return null;
            }
            long now = draft.createdAt();
            if (!ClaimStore.shared().award(payout.player(), draft.withRewards(rewards), payout.diminish(),
                    payout.diminishKey(), now)) {
                return null;
            }
            for (Claim held : ClaimStore.shared().list(payout.player())) {
                if (held.is(draft.id())) {
                    return held;
                }
            }
            return null;
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the Claim '" + draft.id() + "' could not be rolled or written for " + payout.player(), t);
            return null;
        }
    }

    /** One pass of {@code table} under {@code lookup}, as rewards kept for later under {@code source}. */
    @Nonnull
    private static List<InstanceReward> roll(@Nonnull LootableAsset table, @Nonnull UUID player, @Nonnull String source,
            @Nonnull FactorLookup lookup, @Nonnull Consumer<String> warn) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<LootEngine.Selected> decided = LootEngine.select(table.payingRolls(), table.payingPool(), null, lookup,
                random::nextDouble);
        return InstanceReward.merge(DeferredRewards.fromSelection(decided, RewardKinds.shared(),
                Subject.of(player, CLAIMER), source, warn));
    }

    /**
     * The question a payout's tables are asked: about the player behind {@code ref} (on their world's thread) with a
     * won run's two readings laid over it, or those two alone with nobody to ask.
     */
    @Nonnull
    static FactorLookup lookupFor(@Nullable Ref<EntityStore> ref) {
        LootFactors.Outcome won = new LootFactors.Outcome(0, true);
        if (ref == null || !ref.isValid()) {
            return LootFactors.lookupFor(won.score(), won.win());
        }
        FactorContext about = FactorContext.about(ref.getStore(), ref).toBuilder().payload(won).build();
        return LootFactors.over(LootRewardKinds.lookupAbout(about), won);
    }

    /**
     * Run {@code task} with {@code player}'s live entity on its world's thread: inline when this is it (true), queued
     * there when they stand in another world, and asked again a second later while they are between worlds, at most
     * {@code hops} times. With nobody online, or once the hops run out, {@code task} runs where it is asked with
     * nobody (null). {@code task} runs exactly once.
     */
    private static boolean onPlayersThread(@Nonnull UUID player, int hops, @Nonnull Consumer<Ref<EntityStore>> task) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        if (ref == null) {
            if (hops > 0 && connected(player)) {
                try {
                    HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onPlayersThread(player, hops - 1, task),
                            BETWEEN_WORLDS_RETRY_MS, TimeUnit.MILLISECONDS);
                    return false;
                } catch (Throwable t) {
                    SafeLog.fine(LOG + "a payout could not wait for its player between worlds: " + t.getMessage());
                }
            }
            if (connected(player)) {
                SafeLog.info(LOG + "a payout rolled for a player who stayed between worlds, with nobody to ask");
            }
            task.accept(null);
            return true;
        }
        Store<EntityStore> store = ref.getStore();
        if (store.isInThread()) {
            task.accept(ref);
            return true;
        }
        World world = store.getExternalData().getWorld();
        if (hops > 0 && world != null && world.isAlive()) {
            try {
                world.execute(() -> onPlayersThread(player, hops - 1, task));
                return false;
            } catch (Throwable t) {
                SafeLog.fine(LOG + "a payout could not reach its player's world: " + t.getMessage());
            }
        }
        SafeLog.info(LOG + "a payout could not reach its player's world thread, so it rolled with nobody to ask");
        task.accept(null);
        return true;
    }

    /** Whether {@code player} is connected (in a world or between worlds). */
    private static boolean connected(@Nonnull UUID player) {
        try {
            Universe universe = Universe.get();
            return universe != null && universe.getPlayer(player) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
