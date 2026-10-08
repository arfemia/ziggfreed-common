package com.ziggfreed.common.objectives.indicator;

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
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestSituation;
import com.ziggfreed.common.worldmap.WaypointService;

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
 * (every situation shows overhead, none marks the map). Besides the block's leaves it carries
 * {@code Pointer}, the tracked pointer's look, which only this server-wide scope has.
 */
public final class QuestIndicatorAsset extends QuestIndicatorSpec
        implements JsonAssetWithMap<String, DefaultAssetMap<String, QuestIndicatorAsset>> {

    /**
     * How the tracked-quest pointer looks on the compass and map. The library points at every tracked
     * quest's destination, always; only this server-wide file says how the marker looks, so a quest
     * has no pointer block of its own.
     */
    public static final class Pointer {

        @Nullable protected String icon;

        public static final BuilderCodec<Pointer> CODEC = BuilderCodec.builder(Pointer.class, Pointer::new)
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (p, v) -> p.icon = v, p -> p.icon, (p, parent) -> p.icon = parent.icon)
                .metadata(EditorSchema.defaultValue(WaypointService.DEFAULT_ICON))
                .documentation("The map marker texture the pointer draws, e.g. \"Coordinate.png\": on the "
                        + "character a tracked quest's current step sends the player to, or on the nearest "
                        + "known way into the world that character stands in.").add()
                .build();

        public Pointer() {
        }

        @Nonnull
        public static Pointer of(@Nullable String icon) {
            Pointer p = new Pointer();
            p.icon = icon;
            return p;
        }

        @Nullable
        public String getIcon() {
            return icon;
        }
    }

    /** Where the file lives. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/QuestIndicators";

    /** The one id the global word is read under. */
    public static final String DEFAULT_ID = "Default";

    private String id;
    private AssetExtraInfo.Data data;
    @Nullable private Pointer pointer;

    public static final AssetBuilderCodec<String, QuestIndicatorAsset> CODEC = appendLeaves(AssetBuilderCodec.builder(
                    QuestIndicatorAsset.class,
                    QuestIndicatorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data))
            .appendInherited(new KeyedCodec<>("Pointer", Pointer.CODEC, false),
                    (a, v) -> a.pointer = v, a -> a.pointer, (a, p) -> a.pointer = p.pointer)
            .documentation("The tracked quest's pointer on the compass and map: how it looks. Every tracked "
                    + "quest is pointed at; this is the one place its look is set.").add()
            .build();

    public QuestIndicatorAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The tracked pointer's look, or null when this file says nothing about it. */
    @Nullable
    public Pointer getPointer() {
        return pointer;
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

    /** {@link #of(String, QuestIndicatorSpec)} with the tracked pointer's look as well. */
    @Nonnull
    public static QuestIndicatorAsset of(@Nonnull String id, @Nonnull QuestIndicatorSpec spec,
            @Nullable Pointer pointer) {
        QuestIndicatorAsset a = of(id, spec);
        a.pointer = pointer;
        return a;
    }
}
