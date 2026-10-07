package com.ziggfreed.common.quest.asset;

import javax.annotation.Nonnull;

import com.ziggfreed.common.progress.asset.CategoryPresentationConfig;

/**
 * The folded {@link QuestCategoryAsset} layer: how each quest category is presented, resolved
 * {@code defaults < pack < owner} by id like every other framework config, read the same way as the
 * achievement categories ({@link CategoryPresentationConfig}).
 *
 * <p>Read it LAZILY: the layer is filled by the asset store's load event, which runs after every
 * plugin's {@code setup()}.
 */
public final class QuestCategoryConfig extends CategoryPresentationConfig<QuestCategoryAsset> {

    private static final QuestCategoryConfig INSTANCE = new QuestCategoryConfig();

    @Nonnull
    public static QuestCategoryConfig getInstance() {
        return INSTANCE;
    }

    private QuestCategoryConfig() {
    }
}
