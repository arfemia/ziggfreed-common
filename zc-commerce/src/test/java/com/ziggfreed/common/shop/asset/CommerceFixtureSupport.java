package com.ziggfreed.common.shop.asset;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The decode helpers the shop tests share: one place that knows how an asset is read back with its
 * {@code Parent} resolved, so a test reads as what it is proving rather than as codec plumbing.
 */
final class CommerceFixtureSupport {

    private CommerceFixtureSupport() {
    }

    @Nonnull
    static ShopEntryAsset entry(@Nonnull String json, @Nonnull String id, @Nullable String parentId,
            @Nullable ShopEntryAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ShopEntryAsset.class, id, parentId);
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @Nonnull
    static ShopAsset shop(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ShopAsset.class, id, null);
        return ShopAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @Nonnull
    static ShopPoolAsset pool(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ShopPoolAsset.class, id, null);
        return ShopPoolAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }
}
