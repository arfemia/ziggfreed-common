package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The dungeons in force, folded {@code defaults < pack < owner} like every keyed asset type: the pack layer
 * from the store's own load event, then the owner's {@code mods/ziggfreedcommon/dungeons.json} read on the
 * same event ({@link DungeonOwnerLayers}), since an owner entry has nothing to inherit from until the packs
 * have landed. Ids are lower-cased on every layer, so a copy's world name {@code <id>-<n>} is too.
 *
 * <p>Read it lazily, after {@code LoadedAssetsEvent}: nothing here is meaningful at setup.
 */
public final class DungeonConfig extends AbstractKeyedAssetConfig<DungeonAsset> {

    private static final DungeonConfig INSTANCE = new DungeonConfig();

    @Nonnull
    public static DungeonConfig getInstance() {
        return INSTANCE;
    }

    private DungeonConfig() {
    }

    /** The dungeon {@code id} names when it can be entered right now (switched on, in season); else null. */
    @Nullable
    public DungeonAsset open(@Nonnull String id) {
        DungeonAsset dungeon = resolve(id);
        return dungeon != null && dungeon.isOpen() ? dungeon : null;
    }
}
