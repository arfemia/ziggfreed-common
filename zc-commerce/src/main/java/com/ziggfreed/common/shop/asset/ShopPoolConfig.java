package com.ziggfreed.common.shop.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link ShopPoolAsset}: which rotating shelves
 * this server has.
 *
 * <p>Process-wide for the same reason the storefronts are, and folded the same way, so a server
 * owner can slow a rotation down or raise a reroll price through the owner layer without editing
 * anybody's pack.
 */
public final class ShopPoolConfig extends AbstractKeyedAssetConfig<ShopPoolAsset> {

    private static final ShopPoolConfig INSTANCE = new ShopPoolConfig();

    private ShopPoolConfig() {
    }

    @Nonnull
    public static ShopPoolConfig getInstance() {
        return INSTANCE;
    }

    /**
     * The shelves of one storefront, in the order they should read: by {@code Order}, then by id so
     * two shelves sharing a number never swap places between restarts.
     */
    @Nonnull
    public List<ShopPoolAsset> shelvesOf(@Nonnull String shopId) {
        String wanted = shopId.trim().toLowerCase(Locale.ROOT);
        List<ShopPoolAsset> out = new ArrayList<>();
        for (String id : ids()) {
            ShopPoolAsset pool = resolve(id);
            if (pool != null && pool.isEnabled() && wanted.equals(pool.getShop())) {
                out.add(pool);
            }
        }
        out.sort(Comparator.comparingInt(ShopPoolAsset::order)
                .thenComparing(pool -> pool.getId() == null ? "" : pool.getId()));
        return out;
    }
}
