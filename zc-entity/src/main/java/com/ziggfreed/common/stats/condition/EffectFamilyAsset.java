package com.ziggfreed.common.stats.condition;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * A family of effects a condition or an effect stat names as one (Burning, Poisoned, Slowed, Controlled): the FILE
 * NAME is the family's id. An effect joins a family through its own native {@code Tags} ({@value #TAG}, R751), so a
 * mod's own effect opts in from its own file; {@code Effects} lists the game's own, which no other file can tag, and
 * {@code Includes} folds whole families in (Controlled is Slowed, Rooted, Frozen and Withered).
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/EffectFamilies/Burning.json
 * { "Effects": [ "Burn", "Lava_Burn" ] }
 * // a mod's own effect, in its own file
 * "Tags": { "ZigEffectFamily": [ "Burning" ] }
 * }</pre>
 *
 * <p>The library ships the families its own stats read; the owner layer is
 * {@code mods/ziggfreedcommon/}{@value EffectFamilyConfig#OWNER_FILE}.
 */
public final class EffectFamilyAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, EffectFamilyAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/EffectFamilies";

    /** The native {@code Tags} key an effect carries to join a family: {@code "ZigEffectFamily": ["Burning"]}. */
    public static final String TAG = "ZigEffectFamily";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected String[] effects;
    @Nullable protected String[] includes;
    @Nullable protected Duration duration;

    public static final AssetBuilderCodec<String, EffectFamilyAsset> CODEC = AssetBuilderCodec.builder(
                    EffectFamilyAsset.class,
                    EffectFamilyAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                    (a, v) -> a.effects = v, a -> a.effects, (a, p) -> a.effects = p.effects)
            .metadata(EditorSchema.assetRef(EntityEffect.class))
            .documentation("Effect ids in this family: the game's own, which no other file can tag. An effect of a "
                    + "mod's own joins through its own Tags (\"ZigEffectFamily\": [\"<Family>\"]) instead. An id this "
                    + "server does not have counts nothing. Under Parent this list replaces the parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Includes", Codec.STRING_ARRAY, false),
                    (a, v) -> a.includes = v, a -> a.includes, (a, p) -> a.includes = p.includes)
            .documentation("Other families whose every effect is in this one too (Controlled includes Slowed and "
                    + "Rooted). A family naming itself, or a loop, counts each member once.").add()
            .appendInherited(new KeyedCodec<>("Duration", Duration.CODEC, false),
                    (a, v) -> a.duration = v, a -> a.duration, (a, p) -> a.duration = p.duration)
            .documentation("Stats that change how long an effect of this family lasts as it lands: one of whoever "
                    + "put it on, one of whoever carries it. Neither when unauthored.").add()
            .build();

    public EffectFamilyAsset() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static EffectFamilyAsset of(@Nonnull String id, @Nullable String[] effects, @Nullable String[] includes) {
        EffectFamilyAsset asset = new EffectFamilyAsset();
        asset.id = id;
        asset.effects = effects;
        asset.includes = includes;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The listed effect ids, lower-cased, blanks dropped, in the order written. */
    @Nonnull
    public Set<String> effectIds() {
        return lowered(effects);
    }

    /** The included family ids, lower-cased, blanks dropped, in the order written. */
    @Nonnull
    public Set<String> includedFamilies() {
        return lowered(includes);
    }

    /** The duration rule, or null when unauthored. */
    @Nullable
    public Duration duration() {
        return duration;
    }

    /**
     * The Duration group: how long an effect of the family lasts as it lands. {@code AddedBy} is a stat of whoever put
     * the effect on (its owner), in seconds added; {@code CutBy} a stat of whoever carries it, in percent taken off,
     * held to {@code CutCap} so no stat makes anyone immune.
     */
    public static final class Duration {

        /** The most a carrier's stat takes off, in percent, unless written. */
        public static final double DEFAULT_CUT_CAP = 80.0;

        @Nullable protected String addedBy;
        @Nullable protected String cutBy;
        @Nullable protected Double cutCap;

        public static final BuilderCodec<Duration> CODEC = BuilderCodec.builder(Duration.class, Duration::new)
                .appendInherited(new KeyedCodec<>("AddedBy", Codec.STRING, false),
                        (o, v) -> o.addedBy = v, o -> o.addedBy, (o, p) -> o.addedBy = p.addedBy)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("A stat of whoever put the effect on: each point adds a second to the effect as it "
                        + "lands (EffectDuration_Dot). None when unauthored.").add()
                .appendInherited(new KeyedCodec<>("CutBy", Codec.STRING, false),
                        (o, v) -> o.cutBy = v, o -> o.cutBy, (o, p) -> o.cutBy = p.cutBy)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("A stat of whoever carries the effect: each point takes a percent off it as it lands "
                        + "(EffectTaken_Control). None when unauthored.").add()
                .appendInherited(new KeyedCodec<>("CutCap", Codec.DOUBLE, false),
                        (o, v) -> o.cutCap = v, o -> o.cutCap, (o, p) -> o.cutCap = p.cutCap)
                .metadata(EditorSchema.defaultValue(DEFAULT_CUT_CAP))
                .documentation("The most CutBy takes off, in percent, so no stat makes anyone immune. 80 when "
                        + "unauthored.").add()
                .build();

        public Duration() {
        }

        /** The owner's stat, or null. */
        @Nullable
        public String addedBy() {
            return addedBy == null || addedBy.isBlank() ? null : addedBy.trim();
        }

        /** The carrier's stat, or null. */
        @Nullable
        public String cutBy() {
            return cutBy == null || cutBy.isBlank() ? null : cutBy.trim();
        }

        /** The cap in percent, held to [0, 99]. */
        public double cutCap() {
            return cutCap == null || !Double.isFinite(cutCap) ? DEFAULT_CUT_CAP : Math.max(0.0, Math.min(99.0, cutCap));
        }
    }

    @Nonnull
    private static Set<String> lowered(@Nullable String[] values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
