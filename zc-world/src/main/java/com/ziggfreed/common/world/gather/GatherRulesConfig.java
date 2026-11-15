package com.ziggfreed.common.world.gather;

import java.nio.file.Path;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.OwnerLayerReader;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The {@code defaults < pack < owner} fold of {@link GatherRulesAsset}, keyed by record id. The library ships
 * {@code Default.json} in its own jar's asset pack, so it rides the pack layer; the owner layer, from
 * {@code mods/ziggfreedcommon/}{@value #OWNER_FILE}, wins over both. A server with no record at all reads every
 * default ({@link #rules}).
 */
public final class GatherRulesConfig extends AbstractKeyedAssetConfig<GatherRulesAsset> {

    /** The owner file over the rules. */
    public static final String OWNER_FILE = "gather-rules.json";

    private static final GatherRulesConfig INSTANCE = new GatherRulesConfig();

    /** What a server with no record reads: every leaf unauthored. */
    private static final GatherRulesAsset DEFAULTS = new GatherRulesAsset();

    @Nonnull
    private static volatile Path directory = OwnerFiles.DIRECTORY;

    private GatherRulesConfig() {
    }

    @Nonnull
    public static GatherRulesConfig getInstance() {
        return INSTANCE;
    }

    /** Point the owner file at another directory (a test, or a consumer with its own data dir). */
    public static void setDirectory(@Nonnull Path dir) {
        directory = dir;
    }

    /** (Re)read the owner file into the owner layer. From the store's own load event, after the packs. */
    public static void reloadOwner() {
        OwnerLayerReader.apply("gather", directory.resolve(OWNER_FILE), GatherRulesAsset.class,
                GatherRulesAsset.CODEC, INSTANCE, "gather rules");
    }

    /** The folded record every gather step reads, or the defaults when no layer authored one. */
    @Nonnull
    public GatherRulesAsset rules() {
        return resolveOrDefault(GatherRulesAsset.SHARED_ID, DEFAULTS);
    }
}
