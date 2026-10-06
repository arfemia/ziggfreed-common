package com.ziggfreed.common.reputation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.reputation.asset.ReputationOwnerLayers;

/**
 * Reads companion files back the way the engine's asset loading does, and puts every reputation store and
 * switch back the way a bare server starts, so a test reads as what it proves.
 */
public final class ReputationFixtures {

    /** The spec's ladder as test data (not the shipped files): the seven ranks, bottom first. */
    public static final List<ReputationLadder.Rank> LADDER = List.of(
            new ReputationLadder.Rank("Hated", -30_000, -3_000),
            new ReputationLadder.Rank("Unfriendly", -3_000, 0),
            new ReputationLadder.Rank("Neutral", 0, 1_000),
            new ReputationLadder.Rank("Friendly", 1_000, 3_000),
            new ReputationLadder.Rank("Honored", 3_000, 9_000),
            new ReputationLadder.Rank("Revered", 9_000, 21_000),
            new ReputationLadder.Rank("Exalted", 21_000, 2_000_000_000));

    /** A native group id spelled with capitals, the way the engine keys it. */
    public static final String OLD_JACK = "Test_Old_Jack";

    private ReputationFixtures() {
    }

    /** An engine holding the ladder and one group, {@link #OLD_JACK}, starting at 0. */
    @Nonnull
    public static FakeReputationNative engine() {
        return new FakeReputationNative().group(OLD_JACK, 0);
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

    /** A fan-out that keeps everything it is handed, as text a test can compare. Open, so a test can make one part throw. */
    public static class RecordingFanOut implements ReputationFanOut {

        public final List<ReputationChange> changes = new ArrayList<>();
        /** One line per credit: {@code <id>: <rank>, <rank>, ...}. */
        public final List<String> credits = new ArrayList<>();
        /** One line per rise: {@code <id>: <rank>}. */
        public final List<String> rises = new ArrayList<>();

        @Override
        public void changed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                @Nonnull ReputationChange change) {
            changes.add(change);
        }

        @Override
        public void credit(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID playerId,
                @Nonnull ReputationDef reputation, @Nonnull List<ReputationLadder.Rank> held) {
            credits.add(reputation.id() + ": "
                    + held.stream().map(ReputationLadder.Rank::id).collect(Collectors.joining(", ")));
        }

        @Override
        public void rose(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
                @Nonnull ReputationDef reputation, @Nonnull ReputationLadder.Rank rank) {
            rises.add(reputation.id() + ": " + rank.id());
        }
    }
}
