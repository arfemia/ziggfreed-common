package com.ziggfreed.common.progress.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of authored {@link ObjectiveKindAsset}s, folded {@code defaults < pack < owner}
 * like every other keyed asset type.
 *
 * <p>Nothing reads this table while a step is being counted or painted. It is read ONCE, when the
 * assets have loaded, by {@link ObjectiveKindFold} - which merges each file over whatever code
 * registered under the same id and puts the result back in the vocabulary the engines already ask.
 * So a kind stays one lookup at runtime whether it was written in Java or in a file.
 */
public final class ObjectiveKindConfig extends AbstractKeyedAssetConfig<ObjectiveKindAsset> {

    private static final ObjectiveKindConfig INSTANCE = new ObjectiveKindConfig();

    @Nonnull
    public static ObjectiveKindConfig getInstance() {
        return INSTANCE;
    }

    private ObjectiveKindConfig() {
    }
}
