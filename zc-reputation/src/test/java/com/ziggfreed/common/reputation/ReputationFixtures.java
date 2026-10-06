package com.ziggfreed.common.reputation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.reputation.asset.ReputationOwnerLayers;

/**
 * Reads companion files back the way the engine's asset loading does, and puts every reputation store and
 * switch back the way a bare server starts, so a test reads as what it proves.
 */
public final class ReputationFixtures {

    private ReputationFixtures() {
    }

    /** One companion file, decoded as the engine's loader would. */
    @Nonnull
    public static ReputationAsset companion(@Nonnull String id, @Nonnull String json) {
        return companion(id, json, null);
    }

    /** One companion file decoded over {@code parent}, the way an owner entry or a Parent child is. */
    @Nonnull
    public static ReputationAsset companion(@Nonnull String id, @Nonnull String json,
            @Nullable ReputationAsset parent) {
        try {
            return ReputationAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(ReputationAsset.class, id,
                            parent == null ? null : id)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replace the pack layer with {@code byId} (the config lower-cases the keys, as the store does). */
    public static void loadCompanions(@Nonnull Map<String, ReputationAsset> byId) {
        ReputationConfig.getInstance().mergePackLayer(byId);
    }

    /** Every reputation store and switch back to a bare server's. */
    public static void reset() {
        ReputationConfig.getInstance().mergePackLayer(Map.of());
        ReputationConfig.getInstance().mergeOwnerLayer(Map.of());
        ReputationConfig.getInstance().setGlobalEnabled(true);
        ReputationOwnerLayers.setDirectory(ReputationOwnerLayers.DEFAULT_DIRECTORY);
    }
}
