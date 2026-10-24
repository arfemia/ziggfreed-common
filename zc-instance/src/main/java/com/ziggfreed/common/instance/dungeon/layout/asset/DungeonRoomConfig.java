package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.instance.dungeon.layout.PoolIndex;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;

/**
 * The dungeon rooms in force ({@link DungeonRoomAsset}), folded {@code defaults < pack < owner} like every keyed asset
 * type, ids lower-cased. Read it lazily, after {@code LoadedAssetsEvent}.
 */
public final class DungeonRoomConfig extends AbstractKeyedAssetConfig<DungeonRoomAsset> {

    private static final DungeonRoomConfig INSTANCE = new DungeonRoomConfig();

    @Nonnull
    public static DungeonRoomConfig getInstance() {
        return INSTANCE;
    }

    private DungeonRoomConfig() {
    }

    /** Every room as the generator reads it, in id order. */
    @Nonnull
    public List<RoomSpec> specs() {
        List<RoomSpec> out = new ArrayList<>();
        for (String id : ids()) {
            DungeonRoomAsset room = resolve(id);
            if (room != null) {
                out.add(room.toSpec());
            }
        }
        return out;
    }

    /** Every room as a catalogue, each pool counted through {@code pools}. */
    @Nonnull
    public RoomCatalogue catalogue(@Nonnull PoolIndex pools) {
        return RoomCatalogue.of(specs(), pools);
    }
}
