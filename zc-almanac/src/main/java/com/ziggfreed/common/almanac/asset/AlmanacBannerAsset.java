package com.ziggfreed.common.almanac.asset;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * An inline banner in a season page's {@code Sections}: a plate the page's full width and {@code Height}
 * tall, showing a shipped picture ({@code Art}), else one composed from pictures the game has
 * ({@code Composition}, the hero's own shape on the banner's plate), else the season's accent, darkened;
 * with a title and a line over it and an optional button.
 *
 * <pre>{@code
 * { "Banner": { "Height": 120,
 *     "Composition": { "Gradient": { "Top": "#0a0f1e", "Bottom": "#2a1a2c" },
 *                      "Items": [ { "Item": "Spring_Fair_Ribbon", "X": 760, "Y": 8, "Size": 96 } ] },
 *     "Text": { "TitleKey": "almanac.spring_fair.banner.title", "FlavorKey": "almanac.spring_fair.banner.line" },
 *     "Button": { "TextKey": "almanac.spring_fair.banner.button",
 *                 "Destination": { "Type": "Shop", "Shop": "Spring_Fair_Stall" } } } }
 * }</pre>
 *
 * <p>A banner is an entry of an array, so it inherits nothing piece by piece: a layer that writes
 * {@code Sections} writes every banner in it whole.
 */
public final class AlmanacBannerAsset {

    @Nullable private String art;
    @Nullable private Integer height;
    @Nullable private AlmanacHeroAsset.Composition composition;
    @Nullable private ContentTextAsset text;
    @Nullable private AlmanacLinkAsset button;

    public static final BuilderCodec<AlmanacBannerAsset> CODEC =
            BuilderCodec.builder(AlmanacBannerAsset.class, AlmanacBannerAsset::new)
                    .append(new KeyedCodec<>("Art", Codec.STRING, false), (b, v) -> b.art = v, b -> b.art)
                    .documentation("A picture under Common/ drawn across the banner, 906 wide and Height tall; "
                            + "ship Name@2x.png beside it. One the game cannot find is not drawn.").add()
                    .append(new KeyedCodec<>("Height", Codec.INTEGER, false), (b, v) -> b.height = v, b -> b.height)
                    .metadata(EditorSchema.defaultValue(120L))
                    .documentation("The banner's height in pixels, 64 to 240; it is always the page's full "
                            + "width.").add()
                    .append(new KeyedCodec<>("Composition",
                                    new WholeLeafCodec<>(AlmanacHeroAsset.Composition.CODEC, "banner's Composition"),
                                    false),
                            (b, v) -> b.composition = v, b -> b.composition)
                    .documentation("A banner made from pictures the game has, as the top of the page is: a "
                            + "colour or a gradient, a glow, a texture and item pictures placed on the 906 x Height "
                            + "plate. Drawn when the Art is missing. Written badly, it is left out with a "
                            + "warning.").add()
                    .append(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false), (b, v) -> b.text = v, b -> b.text)
                    .documentation("The banner's title (TitleKey) and the line under it (FlavorKey), as "
                            + "localization keys.").add()
                    .append(new KeyedCodec<>("Button", AlmanacLinkAsset.CODEC, false),
                            (b, v) -> b.button = v, b -> b.button)
                    .documentation("A button on the banner: its words and what it opens, in the shared "
                            + "destination vocabulary, for example a storefront: { \"Type\": \"Shop\", \"Shop\": "
                            + "\"<storefront id>\" }. A button with no words, or one opening a screen no installed "
                            + "mod registers, is left out.").add()
                    .build();

    public AlmanacBannerAsset() {
    }

    /** The shipped picture's path, or null when none is named. */
    @Nullable
    public String art() {
        return AlmanacHeroAsset.blankToNull(art);
    }

    /** The height as written, or null (the view reads the unauthored height and keeps it to its range). */
    @Nullable
    public Integer height() {
        return height;
    }

    /** The composed plate, or null when none is authored or it would not read. */
    @Nullable
    public AlmanacHeroAsset.Composition composition() {
        return composition;
    }

    /** The banner's title key, or null. */
    @Nullable
    public String titleKey() {
        return text == null ? null : AlmanacHeroAsset.blankToNull(text.getTitleKey());
    }

    /** The key of the line under the title, or null. */
    @Nullable
    public String flavorKey() {
        return text == null ? null : AlmanacHeroAsset.blankToNull(text.getFlavorKey());
    }

    /** The button, when it has words and somewhere this server can open; else null. */
    @Nullable
    public AlmanacLinkAsset button() {
        return button != null && button.usable() ? button : null;
    }

    /** Whether a button was written that has no words or nowhere this server can open. */
    public boolean buttonLeftOut() {
        return button != null && !button.usable();
    }

    /** Whether the banner names nothing at all to show: no Art, no Composition, no Text key and no Button. */
    public boolean nothingWritten() {
        return art() == null && composition == null && titleKey() == null && flavorKey() == null && button == null;
    }
}
