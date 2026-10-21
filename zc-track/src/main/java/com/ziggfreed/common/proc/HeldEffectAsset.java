package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * A held effect: a native effect a player wears, with no end, while a pick or a stat says so (an aura, a stance, a
 * guard rule, a talent's cue), and loses cleanly the moment it no longer does. One per file at
 * {@code Server/ZiggfreedCommon/HeldEffects/<Id>.json}; a server owner retunes or switches one off by id in
 * {@code mods/ziggfreedcommon/held-effects.json}.
 *
 * <pre>{@code
 * {
 *   "Effect": "My_Braced",
 *   "Requires": { "Factors": [ { "Factor": "ziggfreedcommon:track_pick", "Param": "Polearms/My_Brace", "Min": 1 } ] },
 *   "Holder": [ { "Id": "ZigCrouching" } ]
 * }
 * }</pre>
 *
 * <p>It is held while its {@code Requires} passes, its {@code Stat} (when written) reads above 0 and every
 * {@code Holder} condition holds, read a few times a second; a condition the engine can say on its own belongs in
 * the effect's own {@code ApplyConditions}, which the engine checks every tick. {@code Hold} makes the hold stand a
 * while before the effect goes on (a stance planted), {@code Linger} keeps it on a while after (a cover that fades).
 * Only an effect worn with no end is taken off, so a timed apply of the same effect by something else is left alone.
 */
public final class HeldEffectAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, HeldEffectAsset>> {

    /** Where the held effects live under a pack's {@code Server/} folder. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HeldEffects";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean isAbstract;
    @Nullable protected Boolean enabled;
    @Nullable protected String effect;
    @Nullable protected GateSpec requires;
    @Nullable protected String stat;
    @Nullable protected Condition[] holder;
    @Nullable protected FactorFormula hold;
    @Nullable protected FactorFormula linger;
    @Nullable private volatile GateSpec[] live;

    public static final AssetBuilderCodec<String, HeldEffectAsset> CODEC = AssetBuilderCodec.builder(
                    HeldEffectAsset.class,
                    HeldEffectAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false), (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("A skeleton other files take their fields from through Parent. Never inherited. False "
                    + "when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to switch it off: the effect comes off. True when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Effect", Codec.STRING, false),
                    (a, v) -> a.effect = v, a -> a.effect, (a, p) -> a.effect = p.effect)
            .metadata(EditorSchema.assetRef(EntityEffect.class))
            .documentation("The native effect held, by id, worn with no end.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation(StoreGates.DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                    (a, v) -> a.stat = v, a -> a.stat, (a, p) -> a.stat = p.stat)
            .metadata(EditorSchema.assetRef(EntityStatType.class))
            .documentation("A native stat that must read above 0 (its maximum, where gear, effects and picks "
                    + "raise it). Not asked when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Holder", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                    (a, v) -> a.holder = v, a -> a.holder, (a, p) -> a.holder = p.holder)
            .documentation("Entity conditions every one of which must hold on the player (ZigCrouching, "
                    + "ZigMoving). None when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Hold", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.hold = v, a -> a.hold, (a, p) -> a.hold = p.hold)
            .documentation("Seconds the hold must stand, unbroken, before the effect goes on, a formula (a guard "
                    + "held two seconds; a talent's rank shortens it). On at once when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Linger", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.linger = v, a -> a.linger, (a, p) -> a.linger = p.linger)
            .documentation("Seconds the effect stays on after the hold stops standing, a formula; a hold that "
                    + "stands again inside them keeps it on. Off at once when unauthored. A file switched off, or "
                    + "whose Requires fails, takes it off at once.").add()
            .build();

    public HeldEffectAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    public boolean isAbstract() {
        return Boolean.TRUE.equals(isAbstract);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public String effect() {
        return effect == null || effect.isBlank() ? null : effect.trim();
    }

    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** The part of {@link #requires()} asked of the player, lifted once; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(requires)};
            live = held;
        }
        return held[0];
    }

    @Nullable
    public String stat() {
        return stat == null || stat.isBlank() ? null : stat.trim();
    }

    @Nonnull
    public Condition[] holderConditions() {
        return holder == null ? new Condition[0] : holder;
    }

    @Nullable
    public FactorFormula hold() {
        return hold;
    }

    @Nullable
    public FactorFormula linger() {
        return linger;
    }
}
