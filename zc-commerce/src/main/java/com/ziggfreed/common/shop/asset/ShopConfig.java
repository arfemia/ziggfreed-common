package com.ziggfreed.common.shop.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * The {@code defaults < pack < owner} fold of every {@link StorefrontAsset}: which storefronts this server
 * has.
 *
 * <p>Process-wide because the defining ASSETS are: one folder, one set of files, however many mods
 * sell out of them. A pack ships its storefronts and a server owner retunes one through
 * {@code mods/ziggfreedcommon/shops.json} - closing a shop, reordering its shelves, changing which
 * wallets its header shows - without editing anybody's pack.
 *
 * <p>Two views of one list. A player's view is {@link #listedIn} and {@link #firstListedIdIn}, which
 * also leave out a storefront whose {@code Where} does not name the world that player stands in.
 * {@link #listed()} and {@link #firstListedId()} have nobody looking, so they ignore {@code Where}:
 * they are what the admin verbs and a server-wide question ("does this server sell anything?") read.
 */
public final class ShopConfig extends AbstractKeyedAssetConfig<StorefrontAsset> {

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Shops";

    private static final ShopConfig INSTANCE = new ShopConfig();

    private ShopConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static ShopConfig getInstance() {
        return INSTANCE;
    }

    /**
     * Every storefront on this server right now ({@link StorefrontAsset#isAvailable()}: switched on,
     * and not hidden by a feature that reads off), in the order they should be listed: by
     * {@code Order}, then by id so two storefronts sharing a number never swap places between
     * restarts. Nobody is looking, so {@code Where} is not asked; a player's list is {@link #listedIn}.
     */
    @Nonnull
    public List<StorefrontAsset> listed() {
        return listedWhere(StorefrontAsset::isAvailable);
    }

    /**
     * What a player standing in {@code viewer}'s world is shown: {@link #listed()} without any
     * storefront whose {@code Where} leaves that world out ({@link StorefrontAsset#isAvailableIn}),
     * in the same order.
     */
    @Nonnull
    public List<StorefrontAsset> listedIn(@Nonnull LoadedWorld viewer) {
        return listedWhere(shop -> shop.isAvailableIn(viewer));
    }

    /** The first storefront {@link #listed()} names. Null for none. */
    @Nullable
    public String firstListedId() {
        return firstIdOf(listed());
    }

    /**
     * The first storefront {@link #listedIn} names for {@code viewer}: what an unnamed destination
     * opens for that player. Null for none.
     */
    @Nullable
    public String firstListedIdIn(@Nonnull LoadedWorld viewer) {
        return firstIdOf(listedIn(viewer));
    }

    @Nonnull
    private List<StorefrontAsset> listedWhere(@Nonnull Predicate<StorefrontAsset> keep) {
        List<StorefrontAsset> out = new ArrayList<>();
        for (String id : ids()) {
            StorefrontAsset shop = resolve(id);
            if (shop != null && keep.test(shop)) {
                out.add(shop);
            }
        }
        out.sort(Comparator.comparingInt(StorefrontAsset::order)
                .thenComparing(shop -> shop.getId() == null ? "" : shop.getId()));
        return out;
    }

    @Nullable
    private static String firstIdOf(@Nonnull List<StorefrontAsset> shops) {
        for (StorefrontAsset shop : shops) {
            if (shop.getId() != null) {
                return shop.getId();
            }
        }
        return null;
    }
}
