package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One group of things on a Records tab's split, as a file: {@code Server/ZiggfreedCommon/RecordGroups/<Id>.json}.
 * A group is one segment of the split's row (the Bosses tab's Seasonal, Dungeon, World, Minigame), which shows while
 * it holds at least one thing with a record; a thing names its group by this file's id (a boss through its binding
 * row's {@code Leaderboard.Group}), and a thing naming no group, or one no file defines, shows under the first
 * choice alone. A pack adds a group by shipping a file.
 *
 * <pre>{@code
 * {"NameKey":"ziggfreedcommon.statboard.group.seasonal","Order":10,"Icon":"Plant_Crop_Pumpkin_Block"}
 * }</pre>
 */
public final class RecordGroupAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, RecordGroupAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/RecordGroups";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String nameKey;
    @Nullable private Integer order;
    @Nullable private String icon;

    public static final AssetBuilderCodec<String, RecordGroupAsset> CODEC = AssetBuilderCodec.builder(
                    RecordGroupAsset.class,
                    RecordGroupAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, parent) -> a.nameKey = parent.nameKey)
            .documentation("The translation key the group's segment is named by. Omit it and the segment shows the "
                    + "file's id.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, parent) -> a.order = parent.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the group's segment sits among the others, lowest first.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, parent) -> a.icon = parent.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the group where a page pictures it. Omit it for none.")
            .add()
            .build();

    public RecordGroupAsset() {
    }

    /** The group id, lower-cased from the filename. */
    @Override
    public String getId() {
        return id;
    }

    /** The key the group is named by, or null when the file names none. */
    @Nullable
    public String nameKey() {
        return nameKey == null || nameKey.isBlank() ? null : nameKey.trim();
    }

    /** Where the group sits, lowest first; 0 when unauthored. */
    public int order() {
        return order == null ? 0 : order;
    }

    /** The item the group is pictured by, or null when the file names none. */
    @Nullable
    public String icon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** The group as a split shows it, under {@code groupId}. */
    @Nonnull
    public RecordGroup toGroup(@Nonnull String groupId) {
        return new RecordGroup(groupId.toLowerCase(Locale.ROOT), nameKey(), order(), icon());
    }
}
