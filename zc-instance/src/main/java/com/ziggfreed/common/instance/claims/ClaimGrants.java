package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.instance.reward.DropRarity;
import com.ziggfreed.common.instance.reward.GrantOutcome;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimCollectedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A Claim press, lifted from Kweebec Nightmare's {@code claimPending}: take the Claim out of the store, hand its
 * rewards over through {@link InstanceRewardGranter#grantAllLanded} with the bag guard (an item goes only when all of
 * it fits), and put back what did not fit, so a full bag keeps the rest waiting. A reward the sink could not pay
 * (a currency on a server with none) is not put back, as Kweebec's never was; it is logged. What a press handed
 * over is announced, Claim by Claim, as a {@link ClaimCollectedEvent} once the rest is back in the store, and
 * listed on the press's {@link Result} with what each reward landed as ({@link Collected}), which the page's
 * toast names. A press may offer only part of a Claim (a reward chest's one line, {@link #claimReward}): the rest of
 * it goes back where it stood.
 *
 * <p>World thread only (the grant touches the player's inventory).
 */
public final class ClaimGrants {

    private static final NativeEventSeam EVENTS = new NativeEventSeam("[claims]");

    /**
     * What one Claim of a press handed over: the Claim, and each reward it handed over with what that landed as
     * ({@link InstanceRewardGranter.Landed}: the receipt of the kind that paid it, or none for a row that paid by
     * itself), in the order the Claim held them. Never a reward that went back to wait.
     */
    public record Collected(@Nonnull Claim claim, @Nonnull List<InstanceRewardGranter.Landed> landed) {

        public Collected {
            landed = List.copyOf(landed);
        }
    }

    /**
     * What a press did: how many Claims it took, how many of those it emptied, how many rewards it handed over,
     * kept back for a full bag, or could not pay, and what each Claim it took handed over ({@code collected}, a
     * Claim that handed nothing over left out), in the order it took them.
     */
    public record Result(int taken, int emptied, int granted, int kept, int failed,
            @Nonnull List<Collected> collected) {

        /** Nothing was there to take (a stale page, a second press). */
        public static final Result NOTHING = new Result(0, 0, 0, 0, 0);

        public Result {
            collected = List.copyOf(collected);
        }

        /** A tally that lists nothing handed over. */
        public Result(int taken, int emptied, int granted, int kept, int failed) {
            this(taken, emptied, granted, kept, failed, List.of());
        }

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
            List<Collected> both = new ArrayList<>(collected);
            both.addAll(other.collected);
            return new Result(taken + other.taken, emptied + other.emptied, granted + other.granted,
                    kept + other.kept, failed + other.failed, both);
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
     * A reward chest's line ({@link RewardChestPage}): of {@code player}'s Claim {@code claimId}, hand over the one
     * reward {@code rewardKey} names ({@link #rewardKey}; the first of two alike) through the same grant a press
     * makes, so an item goes only when all of it fits, and leave the rest of the Claim where it stands. Nothing for a
     * blank key, or when the Claim or the reward is gone (a stale page).
     */
    @Nonnull
    public static Result claimReward(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String claimId,
            @Nullable String rewardKey, @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull InstanceRewardGranter.Sink sink) {
        if (rewardKey == null || rewardKey.isBlank()) {
            return Result.NOTHING;
        }
        String key = rewardKey.trim();
        return payWhere(claims, player, claimId, rewards -> {
            BitSet one = new BitSet();
            for (int i = 0; i < rewards.size(); i++) {
                if (key.equals(rewardKey(rewards.get(i)))) {
                    one.set(i);
                    break;
                }
            }
            return one;
        }, ref, store, sink);
    }

    /**
     * What names one reward of a Claim on a page's line, for {@link #claimReward}: its kind, id, quantity, display
     * key and the rarity its drop reads as ({@link DropRarity}) folded into a short token, so a line never carries a
     * command reward's console line to the client. Two rewards alike in all five share it, and either one pays the
     * same and shows the same. Stable for the server's life, never saved.
     */
    @Nonnull
    public static String rewardKey(@Nonnull InstanceReward reward) {
        String identity = reward.kind().name() + '\n' + reward.id() + '\n' + reward.quantity() + '\n'
                + (reward.displayKey() == null ? "" : reward.displayKey()) + '\n'
                + Objects.toString(DropRarity.qualityOf(reward.dropChance()), "");
        return reward.kind().name().toLowerCase(Locale.ROOT) + "-" + Integer.toHexString(identity.hashCode());
    }

    /**
     * Hand over the rewards of {@code player}'s Claim {@code claimId} that {@code pick} names, by their place in the
     * Claim, through the same grant a press makes, and put back what did not fit beside every reward it did not name,
     * each where it stood ({@link #inPlace}); nothing when the Claim is gone or {@code pick} names none of its
     * rewards.
     */
    @Nonnull
    private static Result payWhere(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String claimId,
            @Nonnull Function<List<InstanceReward>, BitSet> pick, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        Claim listed = held(claims, player, claimId);
        if (listed == null || pick.apply(listed.rewards()).isEmpty()) {
            return Result.NOTHING;
        }
        Claim claim = claims.take(player, listed.id());
        if (claim == null) {
            return Result.NOTHING;
        }
        List<InstanceReward> rewards = claim.rewards();
        BitSet picked = pick.apply(rewards);
        List<InstanceReward> offered = new ArrayList<>();
        for (int i = picked.nextSetBit(0); i >= 0 && i < rewards.size(); i = picked.nextSetBit(i + 1)) {
            offered.add(rewards.get(i));
        }
        if (offered.isEmpty()) {
            // It changed between the read and the take: back as it was, nothing paid.
            claims.putBack(player, claim);
            return Result.NOTHING;
        }
        return grant(claims, player, claim.withRewards(offered), left -> inPlace(rewards, picked, left), ref, store,
                sink);
    }

    /**
     * What goes back of a Claim holding {@code rewards} once a press offered those {@code offered} names: each reward
     * it did not offer, and each offered one that came back ({@code left}: what did not fit, or everything on a
     * throw), all in the order the Claim held them; a reward left that matches none (never today) goes last.
     */
    @Nonnull
    private static List<InstanceReward> inPlace(@Nonnull List<InstanceReward> rewards, @Nonnull BitSet offered,
            @Nonnull List<InstanceReward> left) {
        List<InstanceReward> unplaced = new ArrayList<>(left);
        List<InstanceReward> out = new ArrayList<>(rewards.size());
        for (int i = 0; i < rewards.size(); i++) {
            InstanceReward reward = rewards.get(i);
            if (!offered.get(i)) {
                out.add(reward);
                continue;
            }
            int at = unplaced.indexOf(reward);
            if (at >= 0) {
                out.add(unplaced.remove(at));
            }
        }
        out.addAll(unplaced);
        return out;
    }

    /** {@code player}'s Claim {@code claimId} as the store lists it, or null when they hold none by that id. */
    @Nullable
    private static Claim held(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String claimId) {
        for (Claim held : claims.list(player)) {
            if (held.is(claimId)) {
                return held;
            }
        }
        return null;
    }

    /** {@link #grant(ClaimStore, UUID, Claim, UnaryOperator, Ref, Store, InstanceRewardGranter.Sink)} whole. */
    @Nonnull
    private static Result grant(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull Claim claim,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        return grant(claims, player, claim, UnaryOperator.identity(), ref, store, sink);
    }

    /**
     * Hand one taken Claim over, putting back what did not fit, then announce what it delivered; a throw puts back
     * everything it was handed and hands nothing over. {@code back} turns what comes back into the Claim's whole list
     * to put back: itself for a whole Claim, the rest of the Claim around it for a press that offered only part of one
     * ({@link #payWhere}).
     */
    @Nonnull
    private static Result grant(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull Claim claim,
            @Nonnull UnaryOperator<List<InstanceReward>> back, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        GrantOutcome outcome;
        List<InstanceRewardGranter.Landed> landed = new ArrayList<>();
        try {
            outcome = InstanceRewardGranter.grantAllLanded(claim.rewards(), ref, store, sink, claim.source(),
                    landed::add);
        } catch (Throwable t) {
            claims.putBack(player, claim.withRewards(back.apply(claim.rewards())));
            SafeLog.warn("[claims] the Claim '" + claim.id() + "' could not be handed over, so it waits", t);
            return new Result(1, 0, 0, claim.rewards().size(), 0);
        }
        List<InstanceReward> kept = back.apply(outcome.pending());
        if (!kept.isEmpty()) {
            claims.putBack(player, claim.withRewards(kept));
        }
        if (outcome.failed() > 0) {
            SafeLog.warn("[claims] " + outcome.failed() + " reward(s) of the Claim '" + claim.id()
                    + "' could not be paid on this server and were dropped");
        }
        List<InstanceReward> delivered = new ArrayList<>(landed.size());
        for (InstanceRewardGranter.Landed each : landed) {
            delivered.add(each.row());
        }
        // What the press handed over, announced once per Claim; nothing for an empty delivery, and never throws.
        if (!delivered.isEmpty()) {
            EVENTS.fire("ClaimCollected", ClaimCollectedEvent.class,
                    () -> new ClaimCollectedEvent(player, ref, claim, delivered));
        }
        return new Result(1, kept.isEmpty() ? 1 : 0, outcome.granted(), outcome.blocked(),
                outcome.failed(), landed.isEmpty() ? List.of() : List.of(new Collected(claim, landed)));
    }
}
