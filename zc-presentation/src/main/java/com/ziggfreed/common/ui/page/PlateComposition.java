package com.ziggfreed.common.ui.page;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.UiRetint;

/**
 * A plate composed from pictures the game already has: a flat colour or a vertical sky, an optional
 * glow and texture, and item or NPC pictures placed on the plate. A page's hero and its inline banners
 * author this one shape, and the kit's {@code ui/kit/PlatePainter} paints what a page reads from it.
 *
 * <pre>{@code
 * "Composition": {
 *   "Gradient": { "Top": "#1a2a4a", "Bottom": "#0a1119" },
 *   "Glow": { "Color": "#e8a93b", "X": 600, "Y": -60, "Size": 320 },
 *   "Items": [ { "Item": "Spring_Fair_Ribbon", "X": 700, "Y": 60, "Size": 96 },
 *              { "Npc": "Spring_Fair_Host", "X": 820, "Y": 40, "Size": 128 } ] }
 * }</pre>
 *
 * <p>Under {@code Parent} and in an owner's file a composition is ONE piece ({@link #whole}): a layer that
 * writes it replaces the inherited one whole, and one written badly keeps the inherited one with a warning.
 */
public final class PlateComposition {

    /** How many pictures a composed plate draws. */
    public static final int MAX_PICTURES = 12;

    @Nullable private String background;
    @Nullable private String backgroundTexture;
    @Nullable private Gradient gradient;
    @Nullable private Glow glow;
    @Nullable private Placement[] items;

    public static final BuilderCodec<PlateComposition> CODEC =
            BuilderCodec.builder(PlateComposition.class, PlateComposition::new)
                    .append(new KeyedCodec<>("Background", Codec.STRING, false),
                            (c, v) -> c.background = v, c -> c.background)
                    .documentation("A flat colour behind everything, #rrggbb. Unauthored takes the page's accent, "
                            + "darkened; a Gradient supersedes it.").add()
                    .append(new KeyedCodec<>("BackgroundTexture", Codec.STRING, false),
                            (c, v) -> c.backgroundTexture = v, c -> c.backgroundTexture)
                    .documentation("A picture under the items, as a path under Common/ (a fade, a pattern), drawn "
                            + "over the colour. One the game cannot find is left out.").add()
                    .append(new KeyedCodec<>("Gradient", Gradient.CODEC, false),
                            (c, v) -> c.gradient = v, c -> c.gradient)
                    .documentation("A vertical sky from Top (the plate's top edge) to Bottom (its bottom edge). Both "
                            + "colours are needed; when set it supersedes Background.").add()
                    .append(new KeyedCodec<>("Glow", Glow.CODEC, false),
                            (c, v) -> c.glow = v, c -> c.glow)
                    .documentation("A soft round light drawn under the items, in a square box placed by its top-left "
                            + "corner; the box may hang past the plate's edges, and only the plate shows.").add()
                    .append(new KeyedCodec<>("Items", new ArrayCodec<>(Placement.CODEC, Placement[]::new), false),
                            (c, v) -> c.items = v, c -> c.items)
                    .documentation("Pictures placed on the plate, each an item's (Item) or an NPC's portrait (Npc), "
                            + "drawn in the order written, so a later one lies over an earlier one; at most 12. An "
                            + "item or an NPC role the server does not have is skipped.").add()
                    .build();

    public PlateComposition() {
    }

    /**
     * The composition as a field that is one piece under {@code Parent} and the owner's file; {@code noun} names
     * it in the warning a badly written one gives ({@code "hero's Composition"}).
     */
    @Nonnull
    public static Codec<PlateComposition> whole(@Nonnull String noun) {
        return new WholeLeafCodec<>(CODEC, noun);
    }

    /** The flat colour as {@code #rrggbb} lower-case, or null when unauthored or not a colour. */
    @Nullable
    public String background() {
        return hex(background);
    }

    /** Whether a Background was written that is not a colour. */
    public boolean backgroundMalformed() {
        return blankToNull(background) != null && hex(background) == null;
    }

    /** The picture under the items, or null. */
    @Nullable
    public String backgroundTexture() {
        return blankToNull(backgroundTexture);
    }

    /** The sky, or null when unauthored or either colour is not one. */
    @Nullable
    public Gradient gradient() {
        return gradient == null || gradient.top() == null || gradient.bottom() == null ? null : gradient;
    }

    /** Whether a Gradient was written with a colour missing or malformed. */
    public boolean gradientMalformed() {
        return gradient != null && (gradient.top() == null || gradient.bottom() == null);
    }

    /** The glow, or null when unauthored or its colour is not one. */
    @Nullable
    public Glow glow() {
        return glow == null || glow.color() == null ? null : glow;
    }

    /** Whether a Glow was written with no usable colour. */
    public boolean glowMalformed() {
        return glow != null && glow.color() == null;
    }

    /**
     * The placements that name an item or an NPC, in the order written (the reader caps and checks them); one
     * naming both draws its item ({@link Placement#item()} wins).
     */
    @Nonnull
    public List<Placement> items() {
        if (items == null) {
            return List.of();
        }
        List<Placement> out = new ArrayList<>();
        for (Placement placement : items) {
            if (placement != null && (placement.item() != null || placement.npc() != null)) {
                out.add(placement);
            }
        }
        return List.copyOf(out);
    }

    /** A vertical sky: the colour at the plate's top edge and the one at its bottom edge. */
    public static final class Gradient {

        @Nullable private String top;
        @Nullable private String bottom;

