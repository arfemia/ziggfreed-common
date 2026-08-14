package com.ziggfreed.common.commerce.fold;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;

/**
 * One authored offer, as the purchase engine sees it.
 *
 * <p>It exists because the two halves of this module deliberately cannot see each other: the
 * authoring layer may not import an engine type, so {@link ShopEntryAsset} cannot implement
 * {@link ShopOffer} itself. This is the join, and it is the ONLY place the two shapes meet.
 *
 * <p><b>It is a VIEW, not a copy.</b> The source asset is held rather than discarded, so a caller
 * that needs what a purchase does not - a title key, an icon, a shelf label - reads it off
 * {@link #asset()} instead of a second, drifting mirror. The half a purchase asks about is folded
 * once, at construction, and the object is discarded and rebuilt whenever the catalogue is: an
 * offer's price and its authored price cannot disagree, because the two only ever exist together.
 */
public final class ShopEntryOffer implements ShopOffer {

    private final ShopEntryAsset asset;
    private final Cost cost;
    private final List<RewardSpec> rewards;
    private final PurchaseLimits limits;

    private ShopEntryOffer(@Nonnull ShopEntryAsset asset) {
        this.asset = asset;
        String id = asset.getId() == null ? "" : asset.getId();
        this.cost = CommerceFold.cost(asset.getCost(), id);
        this.rewards = CommerceFold.rewards(asset.rewardsOrEmpty());
        this.limits = CommerceFold.limits(asset.getLimits());
    }

    /** The engine view of {@code asset}. */
    @Nonnull
    public static ShopEntryOffer of(@Nonnull ShopEntryAsset asset) {
        return new ShopEntryOffer(asset);
    }

    /** What the author wrote, for everything a purchase does not ask about. */
    @Nonnull
    public ShopEntryAsset asset() {
        return asset;
    }

    /** The rotating shelf this offer may be drawn onto, or null when it always stands on the page. */
    @Nullable
    public String poolId() {
        return asset.getPool() == null ? null : asset.getPool().getId();
    }

    @Override
    @Nonnull
    public String offerId() {
        return asset.getId() == null ? "" : asset.getId();
    }

    @Override
    @Nonnull
    public Cost cost() {
        return cost;
    }

    @Override
    @Nonnull
    public List<RewardSpec> rewards() {
        return rewards;
    }

    @Override
    public boolean enabled() {
        return asset.isEnabled();
    }

    @Override
    @Nullable
    public GateSpec requires() {
        return asset.getRequires();
    }

    @Override
    @Nullable
    public PurchaseLimits limits() {
        return limits.isOpen() ? null : limits;
    }

    @Override
    @Nullable
    public String poolTier() {
        return asset.getPool() == null ? null : asset.getPool().getTier();
    }

    @Override
    public double poolWeight() {
        return asset.getPool() == null ? 1.0 : asset.getPool().weightOrOne();
    }

    @Override
    public String toString() {
        return "ShopEntryOffer[" + offerId() + "]";
    }
}
