package com.ziggfreed.common.entity.overhead;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.icon.IconSpec;

/**
 * What ONE overhead state looks like, written as content:
 * {@code Server/ZiggfreedCommon/OverheadIndicators/<State_Id>.json}. The state id is the FILENAME,
 * spelled {@code Is_Like_This}, and it is the same id a consumer hands to
 * {@link OverheadIndicators#show}; a later-loaded pack's same-id file replaces it whole.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/OverheadIndicators/Quest_Available.json
 * {
 *   "$Comment": "A scroll over a character who has a quest the player could take.",
 *   "Icon": { "ItemId": "Deco_Scroll" },
 *   "Scale": 0.5,
 *   "Offset": { "Y": 0.45 },
 *   "Spin": true
 * }
 * }</pre>
 *
 * <p>The picture is the ONE picture reference the whole library reads ({@link IconSpec}): an item
 * id floats as that item, the way a dropped item does; a Common-rooted texture path is drawn on a
 * flat card that reads from every side. The card is meant for a SQUARE picture (64 by 64 pixels
 * fills it exactly), and the path has to sit under one of the roots the client loads models and
 * textures from ({@code Items/}, {@code NPC/}, {@code Characters/}, {@code VFX/}). A file naming
 * neither shows nothing, and the library says so once in the log for that id.
 */
public final class OverheadIndicatorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, OverheadIndicatorAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/OverheadIndicators";

    /** The size an unauthored look is drawn at: half an item, a cue rather than a prop. */
    public static final float DEFAULT_SCALE = 0.5f;

    /** How far above the host's anchor an unauthored look floats, in blocks. */
    public static final double DEFAULT_LIFT = 0.45;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private IconSpec icon;
    @Nullable private Float scale;
    @Nullable private Vec3 offset;
    @Nullable private Boolean spin;

    public static final AssetBuilderCodec<String, OverheadIndicatorAsset> CODEC = AssetBuilderCodec.builder(
                    OverheadIndicatorAsset.class,
                    OverheadIndicatorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Icon", IconSpec.CODEC, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .documentation("The picture: an item id floats as that item, a Common-rooted texture path is "
                    + "drawn on a flat card. ItemId wins when both are set. A square 64 by 64 picture "
                    + "fills the card exactly.").add()
            .appendInherited(new KeyedCodec<>("Scale", Codec.FLOAT, false),
                    (a, v) -> a.scale = v, a -> a.scale, (a, p) -> a.scale = p.scale)
            .metadata(EditorSchema.defaultValue(DEFAULT_SCALE))
            .documentation("How big it is drawn: 1 is a full-size item or a one-block card. Unauthored "
                    + "means 0.5.").add()
            .appendInherited(new KeyedCodec<>("Offset", Vec3.CODEC, false),
                    (a, v) -> a.offset = v, a -> a.offset, (a, p) -> a.offset = p.offset)
            .documentation("Where it floats, in blocks, measured from the top of the host's head: Y lifts "
                    + "it higher, X and Z shift it sideways. An unauthored Y means 0.45; unauthored X "
                    + "and Z mean straight above.").add()
            .appendInherited(new KeyedCodec<>("Spin", Codec.BOOLEAN, false),
                    (a, v) -> a.spin = v, a -> a.spin, (a, p) -> a.spin = p.spin)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether an item look turns and bobs the way a dropped item does. The motion "
                    + "is the client's own and costs the server nothing. Unauthored means true; a "
                    + "card never spins.").add()
            .build();

    public OverheadIndicatorAsset() {
    }

    /** The state id this file describes, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public IconSpec getIcon() {
        return icon;
    }

    @Nullable
    public Float getScale() {
        return scale;
    }

    @Nullable
    public Vec3 getOffset() {
        return offset;
    }

    @Nullable
    public Boolean getSpin() {
        return spin;
    }

    /** True when the file names a picture that can be drawn at all. */
    public boolean hasLook() {
        return icon != null && !icon.isEmpty();
    }

    /** The size to draw at, floored to a positive number so the engine's own scale guard never throws. */
    public float effectiveScale() {
        return scale == null || scale <= 0f ? DEFAULT_SCALE : scale;
    }

    /** The sideways shift along X, in blocks. */
    public double offsetX() {
        return offset == null ? 0d : offset.effectiveX();
    }

    /** How far above the host's anchor it floats, in blocks. */
    public double offsetY() {
        return offset == null || offset.getY() == null ? DEFAULT_LIFT : offset.getY();
    }

    /** The sideways shift along Z, in blocks. */
    public double offsetZ() {
        return offset == null ? 0d : offset.effectiveZ();
    }

    /** Whether an item look plays the client's dropped-item idle motion. */
    public boolean spins() {
        return spin == null || spin;
    }

    /** Java-side factory, for a test or a consumer registering a look without a file. */
    @Nonnull
    public static OverheadIndicatorAsset of(@Nonnull String id, @Nullable IconSpec icon, @Nullable Float scale,
            @Nullable Vec3 offset, @Nullable Boolean spin) {
        OverheadIndicatorAsset a = new OverheadIndicatorAsset();
        a.id = id;
        a.icon = icon;
        a.scale = scale;
        a.offset = offset;
        a.spin = spin;
        return a;
    }
}
