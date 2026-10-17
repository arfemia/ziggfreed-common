package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.instance.reward.GrantOutcome;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimCollectedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A Claim press, lifted from Kweebec Nightmare's {@code claimPending}: take the Claim out of the store, hand its
 * rewards over through {@link InstanceRewardGranter#grantAll} with the bag guard (an item goes only when all of
 * it fits), and put back what did not fit, so a full bag keeps the rest waiting. A reward the sink could not pay
 * (a currency on a server with none) is not put back, as Kweebec's never was; it is logged. What a press handed
 * over is announced, Claim by Claim, as a {@link ClaimCollectedEvent} once the rest is back in the store.
 *
 * <p>World thread only (the grant touches the player's inventory).
 */
public final class ClaimGrants {

    private static final NativeEventSeam EVENTS = new NativeEventSeam("[claims]");

    /**
     * What a press did: how many Claims it took, how many of those it emptied, and how many rewards it handed
     * over, kept back for a full bag, or could not pay.
     */
    public record Result(int taken, int emptied, int granted, int kept, int failed) {

        /** Nothing was there to take (a stale page, a second press). */
        public static final Result NOTHING = new Result(0, 0, 0, 0, 0);

        /** True when the press found nothing to take. */
        public boolean nothing() {
            return taken == 0;
        }

        /** True when a full bag kept something waiting. */
        public boolean anyKept() {
            return kept > 0;
        }

        @Nonnull
        Result plus(@Nonnull Result other) {
            return new Result(taken + other.taken, emptied + other.emptied, granted + other.granted,
                    kept + other.kept, failed + other.failed);
        }
    }

    private ClaimGrants() {
    }

    /** Take {@code player}'s Claim {@code claimId} and hand it over to the player behind {@code ref}. */
    @Nonnull
    public static Result claim(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String claimId,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        Claim claim = claims.take(player, claimId);
        return claim == null ? Result.NOTHING : grant(claims, player, claim, ref, store, sink);
    }

    /** Take every Claim {@code player} has waiting, oldest first, and hand each over in turn. */
    @Nonnull
    public static Result claimAll(@Nonnull ClaimStore claims, @Nonnull UUID player,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        Result total = Result.NOTHING;
        List<Claim> all = claims.takeAll(player);
        for (Claim claim : all) {
            total = total.plus(grant(claims, player, claim, ref, store, sink));
        }
        return total;
    }

    /**
     * Take every Claim the fight {@code fight} paid {@code player} ({@link Claim#fromFight}), oldest first, and hand
     * each over in turn: a spoils chest's Take All. Every other Claim stays where it waits.
     */
    @Nonnull
    public static Result claimFight(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String fight,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        Result total = Result.NOTHING;
        for (Claim listed : claims.list(player)) {
            Claim claim = listed.fromFight(fight) ? claims.take(player, listed.id()) : null;
            if (claim != null) {
                total = total.plus(grant(claims, player, claim, ref, store, sink));
            }
        }
        return total;
    }

    /**
     * What a spoils chest's page paid ({@link #claimFightExceptItems}): the press's tally of it, and every reward it
     * really handed over, in the order the Claims held them (never one that went back to wait).
     */
    public record Paid(@Nonnull Result result, @Nonnull List<InstanceReward> rewards) {

        /** Nothing paid. */
        public static final Paid NOTHING = new Paid(Result.NOTHING, List.of());
    }

    /**
     * A spoils chest page's Take All: for every Claim the fight {@code fight} paid {@code player}, hand over what is
     * not an item (a currency, a command line such as an experience or reputation token) through the same grant a
     * press makes, and put the Claim back holding its items alone, which the chest's window then offers. A Claim of
     * items alone is not touched. What a non-item could not pay is dropped and logged, as a press drops it; what a
     * throw put back waits in its Claim, and only what was handed over is listed as paid.
     */
    @Nonnull
    public static Paid claimFightExceptItems(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String fight,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        Result total = Result.NOTHING;
        List<InstanceReward> paid = new ArrayList<>();
        for (Claim listed : claims.list(player)) {
            if (listed.fromFight(fight)) {
                total = total.plus(payExceptItems(claims, player, listed.id(), ref, store, sink, paid));
            }
        }
        return new Paid(total, List.copyOf(paid));
    }

    /**
     * A spoils chest page's Claim: {@link #claimFightExceptItems} for {@code player}'s one Claim {@code claimId},
     * handing over what is not an item and putting its items back for the chest's window.
     */
    @Nonnull
    public static Result claimExceptItems(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String claimId,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        return payExceptItems(claims, player, claimId, ref, store, sink, null);
    }

    /** Hand over what of the Claim {@code claimId} is not an item, its items put back; nothing for items alone. */
    @Nonnull
    private static Result payExceptItems(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String claimId,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink,
            @Nullable List<InstanceReward> paid) {
        Claim listed = null;
        for (Claim held : claims.list(player)) {
            if (held.is(claimId)) {
                listed = held;
                break;
            }
        }
        if (listed == null || !hasNonItem(listed)) {
            return Result.NOTHING;
        }
        Claim claim = claims.take(player, listed.id());
        if (claim == null) {
            return Result.NOTHING;
        }
        List<InstanceReward> items = new ArrayList<>();
        List<InstanceReward> others = new ArrayList<>();
        for (InstanceReward reward : claim.rewards()) {
            (reward.isItem() ? items : others).add(reward);
        }
        Result result = grant(claims, player, claim.withRewards(others), ref, store, sink, paid);
        if (!items.isEmpty()) {
            claims.putBack(player, claim.withRewards(items));
        }
        return result;
    }

    /** Whether {@code claim} holds a reward that is not an item (a currency, an experience or reputation token). */
    public static boolean hasNonItem(@Nonnull Claim claim) {
        return claim.rewards().stream().anyMatch(reward -> !reward.isItem());
    }

    /** {@link #grant(ClaimStore, UUID, Claim, Ref, Store, InstanceRewardGranter.Sink, List)}, keeping no list. */
    @Nonnull
    private static Result grant(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull Claim claim,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        return grant(claims, player, claim, ref, store, sink, null);
    }

    /**
     * Hand one taken Claim over, putting back what did not fit, then announce what it delivered, which
     * {@code handedOver} (when given) also receives; a throw puts the whole Claim back and hands nothing over.
     */
    @Nonnull
    private static Result grant(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull Claim claim,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink,
            @Nullable List<InstanceReward> handedOver) {
        GrantOutcome outcome;
        List<InstanceReward> delivered = new ArrayList<>();
        try {
            outcome = InstanceRewardGranter.grantAll(claim.rewards(), ref, store, sink, claim.source(),
                    delivered::add);
        } catch (Throwable t) {
            claims.putBack(player, claim);
            SafeLog.warn("[claims] the Claim '" + claim.id() + "' could not be handed over, so it waits", t);
            return new Result(1, 0, 0, claim.rewards().size(), 0);
        }
        if (!outcome.pending().isEmpty()) {
            claims.putBack(player, claim.withRewards(outcome.pending()));
        }
        if (outcome.failed() > 0) {
            SafeLog.warn("[claims] " + outcome.failed() + " reward(s) of the Claim '" + claim.id()
                    + "' could not be paid on this server and were dropped");
        }
        if (handedOver != null) {
            handedOver.addAll(delivered);
        }
        announceCollected(player, ref, claim, delivered);
        return new Result(1, outcome.pending().isEmpty() ? 1 : 0, outcome.granted(), outcome.blocked(),
                outcome.failed());
    }

    /**
     * Announce that {@code player} collected {@code delivered} of {@code claim} ({@link ClaimCollectedEvent}): the
     * one place a press and a spoils chest's window both announce it. Nothing for an empty delivery; never throws.
     */
    static void announceCollected(@Nonnull UUID player, @Nonnull Ref<EntityStore> ref, @Nonnull Claim claim,
            @Nonnull List<InstanceReward> delivered) {
        if (!delivered.isEmpty()) {
            EVENTS.fire("ClaimCollected", ClaimCollectedEvent.class,
                    () -> new ClaimCollectedEvent(player, ref, claim, delivered));
        }
    }
}
