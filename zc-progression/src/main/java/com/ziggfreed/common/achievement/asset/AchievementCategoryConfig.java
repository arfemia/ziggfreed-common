package com.ziggfreed.common.achievement.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.progress.asset.CategoryPresentationConfig;

/**
 * The folded {@link AchievementCategoryAsset} layer: how each grouping label is presented, resolved
 * {@code defaults < pack < owner} by id like every other framework config.
 *
 * <p>Presentation only. A category exists because content filed itself under that word; this says
 * where the word sits, what illustrates it, what it is called, its accent, how its subcategories read
 * and which calendar event it belongs to. A category no file here mentions still works, and simply
 * gets whatever a surface draws for a group nobody described. The reads ({@code ordered},
 * {@code orderedIds}, {@code category}) are the ones every category type shares, in
 * {@link CategoryPresentationConfig}.
 *
 * <p>Read it LAZILY: the layer is filled by the asset store's load event, which runs after every
 * plugin's {@code setup()}.
 */
public final class AchievementCategoryConfig extends CategoryPresentationConfig<AchievementCategoryAsset> {

    private static final AchievementCategoryConfig INSTANCE = new AchievementCategoryConfig();

    @Nonnull
    public static AchievementCategoryConfig getInstance() {
        return INSTANCE;
    }

    private AchievementCategoryConfig() {
    }
}
