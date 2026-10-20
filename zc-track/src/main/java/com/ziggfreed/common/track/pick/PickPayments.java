package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.cost.ItemCost;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a pick service charges (a saved build's swap, a respec), through the library's one price: the track's authored
 * {@link CostAsset}, folded by {@link CommerceFold#cost} and checked, drained and refunded by
 * {@link CommerceEngines#costs()}, so it is payable in whatever wallet or item the owner names (R733, R736). Refund
 * the receipt a charge hands back, never the price. World thread.
 */
final class PickPayments {

    /** A charge that went through: who paid and what was really taken, to give back if the write fails. */
    record Paid(@Nullable Subject subject, @Nonnull CostEngine.Receipt receipt) {

        /** Nothing was charged (a free price). */
        static final Paid FREE = new Paid(null, CostEngine.Receipt.FREE);

        /** Give back what this charge took. */
        void refund() {
            if (subject == null || !receipt.ok() || receipt.paid().isFree()) {
                return;
            }
            try {
                CommerceEngines.costs().refund(subject, receipt);
            } catch (Throwable t) {
                SafeLog.warn("[track] a refund of " + subject.name() + "'s payment could not be made: "
                        + t.getMessage());
            }
        }
    }

    private PickPayments() {
    }

    /**
     * Charge {@code price} to the player at {@code ref}: {@link Paid#FREE} for a free price, the charge when it went
     * through, null when it cannot be paid (nothing taken).
     *
     * @param source what the price is for, named in a line about a bad price
     */
    @Nullable
    static Paid charge(@Nonnull Ref<EntityStore> ref, @Nonnull CostAsset price, @Nonnull String source) {
        return charge(ref, CommerceFold.cost(price, source), source);
    }

    /**
     * What a respec of {@code def} costs at the best level {@code bestLevel}: its {@code Respec.Cost} (else its
     * category's, else free), paid {@link TrackDef#respecTimes} times, so a price written to scale with the level
     * does (R665).
     */
    @Nonnull
    static Cost respecPrice(@Nonnull TrackDef def, int bestLevel) {
        return times(CommerceFold.cost(def.respecCost(), "the respec of " + def.id()), def.respecTimes(bestLevel));
    }

    /** {@code cost} paid {@code times} times: every wallet amount and item count multiplied, its Combine kept. */
    @Nonnull
    static Cost times(@Nonnull Cost cost, int times) {
        if (times <= 1 || cost.isFree()) {
            return cost;
        }
        Map<String, Long> currencies = new LinkedHashMap<>();
        cost.currencies().forEach((id, amount) -> currencies.put(id, multiply(amount, times)));
        List<ItemCost> items = new ArrayList<>();
        for (ItemCost item : cost.items()) {
            items.add(item.withCount((int) Math.min(Integer.MAX_VALUE, multiply(item.count(), times))));
        }
        return Cost.of(cost.combine(), currencies, items, null);
    }

    private static long multiply(long amount, int times) {
        try {
            return Math.multiplyExact(amount, (long) times);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** {@link #charge(Ref, CostAsset, String)} for a price already folded. */
    @Nullable
    static Paid charge(@Nonnull Ref<EntityStore> ref, @Nonnull Cost cost, @Nonnull String source) {
        if (cost.isFree()) {
            return Paid.FREE;
        }
        try {
            Store<EntityStore> store = ref.getStore();
            Subject subject = Subject.of(store.getComponent(ref, PlayerRef.getComponentType()),
                    store.getComponent(ref, Player.getComponentType()));
            if (subject == null) {
                return null;
            }
            CostEngine.Receipt receipt = CommerceEngines.costs().drain(subject, cost);
            return receipt.ok() ? new Paid(subject, receipt) : null;
        } catch (Throwable t) {
            SafeLog.warn("[track] the price of " + source + " could not be charged: " + t.getMessage());
            return null;
        }
    }
}
