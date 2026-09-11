package com.ziggfreed.common.objectives.indicator;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;

/**
 * The runtime table of {@link QuestIndicatorAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type. One entry matters, {@link QuestIndicatorAsset#DEFAULT_ID}: it is
 * the global scope every quest's and step's own block narrows.
 */
public final class QuestIndicatorConfig extends AbstractKeyedAssetConfig<QuestIndicatorAsset> {

    private static final QuestIndicatorConfig INSTANCE = new QuestIndicatorConfig();

    @Nonnull
    public static QuestIndicatorConfig getInstance() {
        return INSTANCE;
    }

    private QuestIndicatorConfig() {
    }

    /** The global word, or an empty block (the library's own defaults) when no file was shipped. */
    @Nonnull
    public QuestIndicatorSpec global() {
        QuestIndicatorAsset asset = resolve(QuestIndicatorAsset.DEFAULT_ID);
        return asset != null ? asset : QuestIndicatorSpec.EMPTY;
    }
}
