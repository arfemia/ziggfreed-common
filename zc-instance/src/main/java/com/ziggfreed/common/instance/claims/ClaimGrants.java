package com.ziggfreed.common.instance.claims;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.GrantOutcome;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A Claim press, lifted from Kweebec Nightmare's {@code claimPending}: take the Claim out of the store, hand its
 * rewards over through {@link InstanceRewardGranter#grantAll} with the bag guard (an item goes only when all of
 * it fits), and put back what did not fit, so a full bag keeps the rest waiting. A reward the sink could not pay
 * (a currency on a server with none) is not put back, as Kweebec's never was; it is logged.
 *
 * <p>World thread only (the grant touches the player's inventory).
 */
public final class ClaimGrants {

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

    /** Hand one taken Claim over, putting back what did not fit; a throw puts the whole Claim back. */
    @Nonnull
    private static Result grant(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull Claim claim,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull InstanceRewardGranter.Sink sink) {
        GrantOutcome outcome;
        try {
            outcome = InstanceRewardGranter.grantAll(claim.rewards(), ref, store, sink);
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
        return new Result(1, outcome.pending().isEmpty() ? 1 : 0, outcome.granted(), outcome.blocked(),
                outcome.failed());
    }
}
