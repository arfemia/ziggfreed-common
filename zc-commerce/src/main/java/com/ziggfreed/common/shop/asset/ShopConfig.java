package com.ziggfreed.common.shop.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link StorefrontAsset}: which storefronts this server
 * has.
 *
 * <p>Process-wide because the defining ASSETS are: one folder, one set of files, however many mods
 * sell out of them. A pack ships its storefronts and a server owner retunes one through
 * {@code mods/ziggfreedcommon/shops.json} - closing a shop, reordering its shelves, changing which
 * wallets its header shows - without editing anybody's pack.
 */
public final class ShopConfig extends AbstractKeyedAssetConfig<StorefrontAsset> {

    private static final ShopConfig INSTANCE = new ShopConfig();

    private ShopConfig() {
    }

    @Nonnull
    public static ShopConfig getInstance() {
        return INSTANCE;
    }

    /**
     * Every storefront on this server right now ({@link StorefrontAsset#isAvailable()}: switched on,
     * and not hidden by a feature that reads off), in the order they should be listed: by
     * {@code Order}, then by id so two storefronts sharing a number never swap places between
     * restarts.
     */
    @Nonnull
    public List<StorefrontAsset> listed() {
        List<StorefrontAsset> out = new ArrayList<>();
        for (String id : ids()) {
            StorefrontAsset shop = resolve(id);
            if (shop != null && shop.isAvailable()) {
                out.add(shop);
            }
        }
        out.sort(Comparator.comparingInt(StorefrontAsset::order)
                .thenComparing(shop -> shop.getId() == null ? "" : shop.getId()));
        return out;
    }

    /** The first storefront {@link #listed()} names: what an unnamed destination opens. Null for none. */
    @Nullable
    public String firstListedId() {
        for (StorefrontAsset shop : listed()) {
            if (shop.getId() != null) {
                return shop.getId();
            }
        }
        return null;
    }
}
