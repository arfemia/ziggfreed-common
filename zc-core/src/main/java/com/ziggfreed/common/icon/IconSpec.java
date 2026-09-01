package com.ziggfreed.common.icon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The ONE picture reference every surface in this library and its consumers reads: a nested
 * {@code "Icon": { "ItemId": ..., "TexturePath": ... }} group with its own {@link BuilderCodec},
 * so a new icon-bearing asset adds one codec append
 * ({@code new KeyedCodec<>("Icon", IconSpec.CODEC, false)}) instead of inventing an icon schema.
 *
 * <p>A picture is EITHER an item id, drawn as that item's own generated icon (the widest coverage,
 * and exactly the id {@code /give} takes), OR a Common-rooted texture path. Both leaves are
 * nullable and INDEPENDENT; when both are set the ITEM ID wins, which is settled once at the render
 * seam rather than per caller. An absent group, or two blank leaves, means "no picture" - and a row
 * that cannot be pictured renders as its text alone rather than borrowing an unrelated thing's
 * picture, which would read as a promise of that thing.
 *
 * <p>This value knows nothing about drawing. {@code ui/icon/IconRenderer} is what paints one.
 */
public final class IconSpec {

    public static final BuilderCodec<IconSpec> CODEC = BuilderCodec.builder(IconSpec.class, IconSpec::new)
            .documentation("A picture: either the item whose generated icon to draw, or a Common-rooted "
                    + "texture path. Leave both blank for no picture.")
            .append(new KeyedCodec<>("ItemId", Codec.STRING, false), (i, v) -> i.itemId = v, i -> i.itemId)
            .documentation("The item whose generated icon to draw, e.g. \"Armor_Bronze_Chest\" - the same id "
                    + "/give takes. Wins over TexturePath when both are set.").add()
            .append(new KeyedCodec<>("TexturePath", Codec.STRING, false),
                    (i, v) -> i.texturePath = v, i -> i.texturePath)
            .documentation("A Common-rooted texture path to draw instead, e.g. "
                    + "\"UI/StatusEffects/Stamina.png\". Used only when ItemId is blank.").add()
            .build();

    @Nullable private String itemId;
    @Nullable private String texturePath;

    public IconSpec() {
    }

    /** A picture drawn from an item's own generated icon. */
    @Nonnull
    public static IconSpec ofItem(@Nullable String itemId) {
        IconSpec spec = new IconSpec();
        spec.itemId = itemId;
        return spec;
    }

    /** A picture drawn from a Common-rooted texture path. */
    @Nonnull
    public static IconSpec ofTexture(@Nullable String texturePath) {
        IconSpec spec = new IconSpec();
        spec.texturePath = texturePath;
        return spec;
    }

    /** The item id to draw; {@code null}/blank = none. Wins over {@link #texturePath()}. */
    @Nullable
    public String itemId() {
        return blankToNull(itemId);
    }

    /** The texture path to draw; {@code null}/blank = none. Used only when {@link #itemId()} is null. */
    @Nullable
    public String texturePath() {
        return blankToNull(texturePath);
    }

    /** Is there anything here to draw at all? */
    public boolean isEmpty() {
        return itemId() == null && texturePath() == null;
    }

    @Nullable
    private static String blankToNull(@Nullable String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
