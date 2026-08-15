package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;

/**
 * Reads the shipped fixture files back the way the engine's own asset loading would, so a fold test
 * reads as what it is proving rather than as codec plumbing. The files themselves are real converted
 * content, shared with the schema tests one package over.
 */
final class CommerceFoldFixtures {

    private static final String ROOT = "/Server/ZiggfreedCommon/";

    private CommerceFoldFixtures() {
    }

    @Nonnull
    static CurrencyAsset currency(@Nonnull String path) throws IOException {
        return CurrencyAsset.CODEC.decodeAndInheritJsonAsset(reader(path), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(CurrencyAsset.class, idOf(path), null)));
    }

    @Nonnull
    static StorefrontAsset shop(@Nonnull String path) throws IOException {
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(reader(path), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(StorefrontAsset.class, idOf(path), null)));
    }

    @Nonnull
    static ShopPoolAsset pool(@Nonnull String path) throws IOException {
        return ShopPoolAsset.CODEC.decodeAndInheritJsonAsset(reader(path), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ShopPoolAsset.class, idOf(path), null)));
    }

    @Nonnull
    static ShopEntryAsset entry(@Nonnull String path) throws IOException {
        return entry(path, null, null);
    }

    @Nonnull
    static ShopEntryAsset entry(@Nonnull String path, @Nullable ShopEntryAsset parent,
            @Nullable String parentId) throws IOException {
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(reader(path), parent,
                new AssetExtraInfo<>(
                        new AssetExtraInfo.Data(ShopEntryAsset.class, idOf(path), parentId)));
    }

    @Nonnull
    static BoardAsset board(@Nonnull String path) throws IOException {
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(reader(path), null,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BoardAsset.class, idOf(path), null)));
    }

    @Nonnull
    static BountyAsset bounty(@Nonnull String path, @Nullable BountyAsset parent,
            @Nullable String parentId) throws IOException {
        return BountyAsset.CODEC.decodeAndInheritJsonAsset(reader(path), parent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(BountyAsset.class, idOf(path), parentId)));
    }

    /** The file's body as the codec reads it. */
    @Nonnull
    static RawJsonReader reader(@Nonnull String path) throws IOException {
        return RawJsonReader.fromJsonString(read(path));
    }

    /** The file's body verbatim, for a test seeding an owner file rather than decoding one asset. */
    @Nonnull
    static String read(@Nonnull String path) throws IOException {
        try (InputStream in = CommerceFoldFixtures.class.getResourceAsStream(ROOT + path)) {
            assertNotNull(in, "fixture missing: " + ROOT + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The id the engine would key this file under: its file name, lower-cased by the codec itself. */
    @Nonnull
    static String idOf(@Nonnull String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.substring(0, file.length() - ".json".length());
    }
}
