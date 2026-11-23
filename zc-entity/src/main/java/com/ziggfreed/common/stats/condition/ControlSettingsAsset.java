package com.ziggfreed.common.stats.condition;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The server's one word on crowd control as a whole, beside each kind's own rule ({@link ControlKindAsset}): the
 * Immunity a landing of a kind that ramps adds, and what a fixed control skips. The FILE NAME is the record's id, and
 * the record every landing reads is {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/ControlSettings/Default.json}, which this library ships; the owner
 * layer is {@code mods/ziggfreedcommon/}{@value ControlSettingsConfig#OWNER_FILE} (the Crowd Control screen on
 * {@code /zigadmin}):
 * <pre>{@code
 * { "Default": { "RampAmount": 20, "Fixed": { "SkipsResist": false } } }
 * }</pre>
 *
 * <p><b>A fixed control.</b> An effect tagged {@code "ZigControl": ["Fixed"]} in its own file (R864, R880: a round's
 * stun that must last the round's length on every server) skips what {@code Fixed} says: the foe's immunity (it is
 * never refused by it, never shorter for it and adds none), the carrier's resistance (an effect family's
 * {@code CutBy}, a player's control resistance) and whoever put it on's added seconds (a family's {@code AddedBy}).
 */
public final class ControlSettingsAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, ControlSettingsAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ControlSettings";

    /** The id of the record every landing reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** The Immunity a landing of a kind that ramps adds, unless written: the game's stun bomb's step. */
    public static final double DEFAULT_RAMP_AMOUNT = 25.0;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Double rampAmount;
    @Nullable protected Fixed fixed;

    public static final AssetBuilderCodec<String, ControlSettingsAsset> CODEC = AssetBuilderCodec.builder(
                    ControlSettingsAsset.class,
                    ControlSettingsAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("RampAmount", Codec.DOUBLE, false),
                    (a, v) -> a.rampAmount = v, a -> a.rampAmount, (a, p) -> a.rampAmount = p.rampAmount)
            .metadata(EditorSchema.defaultValue(DEFAULT_RAMP_AMOUNT))
            .documentation("The Immunity one landing of a kind that Ramps adds to the foe, unless the kind writes its "
                    + "own RampAmount. The game's Immunity stat runs to 100, where its Immune effect goes on, and "
                    + "drains on its own. 25 when unauthored: four landings close together, then a while of "
                    + "immunity.").add()
            .appendInherited(new KeyedCodec<>("Fixed", Fixed.CODEC, false),
                    (a, v) -> a.fixed = v, a -> a.fixed, (a, p) -> a.fixed = p.fixed)
            .documentation("What a fixed control (an effect tagged \"ZigControl\": [\"Fixed\"]) skips.").add()
            .build();

    public ControlSettingsAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The Immunity one landing of a ramping kind adds, never below 0. */
    public double rampAmount() {
        return rampAmount == null || !Double.isFinite(rampAmount) ? DEFAULT_RAMP_AMOUNT : Math.max(0.0, rampAmount);
    }

    /** Does a fixed control skip the foe's immunity (no refusal, no shrink, no ramp)? True unless written false. */
    public boolean fixedSkipsImmunity() {
        return fixed == null || fixed.skipsImmunity == null || fixed.skipsImmunity;
    }

    /** Does a fixed control skip the carrier's resistance (a family's {@code CutBy})? True unless written false. */
    public boolean fixedSkipsResist() {
        return fixed == null || fixed.skipsResist == null || fixed.skipsResist;
    }

    /** Does a fixed control skip its owner's added seconds (a family's {@code AddedBy})? True unless written false. */
    public boolean fixedSkipsAddedSeconds() {
        return fixed == null || fixed.skipsAddedSeconds == null || fixed.skipsAddedSeconds;
    }

    /** The Fixed group: what a fixed control skips, each true unless written. */
    public static final class Fixed {

        @Nullable protected Boolean skipsImmunity;
        @Nullable protected Boolean skipsResist;
        @Nullable protected Boolean skipsAddedSeconds;

        public static final BuilderCodec<Fixed> CODEC = BuilderCodec.builder(Fixed.class, Fixed::new)
                .appendInherited(new KeyedCodec<>("SkipsImmunity", Codec.BOOLEAN, false),
                        (o, v) -> o.skipsImmunity = v, o -> o.skipsImmunity, (o, p) -> o.skipsImmunity = p.skipsImmunity)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("True: a fixed control lands on a foe whatever Immunity it carries: never refused by "
                        + "it, never shorter for it, and it adds none. True when unauthored.").add()
                .appendInherited(new KeyedCodec<>("SkipsResist", Codec.BOOLEAN, false),
                        (o, v) -> o.skipsResist = v, o -> o.skipsResist, (o, p) -> o.skipsResist = p.skipsResist)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("True: no stat of whoever carries it shortens it (an effect family's CutBy, such as a "
                        + "player's control resistance). True when unauthored.").add()
                .appendInherited(new KeyedCodec<>("SkipsAddedSeconds", Codec.BOOLEAN, false),
                        (o, v) -> o.skipsAddedSeconds = v, o -> o.skipsAddedSeconds,
                        (o, p) -> o.skipsAddedSeconds = p.skipsAddedSeconds)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("True: no stat of whoever put it on lengthens it (an effect family's AddedBy). True "
                        + "when unauthored.").add()
                .build();

        public Fixed() {
        }
    }
}
