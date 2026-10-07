package com.ziggfreed.common.commerce.fold;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.ShopCatalog;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

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
    @Nullable private final String hostId;

    private ShopEntryOffer(@Nonnull ShopEntryAsset asset) {
        this.asset = asset;
        String id = asset.getId() == null ? "" : asset.getId();
        this.cost = CommerceFold.cost(asset.getCost(), id);
        this.rewards = CommerceFold.rewards(asset.rewardsOrEmpty());
        this.limits = CommerceFold.limits(asset.getLimits());
        this.hostId = null;
    }

    private ShopEntryOffer(@Nonnull ShopEntryOffer listed, @Nonnull String hostId) {
        this.asset = listed.asset;
        this.cost = listed.cost;
        this.rewards = listed.rewards;
        this.limits = listed.limits;
        this.hostId = hostId;
    }

    /** The engine view of {@code asset}. */
    @Nonnull
    public static ShopEntryOffer of(@Nonnull ShopEntryAsset asset) {
        return new ShopEntryOffer(asset);
    }

    /**
     * This offer as listed at {@code hostId}, a storefront that {@code Includes} its own: the same asset,
     * price, rewards, limits and id (so a purchase counts the same wherever it is made), with its storefront
     * presence and lock read off the host instead of its own storefront. Its own storefront is not asked, so
     * a stall switched off on its own still supplies every storefront that includes it. The offer's own
     * {@code Enabled}, {@code Requires} and {@code Season} still apply.
     */
    @Nonnull
    public ShopEntryOffer at(@Nonnull String hostId) {
        return new ShopEntryOffer(this, ShopCatalog.normalize(hostId));
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

    /**
     * On sale right now: the offer is available, and so is the storefront it stands in, which is the
     * host it is listed at for a view from {@link #at}, else its own. Read live, so a press on a page
     * drawn before the storefront was hidden refuses rather than sells. An offer naming a storefront
     * nothing defines answers by its own file; the audit names that storefront.
     */
    @Override
    public boolean enabled() {
        StorefrontAsset storefront = storefront();
        return asset.isAvailable() && (storefront == null || storefront.isAvailable());
    }

    /** The offer's own lock: its {@code Requires} with the hide axis taken out. */
    @Override
    @Nullable
    public GateSpec requires() {
        return asset.lockRequires();
    }

    /**
     * The lock of the storefront it stands in (the host, for a view from {@link #at}), read live; null
     * when there is none to ask.
     */
    @Override
    @Nullable
    public GateSpec storefrontRequires() {
        StorefrontAsset storefront = storefront();
        return storefront == null ? null : storefront.lockRequires();
    }

    /** The storefront this view answers presence and lock by: the host it is listed at, else its own. */
    @Nullable
    private StorefrontAsset storefront() {
        String shopId = hostId != null ? hostId : asset.getShop();
        return shopId == null ? null : ShopConfig.getInstance().resolve(shopId);
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
