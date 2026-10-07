package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.shop.ShopCatalog;
import com.ziggfreed.common.shop.ShopOffer;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.StorefrontIncludes;

/**
 * What one storefront's PAGE shows and sells, its {@code Includes} folded in: the one reader of them, so an
 * included stall reads the same at every storefront that lists it.
 *
 * <p>Every list here walks {@link StorefrontIncludes#chain}: the storefront first, then each one it
 * includes, each once, a loop cut where it comes back round. An included storefront supplies its offers and
 * shelves even while it is switched off itself; at this storefront each of its offers is the host-bound view
 * ({@link ShopEntryOffer#at}), judged by this storefront's presence and lock and by the offer's own
 * {@code Enabled}, {@code Requires} and {@code Season}, never by its own stall's. Purchase counts are filed by
 * offer id, so an included offer's limits are shared by every storefront listing it. Read live: a reload
 * lands on the next look.
 */
public final class StorefrontView {

    private StorefrontView() {
    }

    /** The storefront ids {@code storefrontId}'s page lists from, itself first, lower-cased. */
    @Nonnull
    public static List<String> chain(@Nonnull String storefrontId) {
        return StorefrontIncludes.chain(storefrontId, StorefrontView::resolve);
    }

    /**
     * The offers the page lists: the storefront's own on sale right now ({@link AssetShopCatalog#availableOffersOf}),
     * then each included storefront's that is on sale HERE (the host-bound view), each offer once.
     */
    @Nonnull
    public static List<ShopEntryOffer> offers(@Nonnull String storefrontId) {
        List<String> chain = chain(storefrontId);
        List<ShopEntryOffer> out = new ArrayList<>();
        if (chain.isEmpty()) {
            return out;
        }
        String host = chain.get(0);
        Set<String> listed = new HashSet<>();
        for (ShopEntryOffer offer : CommerceCatalogs.shopContent().availableOffersOf(host)) {
            if (listed.add(ShopCatalog.normalize(offer.offerId()))) {
                out.add(offer);
            }
        }
        for (String included : chain.subList(1, chain.size())) {
            for (ShopEntryOffer offer : CommerceCatalogs.shopContent().offersOf(included)) {
                ShopEntryOffer hosted = offer.at(host);
                if (hosted.enabled() && listed.add(ShopCatalog.normalize(offer.offerId()))) {
                    out.add(hosted);
                }
            }
        }
        return out;
    }

    /**
     * The offer catalogue as {@code storefrontId}'s page sees it: every offer the shared catalogue knows, an
     * offer of a storefront this one includes handed back bound to this one ({@link ShopEntryOffer#at}). The
     * page's engine is built over it ({@code CommerceEngines.shopsAt}), so a press, a shelf draw and a reroll
     * judge an included offer by this storefront.
     */
    @Nonnull
    public static ShopCatalog catalogAt(@Nonnull String storefrontId) {
        List<String> chain = chain(storefrontId);
        ShopCatalog shared = CommerceCatalogs.shops();
        return new ShopCatalog() {
            @Override
            @Nullable
            public ShopOffer offer(@Nonnull String offerId) {
                return hosted(shared.offer(offerId), chain);
            }

            @Override
            @Nonnull
            public Collection<ShopOffer> poolCandidates(@Nonnull String poolId) {
                List<ShopOffer> out = new ArrayList<>();
                for (ShopOffer offer : shared.poolCandidates(poolId)) {
                    out.add(hosted(offer, chain));
                }
                return out;
            }
        };
    }

    /** One offer as {@code storefrontId}'s page sells it ({@link #catalogAt}); null when nothing answers to it. */
    @Nullable
    public static ShopOffer offer(@Nonnull String storefrontId, @Nonnull String offerId) {
        return catalogAt(storefrontId).offer(offerId);
    }

    /** The rotating shelves the page draws: the storefront's own, then each included one's, each once. */
    @Nonnull
    public static List<ShelfSpec> shelves(@Nonnull String storefrontId) {
        List<ShelfSpec> out = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (String id : chain(storefrontId)) {
            for (ShelfSpec shelf : CommerceCatalogs.shelvesOf(id)) {
                if (listed.add(ShopCatalog.normalize(shelf.shelfId()))) {
                    out.add(shelf);
                }
            }
        }
        return out;
    }

    /** The category order: the storefront's own {@code CategoryOrder} first, then each included one's, each once. */
    @Nonnull
    public static List<String> categoryOrder(@Nonnull String storefrontId) {
        return merged(storefrontId, StorefrontAsset::categoryOrder);
    }

    /** The header's wallets: the storefront's own {@code Currencies}, then each included one's, each once. */
    @Nonnull
    public static List<String> currencyIds(@Nonnull String storefrontId) {
        return merged(storefrontId, StorefrontAsset::currencyIds);
    }

    /**
     * The storefront whose {@code Categories} names {@code categoryId}, the first along the chain that does,
     * else the storefront itself (null when nothing defines it), so a category an included stall named keeps
     * its name at every host.
     */
    @Nullable
    public static StorefrontAsset namingCategory(@Nonnull String storefrontId, @Nonnull String categoryId) {
        for (String id : chain(storefrontId)) {
            StorefrontAsset storefront = resolve(id);
            if (storefront != null && storefront.categoryText(categoryId) != null) {
                return storefront;
            }
        }
        return resolve(storefrontId);
    }

    /** {@code offer} bound to the chain's host when its own storefront is one the host includes; else itself. */
    @Nullable
    private static ShopOffer hosted(@Nullable ShopOffer offer, @Nonnull List<String> chain) {
        if (!(offer instanceof ShopEntryOffer entry) || chain.size() < 2) {
            return offer;
        }
        String own = entry.asset().getShop();
        return own != null && chain.indexOf(own) > 0 ? entry.at(chain.get(0)) : offer;
    }

    @Nonnull
    private static List<String> merged(@Nonnull String storefrontId,
            @Nonnull Function<StorefrontAsset, List<String>> leaf) {
        List<String> out = new ArrayList<>();
        for (String id : chain(storefrontId)) {
            StorefrontAsset storefront = resolve(id);
            if (storefront == null) {
                continue;
            }
            for (String value : leaf.apply(storefront)) {
                if (!out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /** The storefront an id names, read live; null when nothing defines it or the fold is not up yet. */
    @Nullable
    private static StorefrontAsset resolve(@Nonnull String storefrontId) {
        try {
            return ShopConfig.getInstance().resolve(storefrontId);
        } catch (Throwable notLoadedYet) {
            return null;
        }
    }
}
