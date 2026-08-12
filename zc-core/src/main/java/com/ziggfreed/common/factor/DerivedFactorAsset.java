package com.ziggfreed.common.factor;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorDataSets;

/**
 * Defines a NEW factor id out of the ones that already exist, with no Java: the file's name is the
 * factor id, and its {@code Formula} is what that id resolves to.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/Factors/<id>.json} (Pattern A - this codec IS the
 * schema):
 * <pre>{@code
 * // Server/ZiggfreedCommon/Factors/yourmod_gear_score.json
 * { "Formula": {
 *     "Base": 1.0,
 *     "Factors": [ {"Factor": "hytale:tool_quality",    "Weight": 0.5},
 *                  {"Factor": "hytale:tool_item_level", "Weight": 0.1} ],
 *     "Clamp": {"Min": 1.0, "Max": 5.0} } }
 * }</pre>
 *
 * <p>Anything that reads the vocabulary can then address {@code yourmod_gear_score} exactly like a
 * mod-registered id - an NPC placement's {@code Requires}, a dialogue {@code Factor} condition,
 * another formula's term. Nothing tells them apart, which is the whole point: a server owner
 * retunes a number by editing one small file instead of asking a mod author for a new reading.
 *
 * <p><b>The file NAME is the factor id</b>, so name it the way you would name a registered factor
 * and prefix it with your mod. Override a definition by dropping a same-named file in a later pack
 * or in the owner layer; reuse one with a top-level {@code "Parent": "<id>"}, which inherits leaf by
 * leaf (override just the {@code Clamp} and {@code Base} + {@code Factors} carry over).
 *
 * <p><b>A derived factor answers whenever its file exists.</b> An input nobody can read contributes
 * 0 rather than voiding the result ({@link FactorFormula} explains why the value side degrades where
 * a gate fails closed), so a bounds-less condition on a derived id passes as soon as the definition
 * is installed. Author {@code Base} for the value it must have when everything optional is missing,
 * and gate on a {@code Min} when an input is what really matters.
 *
 * <p>A definition that reaches itself, directly or through another one, cannot resolve and fails
 * closed; {@link DerivedFactorValidator} reports the cycle at load so it is visible before anyone
 * hunts a missing NPC.
 */
public final class DerivedFactorAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, DerivedFactorAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private FactorFormula formula;

    public static final AssetBuilderCodec<String, DerivedFactorAsset> CODEC = AssetBuilderCodec.builder(
                    DerivedFactorAsset.class,
                    DerivedFactorAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Formula", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.formula = v, a -> a.formula, (a, p) -> a.formula = p.formula)
            .documentation("What this factor id resolves to: a Base plus weighted readings of other factors, "
                    + "optionally clamped. Without it the id defines nothing and everything gating on it "
                    + "stays shut.")
            .add()
            .build();

    public DerivedFactorAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The authored formula, or null when the file defines none (a validator error). */
    @Nullable
    public FactorFormula getFormula() {
        return formula;
    }
}
