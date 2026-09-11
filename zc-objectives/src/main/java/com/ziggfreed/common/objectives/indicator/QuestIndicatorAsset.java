package com.ziggfreed.common.objectives.indicator;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestSituation;

/**
 * The server's GLOBAL word on quest indicators, as a file:
 * {@code Server/ZiggfreedCommon/QuestIndicators/Default.json}. One file, read under the id
 * {@link #DEFAULT_ID}; a pack overrides it by shipping its own, and a server owner narrows it
 * per leaf from {@code mods/ziggfreedcommon/quest-indicators.json}.
 *
 * <p>The leaves are exactly a quest's own {@code Indicator} block ({@link QuestIndicatorSpec}),
 * appended onto this asset codec through the same call, so the global scope, the quest scope and
 * the step scope are one schema and cannot drift. What a quest or a step does not say falls
 * through to this file; what this file does not say falls through to the library's defaults
 * (every situation shows overhead, none marks the map).
 */
public final class QuestIndicatorAsset extends QuestIndicatorSpec
        implements JsonAssetWithMap<String, DefaultAssetMap<String, QuestIndicatorAsset>> {

    /** Where the file lives. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/QuestIndicators";

    /** The one id the global word is read under. */
    public static final String DEFAULT_ID = "Default";

    private String id;
    private AssetExtraInfo.Data data;

    public static final AssetBuilderCodec<String, QuestIndicatorAsset> CODEC = appendLeaves(AssetBuilderCodec.builder(
                    QuestIndicatorAsset.class,
                    QuestIndicatorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data))
            .build();

    public QuestIndicatorAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Java-side factory, for a test or a consumer seeding the table without a file. */
    @Nonnull
    public static QuestIndicatorAsset of(@Nonnull String id, @Nonnull QuestIndicatorSpec spec) {
        QuestIndicatorAsset a = new QuestIndicatorAsset();
        a.id = id;
        a.enabled = spec.getEnabled();
        a.collect = spec.situation(QuestSituation.COLLECT);
        a.turnIn = spec.situation(QuestSituation.TURN_IN);
        a.available = spec.situation(QuestSituation.AVAILABLE);
        a.inProgress = spec.situation(QuestSituation.IN_PROGRESS);
        return a;
    }
}
