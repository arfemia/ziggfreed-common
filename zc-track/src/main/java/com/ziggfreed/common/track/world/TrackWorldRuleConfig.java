package com.ziggfreed.common.track.world;

import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of every {@link TrackWorldRuleAsset}, and its owner file
 * {@code mods/ziggfreedcommon/track-world-rules.json} (an id to the leaves that file should read differently,
 * decoded against the packs' answer through the same codec). Every merge moves {@link TrackWorldRules}' generation,
 * so each world reads its rules again and every compiled gain plan is built again.
 */
public final class TrackWorldRuleConfig extends AbstractKeyedAssetConfig<TrackWorldRuleAsset> {

    /** The owner file. */
    public static final String OWNER_FILE = "track-world-rules.json";

    private static final TrackWorldRuleConfig INSTANCE = new TrackWorldRuleConfig();

    @Nonnull
    private static volatile Path ownerDirectory = OwnerFiles.DIRECTORY;

    private TrackWorldRuleConfig() {
    }

    @Nonnull
    public static TrackWorldRuleConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setOwnerDirectory(@Nonnull Path dir) {
        ownerDirectory = dir;
    }

    /** (Re)read {@code track-world-rules.json} into the fold's owner layer. */
    public static void reloadOwnerLayer() {
        OwnerLayerReader.apply("track", ownerDirectory.resolve(OWNER_FILE), TrackWorldRuleAsset.class,
                TrackWorldRuleAsset.CODEC, INSTANCE, "track world rule");
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, TrackWorldRuleAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        TrackWorldRules.invalidate();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, TrackWorldRuleAsset> layer) {
        super.mergePackLayer(layer);
        TrackWorldRules.invalidate();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, TrackWorldRuleAsset> layer) {
        super.mergeOwnerLayer(layer);
        TrackWorldRules.invalidate();
    }
}
