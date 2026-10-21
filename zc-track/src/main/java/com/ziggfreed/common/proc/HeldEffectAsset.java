package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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
 * {@code Reach} makes it an aura: while it is on, the holder's allies (or the creatures their taunt holds) in reach
 * wear it too, applied a moment at a time with the holder as its owner and kept up while they stay, so nothing is
 * left on anyone once they step away, the hold ends or the holder leaves.
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
    @Nullable protected Reach reach;
    @Nullable private volatile GateSpec[] live;

    /**
     * The Reach group: the others the effect reaches while it is on, an aura: the holder's allies, or the creatures
     * their taunt holds, in reach and meeting the group's conditions, each wearing the effect for a moment at a time,
     * kept up while they stay in reach and it stays on, so it comes off on its own soon after either ends (or the
     * holder goes).
     */
    public static final class Reach {

        /** The radius in blocks unless written. */
        public static final double DEFAULT_RADIUS = 8.0;

        /** The farthest it reaches, whatever {@code Radius} says. */
        public static final double RADIUS_CAP = 64.0;

        /** How many it reaches unless written. */
        public static final int DEFAULT_MAX = 8;

        /** The most it ever reaches, whatever {@code Max} says. */
        public static final int MAX_CAP = 32;

        @Nullable protected Boolean allies;
        @Nullable protected Boolean taunted;
        @Nullable protected Double radius;
        @Nullable protected Integer max;
        @Nullable protected Integer min;
        @Nullable protected Boolean behind;
        @Nullable protected Boolean self;
        @Nullable protected Condition[] conditions;

        public static final BuilderCodec<Reach> CODEC = BuilderCodec.builder(Reach.class, Reach::new)
                .appendInherited(new KeyedCodec<>("Allies", Codec.BOOLEAN, false),
                        (o, v) -> o.allies = v, o -> o.allies, (o, p) -> o.allies = p.allies)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the holder's allies in reach (the members of their party, or with no party "
                        + "every other player while the world's PvP is off). False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Taunted", Codec.BOOLEAN, false),
                        (o, v) -> o.taunted = v, o -> o.taunted, (o, p) -> o.taunted = p.taunted)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the creatures in reach the holder's taunt holds right now (pinned onto them, "
                        + "or locked on them while their area taunt is open). False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE, false),
                        (o, v) -> o.radius = v, o -> o.radius, (o, p) -> o.radius = p.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, from the holder, never past 64. 8 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_MAX))
                .documentation("The most it reaches, nearest first, never past 32. 8 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Min", Codec.INTEGER, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many others must be in reach (meeting the conditions) for the hold to stand at "
                        + "all (2: with two or more allies near), the holder not counted. 0 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Behind", Codec.BOOLEAN, false),
                        (o, v) -> o.behind = v, o -> o.behind, (o, p) -> o.behind = p.behind)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: only those behind the holder (on the far side of the way they look). False "
                        + "when unauthored: all around.").add()
                .appendInherited(new KeyedCodec<>("Self", Codec.BOOLEAN, false),
                        (o, v) -> o.self = v, o -> o.self, (o, p) -> o.self = p.self)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("False: only the others wear it, never the holder. True when unauthored: the holder "
                        + "wears it too, as every held effect is worn.").add()
                .appendInherited(new KeyedCodec<>("Conditions",
                                new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                        (o, v) -> o.conditions = v, o -> o.conditions, (o, p) -> o.conditions = p.conditions)
                .documentation("Entity conditions each one reached must meet (Sprinting: only allies who sprint). "
                        + "None when unauthored.").add()
                .build();

        public Reach() {
        }

        public boolean allies() {
            return Boolean.TRUE.equals(allies);
        }

        public boolean taunted() {
            return Boolean.TRUE.equals(taunted);
        }

        /** The reach in blocks, held to [0, {@value #RADIUS_CAP}]. */
        public double radius() {
            return radius == null || !Double.isFinite(radius) ? DEFAULT_RADIUS
                    : Math.max(0.0, Math.min(RADIUS_CAP, radius));
        }

        /** Is {@code Radius} written past {@value #RADIUS_CAP}, so it is held there? */
        public boolean radiusPastCap() {
            return radius != null && Double.isFinite(radius) && radius > RADIUS_CAP;
        }

        public int max() {
            return Math.max(0, Math.min(MAX_CAP, max == null ? DEFAULT_MAX : max));
        }

        public int min() {
            return Math.max(0, min == null ? 0 : min);
        }

        public boolean behind() {
            return Boolean.TRUE.equals(behind);
        }

        public boolean self() {
            return self == null || self;
        }

        @Nonnull
        public Condition[] conditions() {
            return conditions == null ? new Condition[0] : conditions;
        }

        /** Does it name no one to reach (neither allies nor the taunted)? */
        public boolean reachesNoKind() {
            return !allies() && !taunted();
        }
    }

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
            .appendInherited(new KeyedCodec<>("Reach", Reach.CODEC, false),
                    (a, v) -> a.reach = v, a -> a.reach, (a, p) -> a.reach = p.reach)
            .documentation("Make it an aura: while it is on, the holder's allies (or the creatures their taunt "
                    + "holds) in reach wear it too, a moment at a time, so it leaves them soon after they step out of "
                    + "reach or it goes off. Worn by the holder alone when unauthored.").add()
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

    /** The others it reaches as an aura, or null when only the holder wears it. */
    @Nullable
    public Reach reach() {
        return reach;
    }
}
