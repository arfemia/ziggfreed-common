package com.ziggfreed.common.dialogue.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

import com.ziggfreed.common.dialogue.DialogueOptionTheme;

/**
 * Pack-authorable, mod-agnostic look of ONE dialogue-option style kind, loaded from
 * {@code Server/ZiggfreedCommon/DialogueOptionTheme/<Kind>.json} (the filename - accept / turnin
 * / continue / neutral / farewell - is the id, matching
 * {@link com.ziggfreed.common.dialogue.DialogueOptionStyle#key()}). Lifts the tint + glyph the
 * {@code DialogueOptionStyle} enum previously hard-coded into authorable data, resolved
 * {@code defaults < pack < owner} by {@link com.ziggfreed.common.dialogue.DialogueOptionThemeConfig}.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors {@code PartySettingsAsset} /
 * {@code InstancePresetAsset}). The engine decodes it DIRECTLY into typed fields via {@link #CODEC}
 * - the codec IS the single schema authority on both the pack and owner layers; there is NO raw
 * {@code Payload}. Every {@code KeyedCodec} field name is PascalCase (the constructor rejects a
 * lower-case first letter at static init; {@code AssetCodecInitTest} guards it).
 *
 * <p>Pack JSON shape (all fields optional; absent = inherit the enum default for that kind):
 * <pre>{@code
 * { "Name": "accept",
 *   "Color": "#4aff7f", "HoverColor": "#7affa0", "PressColor": "#33cc66", "Glyph": "accept" }
 * }</pre>
 *
 * <ul>
 *   <li>{@code Color} - the default button tint ({@code #rrggbb}).</li>
 *   <li>{@code HoverColor} / {@code PressColor} - the hover / press tints; omit either to derive it
 *       from {@code Color} (lightened / darkened) at paint time.</li>
 *   <li>{@code Glyph} - the leading glyph token (accept / turnin / continue / open / farewell);
 *       omit to keep the kind's default glyph.</li>
 * </ul>
 */
public final class DialogueOptionThemeAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, DialogueOptionThemeAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String color;
    @Nullable private String hoverColor;
    @Nullable private String pressColor;
    @Nullable private String glyph;

    public static final AssetBuilderCodec<String, DialogueOptionThemeAsset> CODEC = AssetBuilderCodec.builder(
                    DialogueOptionThemeAsset.class,
                    DialogueOptionThemeAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative key is
            // the filename) - a no-op setter so it does not trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("Color", Codec.STRING, false), (a, v) -> a.color = v, a -> a.color)
            .add()
            .append(new KeyedCodec<>("HoverColor", Codec.STRING, false), (a, v) -> a.hoverColor = v, a -> a.hoverColor)
            .add()
            .append(new KeyedCodec<>("PressColor", Codec.STRING, false), (a, v) -> a.pressColor = v, a -> a.pressColor)
            .add()
            .append(new KeyedCodec<>("Glyph", Codec.STRING, false), (a, v) -> a.glyph = v, a -> a.glyph)
            .add()
            .build();

    public DialogueOptionThemeAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The resolved runtime theme (nullable leaves pass through - the page inherits the enum per leaf). */
    @Nonnull
    public DialogueOptionTheme toTheme() {
        return new DialogueOptionTheme(color, hoverColor, pressColor, glyph);
    }
}
