package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A reputation's payouts past its top: parked on the player's {@link ReputationComponent} as earned standing
 * crosses each multiple of Every, and paid when the player presses Collect on the Reputation page. A
 * reputation authored {@code Collect: false} pays each crossing on the spot instead, as a payout through the
 * same grant. World thread throughout: a change can land inside a quest's own payout or on a later world task
 * for a kill, and a press runs in the page's event handler.
 */
public final class ReputationBeyond {

    /** What every Beyond payout is labelled in logs and retry commands: {@code reputation:<id>}. */
    public static final String SOURCE_PREFIX = "reputation:";

    /** How a Collect press ended. */
    public enum Status {
        /** Paid {@link Collected#paid()} payouts; {@link Collected#left()} still wait. */
        PAID,
        /** The bag has no room for the items one press would hand over; nothing paid, nothing taken off. */
        NO_ROOM,
        /** Nothing waits (or the reputation is off or pays no Beyond now): nothing to do. */
        NOTHING,
        /** Nothing could be handed over or queued; nothing taken off. */
        FAILED
    }

    /** One press's outcome: its status, how many payouts it paid, how many still wait, what was handed over. */
    public record Collected(@Nonnull Status status, int paid, long left, @Nonnull List<RewardSpec> receipt) {

        public Collected {
            receipt = List.copyOf(receipt);
        }

        static Collected of(@Nonnull Status status, long left) {
            return new Collected(status, 0, left, List.of());
        }
    }

    private ReputationBeyond() {
    }

    /** The source a payout of {@code reputation} is labelled with. */
    @Nonnull
    public static String source(@Nonnull ReputationDef reputation) {
        return SOURCE_PREFIX + reputation.id();
    }

    /**
     * How many Beyond payouts wait for the player at {@code ref} with {@code reputation}: 0 when it pays no
     * Beyond now (the record is kept, unread), when the player carries no record, or when the read fails.
     */
    public static long owed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationDef reputation) {
        if (!reputation.hasBeyond()) {
            return 0L;
        }
        ReputationComponent record = ReputationComponent.of(store, ref);
        return record == null ? 0L : record.owed(reputation.id());
    }

    /**
     * Record {@code change} on the player's record (the high-water rule, {@link ReputationComponent#climb}, its
     * steps read from the top floor and the Every the change landed under) and answer how many payouts
     * it newly parked; 0 for a change below the top, a fall, a climb back to standing already reached, or a
     * ladder with no top. Answers -1 when there is nowhere to park (no record, or an id the save cannot hold),
     * so the caller pays on the spot rather than lose the crossing. The reputation's track then carries every step
     * through the highest standing as its Beyond mark ({@link ReputationTracks#markBeyond}): the steps parked, and
     * the steps the high-water rule settled unpaid (a standing an admin set, read as the change's before).
     */
    static long park(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationChange change) {
        ReputationComponent record = ReputationComponent.of(store, ref);
        String id = change.reputation().id();
        if (record == null || !ReputationComponent.keeps(id)) {
            return -1L;
        }
        Integer floor = change.beyondFloor();
        if (floor == null) {
            return 0L;
        }
        long parked = record.climbed(id, change.earnedBefore(), change.earnedAfter(), floor,
                change.reputation().beyondEvery());
        Long highest = record.highest(id);
        if (highest != null) {
            ReputationTracks.markBeyond(change.playerId(), change.reputation(), highest);
        }
        return parked;
    }

    /**
     * One Collect press for the player at {@code ref}: the reputation's Rewards as they are written now, once per
     * waiting payout, up to {@link ReputationComponent#MAX_AT_ONCE} (the rest keep waiting), through the same
     * grant every reward takes (a failed item is queued for the next connect). First the bag is asked whether it
     * takes every item the press would hand over (a Lootable needs no room); with no room nothing is paid and
     * nothing taken off. Only what was paid comes off the count.
     */
    @Nonnull
    public static Collected collect(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationDef reputation) {
        ReputationComponent record = ReputationComponent.of(store, ref);
        String id = reputation.id();
        long owed = record == null || !reputation.hasBeyond() ? 0L : record.owed(id);
        int times = ReputationComponent.perPress(owed);
        if (times <= 0) {
            return Collected.of(Status.NOTHING, owed);
        }
        Subject subject = subject(store, ref);
        if (subject == null) {
            SafeLog.warn("[reputation] nobody to pay the waiting Beyond rewards of '" + id + "' to");
            return Collected.of(Status.FAILED, owed);
        }
        List<RewardSpec> rewards = repeated(reputation.beyondRewards(), times);
        String source = source(reputation);
        if (!LootRewardKinds.canAddAll(rewards, subject, source)) {
            return Collected.of(Status.NO_ROOM, owed);
        }
        RewardGrants.GrantOutcome outcome;
        try {
            outcome = RewardGrants.grantAll(rewards, subject, source, RewardKinds.shared(), true,
                    ProgressionRuntime.rewardRetryQueue(), SafeLog::warn);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] collecting the Beyond rewards of '" + id + "' failed: " + t.getMessage());
            return Collected.of(Status.FAILED, owed);
        }
        if (!outcome.anyDelivered()) {
            return Collected.of(Status.FAILED, owed);
        }
        record.paid(id, times);
        return new Collected(Status.PAID, times, record.owed(id), outcome.receipt());
    }

    /**
     * Pay {@code crossings} of {@code reputation}'s Beyond on the spot, at most
     * {@link ReputationComponent#MAX_AT_ONCE} (a reputation authored {@code Collect: false}, or a crossing with
     * nowhere to park). No room check, as a payout on crossing never had one.
     */
    static void payNow(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ReputationDef reputation, int crossings) {
        List<RewardSpec> rewards = repeated(reputation.beyondRewards(),
                Math.min(crossings, ReputationComponent.MAX_AT_ONCE));
        if (rewards.isEmpty()) {
            return;
        }
        if (crossings > ReputationComponent.MAX_AT_ONCE) {
            SafeLog.warn("[reputation] one change to '" + reputation.id() + "' crossed " + crossings
                    + " Beyond payouts; only " + ReputationComponent.MAX_AT_ONCE + " are paid");
        }
        Subject subject = subject(store, ref);
        if (subject == null) {
            SafeLog.warn("[reputation] nobody to pay the Beyond rewards of '" + reputation.id() + "' to");
            return;
        }
        RewardGrants.grantAll(rewards, subject, source(reputation), RewardKinds.shared(), true,
                ProgressionRuntime.rewardRetryQueue(), SafeLog::warn);
    }

    /** {@code once}, {@code times} over, in order; empty for none. */
    @Nonnull
    static List<RewardSpec> repeated(@Nonnull List<RewardSpec> once, int times) {
        List<RewardSpec> out = new ArrayList<>(once.size() * Math.max(0, times));
        for (int i = 0; i < times; i++) {
            out.addAll(once);
        }
        return out;
    }

    /** Who a payout goes to: the runtime's quest subject for the player at {@code ref}, else the player. */
    @Nullable
    static Subject subject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
        if (subject != null) {
            return subject;
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : Subject.of(playerRef, store.getComponent(ref, Player.getComponentType()));
    }
}
