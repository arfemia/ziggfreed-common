package com.ziggfreed.common.commerce.fold;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The SERVER OWNER's last word on commerce content, at {@code mods/ziggfreedcommon/*.json}.
 *
 * <p>It exists because an admin has to be able to close a shop, take a board down, slow a rotation
 * or retune a wallet WITHOUT editing the pack that ships it - which an update would overwrite. So
 * each file is a map from an id to the leaves that id should read differently:
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/shops.json
 * {
 *   "general":   { "Enabled": false },
 *   "xpexchange": { "CategoryOrder": ["Conversion", "Featured"] }
 * }
 * }</pre>
 *
 * <p><b>Override BY ID, LEAF BY LEAF.</b> An entry is decoded against whatever the packs already say
 * about that id, through the very same codec the file itself uses, so writing one leaf keeps every
 * other one and an author needs no second schema. An id no pack defines is a new entry rather than
 * an error, which is how an owner adds a wallet of their own.
 *
 * <p><b>A re-read never compounds.</b> The previous owner layer is dropped before anything is
 * decoded, so each entry inherits from the PACK layer every time rather than from what the last read
 * produced.
 *
 * <p><b>A malformed file costs the overrides, not the server.</b> The file is never rewritten, a bad
 * entry is skipped with one line naming it, and the rest are carried - the same bargain the
 * placement owner switch makes.
 *
 * <p><b>{@code $}-prefixed top-level keys are reserved, never entries</b> ({@link OwnerFiles}):
 * {@code $Comment} is documentation, and {@code $SchemaVersion} names the file's schema (absent
 * means 1, the shape described above; a newer number than this library reads refuses the whole
 * file with one warning).
 *
 * <p>Read AFTER the pack layer has merged, which is why the wiring root calls each of these from the
 * store's own load event rather than from setup. The reading itself is the library-wide
 * {@link OwnerLayerReader}; this class only says which four files the economy keeps and where.
 */
public final class CommerceOwnerLayers {

    /** Where a server owner's commerce files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

    /** The log prefix every commerce owner-file line carries. */
    private static final String LOG_TAG = "commerce";

    /** The owner file over the wallets. */
    public static final String CURRENCIES_FILE = "currencies.json";

    /** The owner file over the storefronts. */
    public static final String SHOPS_FILE = "shops.json";

    /** The owner file over the boards. */
    public static final String BOARDS_FILE = "boards.json";

    /** The rotating shelves' own owner file, decoded against the pack layer like its siblings. */
    public static final String SHOP_POOLS_FILE = "shop-pools.json";

    @Nonnull
    private static volatile Path directory = DEFAULT_DIRECTORY;

    private CommerceOwnerLayers() {
    }

    /** Point the owner files at a different directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** Where the owner files are being read from. */
    @Nonnull
    public static Path directory() {
        return directory;
    }

    /** (Re)read {@code currencies.json} into the wallet fold's owner layer. */
    public static void reloadCurrencies() {
        apply(CURRENCIES_FILE, CurrencyAsset.class, CurrencyAsset.CODEC, CurrencyConfig.getInstance(),
                "wallet");
    }

    /** (Re)read {@code shops.json} into the storefront fold's owner layer. */
    public static void reloadShops() {
        apply(SHOPS_FILE, StorefrontAsset.class, StorefrontAsset.CODEC, ShopConfig.getInstance(), "storefront");
    }

    /** (Re)read {@code boards.json} into the board fold's owner layer. */
    public static void reloadBoards() {
        apply(BOARDS_FILE, BoardAsset.class, BoardAsset.CODEC, BoardConfig.getInstance(), "board");
    }

    /** (Re)read {@code shop-pools.json} into the shelf fold's owner layer. */
    public static void reloadShopPools() {
        apply(SHOP_POOLS_FILE, ShopPoolAsset.class, ShopPoolAsset.CODEC,
                ShopPoolConfig.getInstance(), "shelf");
    }

    /** Read one owner file under the commerce directory through the shared reader. */
    private static <T extends JsonAsset<String>> void apply(@Nonnull String fileName,
            @Nonnull Class<T> assetClass, @Nonnull AssetBuilderCodec<String, T> codec,
            @Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull String noun) {
        OwnerLayerReader.apply(LOG_TAG, directory.resolve(fileName), assetClass, codec, config, noun);
    }
}
