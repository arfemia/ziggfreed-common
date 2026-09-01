package com.ziggfreed.common.loot.stamp;

import java.util.Locale;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * What ONE stat is called on a stamped item, written as a file:
 * {@code Server/ZiggfreedCommon/StatDisplays/<StatId>.json}. The FILENAME is the stat id.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/StatDisplays/Swing_Speed.json
 * { "Key": "mymod.stats.swing_speed", "Color": "#e0b341" }
 * }</pre>
 *
 * <p>Most stats need no file at all. A stat the client already names is named correctly without one,
 * because the default naming reads the client's own {@code client.itemTooltip.stats.<StatId>} label
 * first. Author a file when a stat needs wording or a colour it would not otherwise get - a stat
 * whose tooltip label reads badly out of context, one the client has no label for, or one a server
 * simply wants worded its own way.
 *
 * <p>Both fields are optional and independent. {@code Key} alone renames without touching the
 * colour; {@code Color} alone tints the name the client already gives it. The key is passed a
 * {@code {value}} argument holding the signed points, so a label reads
 * {@code "Attack Speed {value}"}.
 *
 * <p>Being a file is the point: it wins over whatever a mod registered in code, so a server owner or
 * a pack can correct or translate a stat's wording without waiting on the mod that invented it.
 */
public final class StatDisplayAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatDisplayAsset>> {

    /** Where these files live, and the id the Asset Editor serves this type's pick list under. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/StatDisplays";
    public static final String EDITOR_DATASET = "ziggfreedcommon:statdisplays";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String key;
    @Nullable private String color;

    public static final AssetBuilderCodec<String, StatDisplayAsset> CODEC = AssetBuilderCodec.builder(
                    StatDisplayAsset.class,
                    StatDisplayAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Key", Codec.STRING, false),
                    (a, v) -> a.key = v, a -> a.key, (a, parent) -> a.key = parent.key)
            .documentation("The FULL translation key naming this stat, passed a {value} argument holding the "
                    + "signed points (so the value reads \"Attack Speed {value}\"). Omit to keep whatever name "
                    + "the stat already has.").add()
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, parent) -> a.color = parent.color)
            .documentation("A six-digit hex colour for the line, e.g. \"#e0b341\". Omit to leave the line "
                    + "uncoloured.").add()
            .build();

    public StatDisplayAsset() {
    }

    /** The stat id this file names, lower-cased from the filename. */
    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public String getKey() {
        return key;
    }

    @Nullable
    public String getColor() {
        return color;
    }
}
