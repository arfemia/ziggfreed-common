package com.ziggfreed.common.shop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which offers exist, and which rotating pool each belongs to. The seam between the purchase engine
 * and wherever the storefront was authored.
 *
 * <p>Two questions and nothing else, because that is all a purchase and a draw need. What a
 * storefront is CALLED, how its categories are ordered, which worlds it exists in and what it looks
 * like are the authoring layer's, and asking about them here would drag every one of them into the
 * engine.
 *
 * <p>Ids resolve case-insensitively, the same rule every other id in this library follows.
 */
public interface ShopCatalog {

    /** A catalog holding nothing. Every offer is unknown and every pool is empty. */
    ShopCatalog EMPTY = new ShopCatalog() {
        @Override
        @Nullable
        public ShopOffer offer(@Nonnull String offerId) {
            return null;
        }

        @Override
        @Nonnull
        public Collection<ShopOffer> poolCandidates(@Nonnull String poolId) {
            return List.of();
        }
    };

    /** The offer with this id, matched case-insensitively, or null when nothing answers to it. */
    @Nullable
    ShopOffer offer(@Nonnull String offerId);

    /**
     * Every offer eligible to appear in the rotating pool {@code poolId}, before any slot filtering
     * or drawing. A disabled offer belongs here or not at its own author's discretion; the engine
     * filters disabled ones out either way, so a catalog need not.
     */
    @Nonnull
    Collection<ShopOffer> poolCandidates(@Nonnull String poolId);

    /**
     * A fixed catalog over {@code offers}, with each one's pool named by {@code poolOf}. What a test
     * drives the engine with, and what a consumer folding a resolved asset store hands over.
     */
    @Nonnull
    static ShopCatalog of(@Nonnull Collection<ShopOffer> offers,
            @Nonnull Function<ShopOffer, String> poolOf) {
        Map<String, ShopOffer> byId = new LinkedHashMap<>();
        Map<String, List<ShopOffer>> byPool = new LinkedHashMap<>();
        for (ShopOffer offer : offers) {
            if (offer == null) {
                continue;
            }
            byId.put(normalize(offer.offerId()), offer);
            String pool = poolOf.apply(offer);
            if (pool != null && !pool.isBlank()) {
                byPool.computeIfAbsent(normalize(pool), id -> new ArrayList<>()).add(offer);
            }
        }
        Map<String, ShopOffer> frozenOffers = Collections.unmodifiableMap(byId);
        Map<String, List<ShopOffer>> frozenPools = Collections.unmodifiableMap(byPool);
        return new ShopCatalog() {
            @Override
            @Nullable
            public ShopOffer offer(@Nonnull String offerId) {
                return frozenOffers.get(normalize(offerId));
            }

            @Override
            @Nonnull
            public Collection<ShopOffer> poolCandidates(@Nonnull String poolId) {
                return frozenPools.getOrDefault(normalize(poolId), List.of());
            }
        };
    }

    /** The case-insensitive form ids are keyed and compared by. */
    @Nonnull
    static String normalize(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
