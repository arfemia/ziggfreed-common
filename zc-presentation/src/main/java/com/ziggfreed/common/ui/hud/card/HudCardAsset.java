package com.ziggfreed.common.ui.hud.card;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The ONE shared look every HUD card this family draws reads: the colour and the transparency of
 * the card's frame, as one hex. A card is any panel wearing a shipped frame on the keyed HUD base:
 * both progress-bar panels, the tracked-quest tracker, and a mod's own panel built on the same
 * base (RPG Stations' session summary reads it). The FILE NAME is the record's id, and the record
 * every card reads is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudCards/Default.json}, which this library ships
 * with the identity value; a consumer's same-id file wins by pack order, and the owner layer is
 * {@code mods/ziggfreedcommon/hud-cards.json}:
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-cards.json
 * { "Default": { "Color": "#ffffffb8" } }
 * }</pre>
 *
 * <p>One leaf, {@code Color}, the hex {@link HudCardLook} explains: it multiplies the shipped
 * frame, so {@code #ffffff} is exactly the shipped look; eight digits carry an alpha in the last
 * two, so {@code #ffffffb8} dims every card to about 72 percent at once. A card overrides it
 * through a leaf of its own (a bar spot's or a bar panel's {@code Color}, a mod's own settings),
 * and a card whose colour resolves to the identity pushes nothing.
 */
public final class HudCardAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, HudCardAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudCards";

    /** The id of the record every card reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String color;

    public static final AssetBuilderCodec<String, HudCardAsset> CODEC = AssetBuilderCodec.builder(
                    HudCardAsset.class,
                    HudCardAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, p) -> a.color = p.color)
            .metadata(EditorSchema.defaultValue(HudCardLook.IDENTITY_HEX))
            .documentation("The colour every HUD card's frame is drawn in, as a hex that MULTIPLIES the "
                    + "shipped frame: #ffffff is exactly the shipped look, a darker hex darkens it and a "
                    + "hue tints it. Eight digits carry a transparency in the last two, so #ffffffb8 is "
                    + "the shipped card at about 72 percent and #ffffff80 at half. Six digits is fully "
                    + "opaque. This one value dims every card at once: both bar panels, the quest "
                    + "tracker and any mod's panel on the same base; a bar spot's or a bar panel's own "
                    + "Color, or a mod's own setting, overrides it for that card alone. Left out, or "
                    + "not a #rrggbb or #rrggbbaa hex, the card keeps the shipped look.")
            .add()
            .build();

    public HudCardAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * The authored colour, normalised, or null when the file states none or states something that
     * is not a hex (which warns once, naming this file and the value, and reads as unauthored).
     */
    @Nullable
    public String color() {
        return HudCardLook.authored(color, "Server/" + TYPE_ROOT + "/" + id + ".json (or its owner entry)");
    }
}
