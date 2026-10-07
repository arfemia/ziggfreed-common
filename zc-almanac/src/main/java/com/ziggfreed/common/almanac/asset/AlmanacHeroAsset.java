package com.ziggfreed.common.almanac.asset;

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
 * The top of a season's Almanac page, a season page's {@code Hero} group: a shipped image, a hero the
 * page composes from pictures the game already has, and the switch between them. Every leaf is
 * optional; a season that authors none composes its top from its own {@code Icon}.
 *
 * <pre>{@code
 * "Hero": {
 *   "Art": "UI/Custom/Almanac/Spring_Fair.png",
 *   "ShowArt": true,
 *   "Composition": {
 *     "Gradient": { "Top": "#1a2a4a", "Bottom": "#0a1119" },
 *     "Glow": { "Color": "#e8a93b", "X": 600, "Y": -60, "Size": 320 },
 *     "Items": [ { "Item": "Spring_Fair_Ribbon", "X": 700, "Y": 60, "Size": 96 } ] } }
 * }</pre>
 *
 * <p>The page draws, in order: the {@code Art} while {@code ShowArt} is true and the texture ships; else
 * the {@code Composition} when it has at least one item the server knows; else the season's own icon.
 * Inside the group, {@code Art} and {@code ShowArt} inherit one by one, so a server owner's
 * {@code almanac.json} can turn the art off for one season and keep the pack's composition. The
 * {@code Composition} is ONE piece: a layer that writes it (a child page, or the owner) replaces the
 * inherited one whole, and one written badly keeps the inherited one with a warning.
 */
public final class AlmanacHeroAsset {

    @Nullable private String art;
    @Nullable private Boolean showArt;
    @Nullable private Composition composition;

    public static final BuilderCodec<AlmanacHeroAsset> CODEC =
            BuilderCodec.builder(AlmanacHeroAsset.class, AlmanacHeroAsset::new)
                    .appendInherited(new KeyedCodec<>("Art", Codec.STRING, false),
                            (h, v) -> h.art = v, h -> h.art, (h, p) -> h.art = p.art)
                    .documentation("A picture shipped for the top of the season's page, as a path under Common/ "
                            + "(UI/Custom/Almanac/Spring_Fair.png), drawn 962 x 240; ship Name@2x.png at 1924 x 480 "
                            + "beside it. A picture the game cannot find is not drawn, and the page composes its top "
                            + "instead.").add()
                    .appendInherited(new KeyedCodec<>("ShowArt", Codec.BOOLEAN, false),
                            (h, v) -> h.showArt = v, h -> h.showArt, (h, p) -> h.showArt = p.showArt)
                    .metadata(EditorSchema.defaultValue(true))
                    .documentation("Whether the Art is drawn; unauthored means true. False composes the top from "
                            + "the Composition even when Art is set: a server owner's switch, per season, in "
                            + "mods/ziggfreedcommon/almanac.json.").add()
                    .appendInherited(new KeyedCodec<>("Composition",
                                    new WholeLeafCodec<>(Composition.CODEC, "hero's Composition"), false),
                            (h, v) -> h.composition = v, h -> h.composition, (h, p) -> h.composition = p.composition)
                    .documentation("A top for the page made from pictures the game already has: a colour or a "
                            + "gradient, an optional glow and texture, and item pictures placed on the 962 x 240 "
                            + "plate. Drawn when the Art is off or missing. One piece: a layer that writes it "
                            + "replaces the inherited one whole.").add()
                    .build();

    public AlmanacHeroAsset() {
    }

    /** The shipped picture's path, or null when none is named. */
    @Nullable
    public String art() {
        return blankToNull(art);
    }

    /** Whether the art is drawn when it is set; true when unauthored. */
    public boolean showArt() {
        return showArt == null || showArt;
    }

    /** The composed top, or null when none is authored. */
    @Nullable
    public Composition composition() {
        return composition;
    }

    /** The top composed from pictures the game has: colours, a glow, a texture and placed items. */
    public static final class Composition {

