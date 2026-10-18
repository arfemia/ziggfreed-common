package com.ziggfreed.common.track.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.progress.asset.CategoryPresentationConfig;

/**
 * The folded {@link TrackCategoryAsset} layer, read the same way as the quest and achievement categories
 * ({@link CategoryPresentationConfig}). Read it lazily: the store's load event fills it after every plugin's
 * {@code setup()}.
 */
public final class TrackCategoryConfig extends CategoryPresentationConfig<TrackCategoryAsset> {

    private static final TrackCategoryConfig INSTANCE = new TrackCategoryConfig();

    private TrackCategoryConfig() {
    }

    @Nonnull
    public static TrackCategoryConfig getInstance() {
        return INSTANCE;
    }
}
