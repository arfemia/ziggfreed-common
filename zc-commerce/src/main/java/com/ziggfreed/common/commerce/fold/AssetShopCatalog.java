package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.shop.ShopCatalog;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;

/**
 * Which offers exist, and which rotating shelf each belongs to.
 *
 * <p><b>A SNAPSHOT, unlike the wallet catalog, and the reason is the generators.</b> An offer family
 * written as one file has to be EXPANDED before it exists at all, and that expansion needs the
 * consumer's registered value sources - so it happens once, when the layer changes, rather than on
 * every lookup. {@link #refresh} is the rebuild, and {@link CommerceCatalogs} is what calls it off
 * the load event.
 *
 * <p>Until the first refresh the catalogue is empty, which is the honest answer for a server whose
 * offer files have not been read yet: nothing is for sale rather than something arbitrary being on
 * the page.
 */
public final class AssetShopCatalog implements ShopCatalog {

    private static final AssetShopCatalog INSTANCE = new AssetShopCatalog();

    /** The one catalog over the authored offers. */
    @Nonnull
    public static AssetShopCatalog getInstance() {
        return INSTANCE;
    }

    /** One rebuild's whole answer, swapped in as a unit so no reader sees a half-built catalogue. */
    private record Snapshot(@Nonnull Map<String, ShopEntryOffer> byId,
                            @Nonnull Map<String, List<ShopOffer>> byPool) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    }

    @Nonnull
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private AssetShopCatalog() {
    }

    /**
     * Fold the offer store - authored files plus everything the generators write - and replace the
     * catalogue with the result.
     *
     * @param values where an axis naming a {@code Source} gets its rows; null means none are
     *               registered, which the store's own findings say rather than silently writing
     *               nothing
     */
    public void refresh(@Nullable GeneratorCore.AxisValueSource values) {
        Map<String, ShopEntryAsset> folded = ShopAssetStore.getInstance().resolveAll(values);
        Map<String, ShopEntryOffer> byId = new LinkedHashMap<>();
        Map<String, List<ShopOffer>> byPool = new LinkedHashMap<>();
        for (Map.Entry<String, ShopEntryAsset> entry : folded.entrySet()) {
            ShopEntryAsset asset = entry.getValue();
            if (asset == null) {
                continue;
            }
            ShopEntryOffer offer = ShopEntryOffer.of(asset);
            byId.put(ShopCatalog.normalize(offer.offerId()), offer);
            String pool = offer.poolId();
            if (pool != null && !pool.isBlank()) {
                byPool.computeIfAbsent(pool.trim().toLowerCase(Locale.ROOT), id -> new ArrayList<>())
                        .add(offer);
            }
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(byId), Collections.unmodifiableMap(byPool));
    }

    @Override
    @Nullable
    public ShopOffer offer(@Nonnull String offerId) {
        return snapshot.byId().get(ShopCatalog.normalize(offerId));
    }

    @Override
    @Nonnull
    public Collection<ShopOffer> poolCandidates(@Nonnull String poolId) {
        return snapshot.byPool().getOrDefault(ShopCatalog.normalize(poolId), List.of());
    }

    /** Every offer on sale, in id order. What a storefront page lists before it groups them. */
    @Nonnull
    public Collection<ShopEntryOffer> offers() {
        return snapshot.byId().values();
    }

    /** The offers a storefront sells, by that storefront's id, matched case-insensitively. */
    @Nonnull
    public List<ShopEntryOffer> offersOf(@Nonnull String shopId) {
        String wanted = ShopCatalog.normalize(shopId);
        List<ShopEntryOffer> out = new ArrayList<>();
        for (ShopEntryOffer offer : snapshot.byId().values()) {
            if (wanted.equals(offer.asset().getShop())) {
                out.add(offer);
            }
        }
        return out;
    }
}