        public static final BuilderCodec<Gradient> CODEC = BuilderCodec.builder(Gradient.class, Gradient::new)
                .append(new KeyedCodec<>("Top", Codec.STRING, false), (g, v) -> g.top = v, g -> g.top)
                .documentation("The colour at the plate's top edge, #rrggbb.").add()
                .append(new KeyedCodec<>("Bottom", Codec.STRING, false), (g, v) -> g.bottom = v, g -> g.bottom)
                .documentation("The colour at the plate's bottom edge, #rrggbb.").add()
                .build();

        public Gradient() {
        }

        /** The top colour as {@code #rrggbb} lower-case, or null. */
        @Nullable
        public String top() {
            return hex(top);
        }

        /** The bottom colour as {@code #rrggbb} lower-case, or null. */
        @Nullable
        public String bottom() {
            return hex(bottom);
        }
    }

    /** A soft round light: its colour and the square box it fills, placed by the box's top-left corner. */
    public static final class Glow {

        @Nullable private String color;
        @Nullable private Integer x;
        @Nullable private Integer y;
        @Nullable private Integer size;

        public static final BuilderCodec<Glow> CODEC = BuilderCodec.builder(Glow.class, Glow::new)
                .append(new KeyedCodec<>("Color", Codec.STRING, false), (g, v) -> g.color = v, g -> g.color)
                .documentation("The light's colour, #rrggbb. A glow with no colour is not drawn.").add()
                .append(new KeyedCodec<>("X", Codec.INTEGER, false), (g, v) -> g.x = v, g -> g.x)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The box's left edge from the plate's left, -480 to 1442: it may hang past "
                        + "either side.").add()
                .append(new KeyedCodec<>("Y", Codec.INTEGER, false), (g, v) -> g.y = v, g -> g.y)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The box's top edge from the plate's top, -480 to 720: it may hang past the "
                        + "top or the bottom.").add()
                .append(new KeyedCodec<>("Size", Codec.INTEGER, false), (g, v) -> g.size = v, g -> g.size)
                .metadata(EditorSchema.defaultValue(240L))
                .documentation("The box's side in pixels, 32 to 480.").add()
                .build();

        public Glow() {
        }

        /** The colour as {@code #rrggbb} lower-case, or null. */
        @Nullable
        public String color() {
            return hex(color);
        }

        /** The box's left edge as written, or null. */
        @Nullable
        public Integer x() {
            return x;
        }

        /** The box's top edge as written, or null. */
        @Nullable
        public Integer y() {
            return y;
        }

        /** The box's side as written, or null. */
        @Nullable
        public Integer size() {
            return size;
        }
    }

    /**
     * One picture on the plate: what it shows (an item's own picture, or an NPC role's portrait), where its
     * top-left corner sits, and how big it is. A placement names one of {@code Item} and {@code Npc}; one naming
     * both draws the item and ignores the NPC, which the page's audit reports.
     */
    public static final class Placement {

        @Nullable private String item;
        @Nullable private String npc;
        @Nullable private Integer x;
        @Nullable private Integer y;
        @Nullable private Integer size;

        public static final BuilderCodec<Placement> CODEC = BuilderCodec.builder(Placement.class, Placement::new)
                .append(new KeyedCodec<>("Item", Codec.STRING, false), (p, v) -> p.item = v, p -> p.item)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item whose own picture is drawn. One the server does not have is skipped. "
                        + "Write Item or Npc, not both: with both, the item is drawn and the Npc is ignored.").add()
                .append(new KeyedCodec<>("Npc", Codec.STRING, false), (p, v) -> p.npc = v, p -> p.npc)
                .documentation("An NPC role id (the role file's name) whose portrait is drawn in place of an item: "
                        + "the still of the model the role wears, Icons/ModelsGenerated/<model>.png, the picture "
                        + "the game's Memories page shows. A role the server does not have is skipped; a role "
                        + "whose model has no still shows the game's missing-picture icon. Used only when the "
                        + "placement names no Item: with both, the item is drawn and this is ignored.").add()
                .append(new KeyedCodec<>("X", Codec.INTEGER, false), (p, v) -> p.x = v, p -> p.x)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The picture's left edge, in pixels from the plate's left (a page's hero plate is "
                        + "962 wide); kept on the plate.").add()
                .append(new KeyedCodec<>("Y", Codec.INTEGER, false), (p, v) -> p.y = v, p -> p.y)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The picture's top edge, in pixels from the plate's top (a page's hero plate is "
                        + "240 high); kept on the plate.").add()
                .append(new KeyedCodec<>("Size", Codec.INTEGER, false), (p, v) -> p.size = v, p -> p.size)
                .metadata(EditorSchema.defaultValue(64L))
                .documentation("The picture's side in pixels, 24 to 128; 64 is an item picture's own size, 128 a "
                        + "portrait's.").add()
                .build();

        public Placement() {
        }

        /** The item id, trimmed, or null when blank. */
        @Nullable
        public String item() {
            return blankToNull(item);
        }

        /** The NPC role id, trimmed, or null when blank; drawn only when no item is named. */
        @Nullable
        public String npc() {
            return blankToNull(npc);
        }

        /** Whether the placement names both an item and an NPC (the item is drawn, the NPC ignored). */
        public boolean namesBoth() {
            return item() != null && npc() != null;
        }

        /** The left edge as written, or null. */
        @Nullable
        public Integer x() {
            return x;
        }

        /** The top edge as written, or null. */
        @Nullable
        public Integer y() {
            return y;
        }

        /** The side as written, or null. */
        @Nullable
        public Integer size() {
            return size;
        }
    }

    /** {@code value} as a lower-case {@code #rrggbb}, or null when it is not one: how a page reads a colour leaf. */
    @Nullable
    public static String hex(@Nullable String value) {
        String trimmed = blankToNull(value);
        return trimmed != null && UiRetint.isSixDigitHex(trimmed) ? trimmed.toLowerCase(Locale.ROOT) : null;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
