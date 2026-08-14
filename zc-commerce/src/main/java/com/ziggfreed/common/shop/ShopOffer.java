package com.ziggfreed.common.shop;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * What the purchase engine needs to know about one thing for sale. The seam between the ENGINE and
 * whatever authored the offer, so the engine never learns about an asset type, a store, or a file.
 *
 * <p>It is an interface with defaults rather than a record, for the same reason the objective index
 * takes accessors rather than an owner type: an authored offer carries far more than a purchase
 * needs (a title, an icon, a category, an order, whatever else its schema grew), and mapping it
 * into a second object every time somebody looks at a shop is a copy waiting to fall out of step.
 * The authoring layer's own type implements this and answers the six questions a purchase asks.
 *
 * <p>Every leaf but the id and the price has a default, so a new question can be added here without
 * every implementation having to answer it.
 */
public interface ShopOffer {

    /** The id purchases are recorded against. Stable across restarts, because the counts are. */
    @Nonnull
    String offerId();

    /** What it costs. {@link Cost#FREE} for something given away. */
    @Nonnull
    Cost cost();

    /** What the buyer receives, paid out through the shared reward vocabulary. */
    @Nonnull
    List<RewardSpec> rewards();

    /** False takes the offer out of the shop entirely, without deleting the file. */
    default boolean enabled() {
        return true;
    }

    /**
     * Who may buy it, as the ONE shared requirement block every gated thing in the library uses. A
     * null block asks for nothing.
     */
    @Nullable
    default GateSpec requires() {
        return null;
    }

    /** How often one buyer may take it. Null limits nothing. */
    @Nullable
    default PurchaseLimits limits() {
        return null;
    }

    /**
     * The grade this offer carries for a rotating pool that slots by one. Null makes it eligible
     * only for a slot that accepts anything.
     */
    @Nullable
    default String poolTier() {
        return null;
    }

    /** The extra tag a rotating pool's second filter axis matches on. Null means untagged. */
    @Nullable
    default String poolTag() {
        return null;
    }

    /** How strongly a weighted draw favours this offer. Zero or less reads as one. */
    default double poolWeight() {
        return 1.0;
    }
}
