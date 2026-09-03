package com.ziggfreed.common.objectives.flair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.flair.ZigFlairComponent;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The reward kind that unlocks a cosmetic flair:
 * {@code {"Kind": "Flair", "Params": {"Flair": "sawmill_gold"}}}.
 *
 * <p>It is UNPREFIXED because the library owns the record behind it: the per-player unlocked set is
 * {@link ZigFlairComponent}, persisted by the library so a mod that grants a flair and a mod that
 * renders one meet over one record. A namespace names the vocabulary's owner, so a mod prefix here
 * would be a false statement about who decides what a flair grant means.
 *
 * <p>It lives beside the {@code /zigflair} family rather than beside the component because a grant
 * is more than a set write: it is announced on the bus and drawn as a notice, and the module that
 * owns the component can see neither. Everything goes through {@link FlairUnlocks}, the one write
 * path, so a flair paid by a quest and one granted by an administrator are the same thing.
 *
 * <p><b>A flair the player already has is a successful no-op</b>: paying the same quest reward twice
 * (a repeatable, a retried grant) must not fail the payout, and nothing is announced for it.
 *
 * <p><b>A failed grant is replayable.</b> With no live player - the one way a grant fails on a live
 * server - {@link #retryCommand} answers the {@code /zigflair grant} line that would unlock the
 * same flair later, so the shared issuance pass hands it to the consumer's retry queue instead of
 * losing it. It is null for exactly the specs {@link #grant} refuses on their own terms (no id, an
 * id the save format cannot hold), because those would refuse again on every attempt.
 */
public final class FlairRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Flair";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /** The parameter naming which flair is unlocked. */
    static final String PARAM_FLAIR = "flair";

    /** The other spelling of that parameter, read the same way. */
    static final String PARAM_FLAIR_ID = "flairid";

    private FlairRewardKind() {
    }

    /** Register the flair kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new FlairRewardKind());
    }

    /**
     * Which flair {@code spec} unlocks, in either spelling, trimmed; empty when it names none.
     *
     * <p>Public because the chip painted for a flair reward has to read the same parameter the
     * payout does; two readers disagreeing is how a screen promises a flair the grant then refuses.
     */
    @Nonnull
    public static String flairOf(@Nonnull RewardSpec spec) {
        String current = spec.paramOr(PARAM_FLAIR, "").trim();
        return current.isEmpty() ? spec.paramOr(PARAM_FLAIR_ID, "").trim() : current;
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        String flairId = flairOf(spec);
        if (flairId.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND
                    + "' named no flair - it needs a 'Flair' parameter");
        }
        if (ZigFlairComponent.usesReservedDelimiter(flairId)) {
            throw new IllegalStateException("'" + flairId + "' is not a usable flair id: it carries"
                    + " '|' or ':', which the per-player save format reserves");
        }
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            throw new IllegalStateException("no live player to unlock the flair '" + flairId + "' for");
        }
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            throw new IllegalStateException("the player unlocking the flair '" + flairId
                    + "' has no live reference on their entity");
        }
        FlairUnlocks.Outcome outcome = FlairUnlocks.unlock(store, ref, playerRef, flairId);
        switch (outcome) {
            case NO_RECORD -> throw new IllegalStateException("the player carries no "
                    + ZigFlairComponent.REGISTRY_ID + " record to unlock the flair '" + flairId
                    + "' on - the component did not register, or was not attached at connect");
            case REFUSED -> throw new IllegalStateException("the flair id '" + flairId
                    + "' was refused at the write");
            default -> {
                // UNLOCKED or ALREADY_UNLOCKED: both are the reward delivered.
            }
        }
    }

    /**
     * The admin line that would unlock the same flair later, built from the SPEC so a queued retry
     * hands over exactly what the content authored. Null for a spec the grant refuses on its own
     * terms - nothing named, or an id the save format cannot hold - so it is reported lost rather
     * than parked in a queue that would refuse it again on every attempt. Whether any loaded content
     * NAMES the flair is deliberately not checked: the pack that ships it may simply not have
     * loaded yet, and the command warns about that once, where it is visible.
     */
    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                               @Nonnull String sourceId) {
        String flairId = flairOf(spec);
        if (flairId.isEmpty() || ZigFlairComponent.usesReservedDelimiter(flairId)) {
            return null;
        }
        return FlairCommandLine.grant(subject.name(), flairId);
    }
}
