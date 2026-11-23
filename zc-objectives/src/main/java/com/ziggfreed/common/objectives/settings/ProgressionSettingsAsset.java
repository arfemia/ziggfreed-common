package com.ziggfreed.common.objectives.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The server owner's word on the shared quest engine: today, how many quests a player may carry at once. The FILE NAME
 * is the record's id, and the record every read uses is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/ProgressionSettings/Default.json}, which this library ships with every
 * leaf at its unauthored answer; the owner layer is {@code mods/ziggfreedcommon/progression.json}:
 * <pre>{@code
 * // mods/ziggfreedcommon/progression.json
 * { "Default": { "Quests": { "MaxActive": 10 } } }
 * }</pre>
 */
public final class ProgressionSettingsAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ProgressionSettingsAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ProgressionSettings";

    /** The id of the record every read uses (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private QuestRules quests;

    public static final AssetBuilderCodec<String, ProgressionSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    ProgressionSettingsAsset.class,
                    ProgressionSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Quests", QuestRules.CODEC, false),
                    (a, v) -> a.quests = v, a -> a.quests, (a, p) -> a.quests = p.quests)
            .documentation("The quest log: how many quests a player may carry at once.")
            .add()
            .build();

    public ProgressionSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The quest log's rules, never null. */
    @Nonnull
    public QuestRules quests() {
        return quests != null ? quests : QuestRules.NONE;
    }

    /** The {@code Quests} group. */
    public static final class QuestRules {

        /** A quest log nothing was said about: no limit. */
        public static final QuestRules NONE = new QuestRules();

        @Nullable protected Integer maxActive;

        public static final BuilderCodec<QuestRules> CODEC = BuilderCodec
                .builder(QuestRules.class, QuestRules::new)
                .appendInherited(new KeyedCodec<>("MaxActive", Codec.INTEGER, false),
                        (o, v) -> o.maxActive = v, o -> o.maxActive, (o, p) -> o.maxActive = p.maxActive)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many quests a player may carry at once; 0 is no limit. A quest that takes no "
                        + "place in the log (a board contract) never counts. A player at the limit is told their "
                        + "log is full when they try to take another.")
                .add()
                .build();

        public QuestRules() {
        }

        public QuestRules(@Nullable Integer maxActive) {
            this.maxActive = maxActive;
        }

        /** How many quests a player may carry at once, 0 for no limit; a negative one reads as none. */
        public int maxActive() {
            return maxActive == null ? 0 : Math.max(0, maxActive);
        }
    }
}
