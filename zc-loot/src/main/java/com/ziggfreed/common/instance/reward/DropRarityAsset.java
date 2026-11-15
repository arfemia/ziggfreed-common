package com.ziggfreed.common.instance.reward;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One rarity band a reward reads as from its drop's chance ({@link DropRarity}), as a file:
 * {@code Server/ZiggfreedCommon/DropRarities/<Quality>.json}, the filename the game's own item quality id the band
 * reads as (its slot, word and colour on a reward chest's card), and {@code AtMostPercent} the chance, per chest, a
 * drop may have at most to read as it. A drop reads as the band with the least {@code AtMostPercent} its chance is
 * within; a chance above every band reads as the widest.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/DropRarities/Legendary.json
 * { "AtMostPercent": 1 }
 * }</pre>
 *
 * <p>The library ships the five vanilla qualities (Legendary 1, Epic 5, Rare 20, Uncommon 50, Common 100). A pack
 * adds a band for a quality of its own with one file, or retunes one by its id; a server owner does either in
 * {@code mods/ziggfreedcommon/drop-rarities.json} ({@link DropRarityOwnerLayers}), and {@code Enabled: false} takes a
 * band out.
 */
public final class DropRarityAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DropRarityAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DropRarities";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Double atMostPercent;
    @Nullable private Boolean enabled;

    public static final AssetBuilderCodec<String, DropRarityAsset> CODEC = AssetBuilderCodec.builder(
                    DropRarityAsset.class,
                    DropRarityAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("AtMostPercent", Codec.DOUBLE, false),
                    (a, v) -> a.atMostPercent = v, a -> a.atMostPercent, (a, p) -> a.atMostPercent = p.atMostPercent)
            .documentation("The chance, in percent per chest (0 to 100), a drop may have at most to read as this "
                    + "band's quality, the file's name. A drop reads as the band with the least AtMostPercent its "
                    + "chance is within, and a chance above every band as the widest. Unauthored, the band is never "
                    + "read.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("False takes the band out, so its drops read as the next band up. Unauthored means "
                    + "true.").add()
            .build();

    public DropRarityAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The item quality id this band reads as: the file's own id. */
    @Nullable
    public String quality() {
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** {@code AtMostPercent} as written, or null for a band that never reads. */
    @Nullable
    public Double atMostPercent() {
        return atMostPercent == null || !Double.isFinite(atMostPercent) ? null : atMostPercent;
    }

    /** {@code Enabled}: whether the band reads at all; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