        @Nullable private String background;
        @Nullable private String backgroundTexture;
        @Nullable private Gradient gradient;
        @Nullable private Glow glow;
        @Nullable private Placement[] items;

        static final BuilderCodec<Composition> CODEC = BuilderCodec.builder(Composition.class, Composition::new)
                .append(new KeyedCodec<>("Background", Codec.STRING, false),
                        (c, v) -> c.background = v, c -> c.background)
                .documentation("A flat colour behind everything, #rrggbb. Unauthored takes the season's Accent, "
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
                .documentation("Item pictures placed on the plate, drawn in the order written, at most 12. An "
                        + "item the server does not have is skipped.").add()
                .build();

        public Composition() {
        }

        /** The flat colour as {@code #rrggbb} lower-case, or null when unauthored or not a colour. */
        @Nullable
        public String background() {
            return hex(background);
        }

        /** Whether a Background was written that is not a colour. */
        boolean backgroundMalformed() {
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
        boolean gradientMalformed() {
            return gradient != null && (gradient.top() == null || gradient.bottom() == null);
        }

        /** The glow, or null when unauthored or its colour is not one. */
        @Nullable
        public Glow glow() {
            return glow == null || glow.color() == null ? null : glow;
        }

        /** Whether a Glow was written with no usable colour. */
        boolean glowMalformed() {
            return glow != null && glow.color() == null;
        }

        /** The placements that name an item, in the order written (the reader caps and checks them). */
        @Nonnull
        public List<Placement> items() {
            if (items == null) {
                return List.of();
            }
            List<Placement> out = new ArrayList<>();
            for (Placement placement : items) {
                if (placement != null && placement.item() != null) {
                    out.add(placement);
                }
            }
            return List.copyOf(out);
        }
    }

    /** A vertical sky: the colour at the plate's top edge and the one at its bottom edge. */
    public static final class Gradient {

        @Nullable private String top;
        @Nullable private String bottom;

        static final BuilderCodec<Gradient> CODEC = BuilderCodec.builder(Gradient.class, Gradient::new)
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

        static final BuilderCodec<Glow> CODEC = BuilderCodec.builder(Glow.class, Glow::new)
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

    /** One item picture on the plate: which item, where its top-left corner sits, and how big it is. */
    public static final class Placement {

        @Nullable private String item;
        @Nullable private Integer x;
        @Nullable private Integer y;
        @Nullable private Integer size;

        static final BuilderCodec<Placement> CODEC = BuilderCodec.builder(Placement.class, Placement::new)
                .append(new KeyedCodec<>("Item", Codec.STRING, false), (p, v) -> p.item = v, p -> p.item)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item whose own picture is drawn. One the server does not have is skipped.").add()
                .append(new KeyedCodec<>("X", Codec.INTEGER, false), (p, v) -> p.x = v, p -> p.x)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The picture's left edge, in pixels from the plate's left (the plate is 962 "
                        + "wide); kept on the plate.").add()
                .append(new KeyedCodec<>("Y", Codec.INTEGER, false), (p, v) -> p.y = v, p -> p.y)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The picture's top edge, in pixels from the plate's top (the plate is 240 "
                        + "high); kept on the plate.").add()
                .append(new KeyedCodec<>("Size", Codec.INTEGER, false), (p, v) -> p.size = v, p -> p.size)
                .metadata(EditorSchema.defaultValue(64L))
                .documentation("The picture's side in pixels, 24 to 128; 64 is an item picture's own size.").add()
                .build();

        public Placement() {
        }

        /** The item id, trimmed, or null when blank. */
        @Nullable
        public String item() {
            return blankToNull(item);
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

    /** {@code value} as a lower-case {@code #rrggbb}, or null when it is not one. */
    @Nullable
    static String hex(@Nullable String value) {
        String trimmed = blankToNull(value);
        return trimmed != null && UiRetint.isSixDigitHex(trimmed) ? trimmed.toLowerCase(Locale.ROOT) : null;
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
