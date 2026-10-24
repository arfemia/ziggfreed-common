package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;

/**
 * The dungeon layout plans in force ({@link DungeonLayoutAsset}), folded {@code defaults < pack < owner} like every
 * keyed asset type, ids lower-cased. Read it lazily, after {@code LoadedAssetsEvent}.
 */
public final class DungeonLayoutConfig extends AbstractKeyedAssetConfig<DungeonLayoutAsset> {

    private static final DungeonLayoutConfig INSTANCE = new DungeonLayoutConfig();

    @Nonnull
    public static DungeonLayoutConfig getInstance() {
        return INSTANCE;
    }

    private DungeonLayoutConfig() {
    }

    /** The plan {@code id} names, matched without regard to case, as the generator reads it; null when none. */
    @Nullable
    public LayoutPlan plan(@Nonnull String id) {
        DungeonLayoutAsset asset = resolve(id);
        return asset == null ? null : asset.toPlan();
    }

    /** Every plan as the generator reads it, in id order. */
    @Nonnull
    public List<LayoutPlan> plans() {
        List<LayoutPlan> out = new ArrayList<>();
        for (String id : ids()) {
            LayoutPlan plan = plan(id);
            if (plan != null) {
                out.add(plan);
            }
        }
        return out;
    }
}
