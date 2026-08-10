package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.inventory.InventoryUtil;

/**
 * Grants a list of {@link InstanceReward}s to one player with the BLOCK-FIRST
 * full-inventory guard (the user's "players cannot claim rewards with a full inventory"
 * rule, mirroring hyMMO's paradigm): an {@code ITEM} reward is granted ONLY if it all
 * fits ({@link InventoryUtil#canFit}); otherwise it is NOT delivered and is returned in
 * {@link GrantOutcome#pending()} for the caller to hold / re-claim. Currency and command
 * rewards have no space guard and run through the consumer-supplied {@link Sink} (so
 * common imports no currency engine). Non-throwing, isolate-each.
 *
 * <p><b>World thread only</b> (item grants touch the {@link Store}). Each reward is
 * guarded; one failing reward never aborts the rest.
 */
public final class InstanceRewardGranter {

    /** The consumer's executor for the non-item reward kinds (common knows no currency/command engine). */
    public interface Sink {
        /** Grant a currency/token reward. Return true if granted. */
        boolean grantCurrency(@Nonnull String currencyId, int amount, @Nonnull PlayerRef player,
                              @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);

        /**
         * Run a console command reward. The granter has already substituted the {@code {amount}}
         * placeholder from the reward quantity; the consumer substitutes any {@code {player}} placeholder
         * (from {@code player.getUsername()}). Return true if run.
         */
        boolean runCommand(@Nonnull String command, @Nonnull PlayerRef player,
                           @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
    }

    private InstanceRewardGranter() {
    }

    /**
     * Grant {@code rewards} to {@code player}. Item rewards that do not fit are skipped
     * and collected into {@link GrantOutcome#pending()} (never partially delivered).
     *
     * @param sink the consumer executor for currency/command rewards, or {@code null}
     *             (then those kinds count as failed)
     */
    @Nonnull
    public static GrantOutcome grantAll(@Nonnull List<InstanceReward> rewards, @Nonnull PlayerRef player,
                                        @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                        @Nullable Sink sink) {
        int granted = 0;
        int blocked = 0;
        int failed = 0;
        List<InstanceReward> pending = new ArrayList<>();

        for (InstanceReward r : rewards) {
            try {
                switch (r.kind()) {
                    case ITEM -> {
                        if (!InventoryUtil.canFit(store, ref, r.id(), r.quantity())) {
                            blocked++;
                            pending.add(r);
                        } else if (InventoryUtil.give(store, ref, r.id(), r.quantity()) > 0) {
                            // Raced full between the check and the give: treat as blocked, not partial.
                            blocked++;
                            pending.add(r);
                        } else {
                            granted++;
                        }
                    }
                    case CURRENCY -> {
                        if (sink != null && sink.grantCurrency(r.id(), r.quantity(), player, ref, store)) {
                            granted++;
                        } else {
                            failed++;
                        }
                    }
                    case COMMAND -> {
                        // Substitute the {amount} placeholder from the reward quantity before the sink
                        // runs; the consumer's sink substitutes {player}. Common stays currency/XP-agnostic.
                        String command = r.id().replace("{amount}", Integer.toString(r.quantity()));
                        if (sink != null && sink.runCommand(command, player, ref, store)) {
                            granted++;
                        } else {
                            failed++;
                        }
                    }
                    default -> failed++;
                }
            } catch (Throwable t) {
                failed++;
            }
        }
        return new GrantOutcome(granted, blocked, failed, pending);
    }
}
