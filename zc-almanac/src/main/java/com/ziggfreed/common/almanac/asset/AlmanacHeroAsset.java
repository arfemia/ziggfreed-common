package com.ziggfreed.common.almanac.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.page.PlateComposition;

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
 *     "Items": [ { "Item": "Spring_Fair_Ribbon", "X": 700, "Y": 60, "Size": 96 },
 *                { "Npc": "Spring_Fair_Host", "X": 820, "Y": 40, "Size": 128 } ] } }
 * }</pre>
 *
 * <p>The page draws, in order: the {@code Art} while {@code ShowArt} is true and the texture ships; else
 * the {@code Composition} when it has at least one picture the server knows (an item, or an NPC role's
 * portrait); else the season's own icon.
 * Inside the group, {@code Art} and {@code ShowArt} inherit one by one, so a server owner's
 * {@code almanac.json} can turn the art off for one season and keep the pack's composition. The
 * {@code Composition} is the page kit's {@link PlateComposition}, ONE piece: a layer that writes it (a child
 * page, or the owner) replaces the inherited one whole, and one written badly keeps the inherited one with a
 * warning.
 */
public final class AlmanacHeroAsset {

    @Nullable private String art;
    @Nullable private Boolean showArt;
    @Nullable private PlateComposition composition;

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
                    .appendInherited(new KeyedCodec<>("Composition", PlateComposition.whole("hero's Composition"),
                                    false),
                            (h, v) -> h.composition = v, h -> h.composition, (h, p) -> h.composition = p.composition)
                    .documentation("A top for the page made from pictures the game already has: a colour or a "
                            + "gradient, an optional glow and texture, and item or NPC pictures placed on the 962 x "
                            + "240 plate. Drawn when the Art is off or missing. One piece: a layer that writes it "
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
    public PlateComposition composition() {
        return composition;
    }

    /** {@code value} as a lower-case {@code #rrggbb}, or null when it is not one. */
    @Nullable
    static String hex(@Nullable String value) {
        return PlateComposition.hex(value);
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
