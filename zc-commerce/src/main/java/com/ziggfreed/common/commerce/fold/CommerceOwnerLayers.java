package com.ziggfreed.common.commerce.fold;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.shop.asset.ShopAsset;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.util.SafeLog;

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
 * <p>Read AFTER the pack layer has merged, which is why the wiring root calls each of these from the
 * store's own load event rather than from setup.
 */
public final class CommerceOwnerLayers {

    /** Where a server owner's commerce files live. */
    public static final Path DEFAULT_DIRECTORY = Paths.get("mods", "ziggfreedcommon");

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
        apply(SHOPS_FILE, ShopAsset.class, ShopAsset.CODEC, ShopConfig.getInstance(), "storefront");
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

    /**
     * Read one owner file and replace that type's owner layer with what it says.
     *
     * @param noun what one entry is CALLED in a line written for the server owner reading the log
     */
    private static <T extends JsonAsset<String>> void apply(@Nonnull String fileName,
            @Nonnull Class<T> assetClass, @Nonnull AssetBuilderCodec<String, T> codec,
            @Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull String noun) {

        // Drop the previous layer FIRST: every entry below resolves its own base out of the pack
        // layer, and leaving the last read's answers in place would stack one override on another.
        config.mergeOwnerLayer(Map.of());

        Path file = directory.resolve(fileName);
        JsonObject root = readObject(file);
        if (root == null) {
            return;
        }

        Map<String, T> layer = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.startsWith("$")) {
                continue; // $Comment and friends are documentation, not entries
            }
            String id = key.trim().toLowerCase(Locale.ROOT);
            T decoded = decode(entry.getValue(), id, assetClass, codec, config, file, noun);
            if (decoded != null) {
                layer.put(id, decoded);
            }
        }

        config.mergeOwnerLayer(layer);
        if (!layer.isEmpty()) {
            SafeLog.info("[commerce] " + file + ": " + layer.size() + " " + noun
                    + " override(s) in force");
        }
    }

    /** One entry, decoded against whatever the packs already say about its id. */
    @Nullable
    private static <T extends JsonAsset<String>> T decode(@Nullable JsonElement body, @Nonnull String id,
            @Nonnull Class<T> assetClass, @Nonnull AssetBuilderCodec<String, T> codec,
            @Nonnull AbstractKeyedAssetConfig<T> config, @Nonnull Path file, @Nonnull String noun) {

        if (body == null || !body.isJsonObject()) {
            SafeLog.warn("[commerce] " + file + ": the " + noun + " override '" + id
                    + "' is not a block of settings, so it was skipped");
            return null;
        }
        T base = config.resolve(id);
        try {
            AssetExtraInfo.Data data =
                    new AssetExtraInfo.Data(assetClass, id, base == null ? null : id);
            return codec.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(body.toString()), base,
                    new AssetExtraInfo<>(data));
        } catch (Exception e) {
            SafeLog.warn("[commerce] " + file + ": the " + noun + " override '" + id
                    + "' could not be read, so it was skipped: " + e.getMessage());
            return null;
        }
    }

    /**
     * The file as a JSON object, or null when there is nothing usable to read. A missing file is the
     * common case and says nothing; a malformed one warns and is left exactly as the owner wrote it.
     */
    @Nullable
    private static JsonObject readObject(@Nonnull Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) {
                SafeLog.warn("[commerce] " + file + " is not a block of entries keyed by id, so nothing "
                        + "in it is in force");
                return null;
            }
            return root.getAsJsonObject();
        } catch (Exception e) {
            SafeLog.warn("[commerce] could not read " + file + ", so nothing in it is in force: "
                    + e.getMessage());
            return null;
        }
    }
}
