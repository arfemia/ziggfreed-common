package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.List;

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
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.stats.condition.ConditionalStatAsset;
import com.ziggfreed.common.track.TrackFactors;

/**
 * A proc: when a moment happens to a player who meets its {@code Requires} (a talent through
 * {@code ziggfreedcommon:track_pick}, a held item, anything the factor vocabulary reads), and the moment passes its
 * filters, roll its {@code Chance} and run its {@code Actions} at its {@code Strength}, then wait its
 * {@code Cooldown}. One proc per file at {@code Server/ZiggfreedCommon/Procs/<Id>.json}, the filename its id; a
 * server owner retunes or switches one off by id in {@code mods/ziggfreedcommon/procs.json}.
 *
 * <pre>{@code
 * {
 *   "Trigger": "Dodge",
 *   "Requires": { "Factors": [ { "Factor": "ziggfreedcommon:track_pick", "Param": "Defense/My_Cleanse", "Min": 1 } ] },
 *   "Cooldown": { "Seconds": { "Base": 10 } },
 *   "Actions": [ { "RemoveEffect": { "Families": [ "Burning", "Poisoned" ], "OnSelf": true } } ]
 * }
 * }</pre>
 *
 * <p><b>Filters.</b> A moment that carries a hit (Hit, Crit, SprintHit, HitTaken, Blocked) is read by {@code Hit},
 * the same group a conditional stat reads (zc-entity's {@code HitReads}: first hit, projectile, class, distance, the
 * weapon's head branch), read as the hit's attacker dealt it, and {@code OverTime} is false unless written (a
 * damage-over-time tick never procs by accident); {@code Like} counts it only where a damage line counts.
 * {@code Holder} conditions are asked of the holder, {@code Target} conditions of the moment's target (a foe family,
 * an effect family, a health band, the engine's own {@code Wielding} for a guard that is up); a moment with no target
 * of its own finds one through {@code Seek}. {@code Ability} asks which move a hit or a cast came from, and
 * {@code Effect} names what an effect moment watches. A coming hit (HitIncoming, AllyHitIncoming) is read by
 * {@code LeavesUnder} (how low it would drop the one it lands on), an ally's by {@code AllyRadius}, and a block by
 * {@code WouldBreakGuard}; a coming hit's {@code OverTime} asks nothing unless written.
 *
 * <p><b>Order.</b> The procs on one moment run in the order of their ids, compared lower-cased ({@link ProcConfig}),
 * so a proc that must act before another on the same moment takes an id that sorts first.
 */
public final class ProcAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, ProcAsset>> {

    /** Where the procs live under a pack's {@code Server/} folder. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Procs";

    /** How far an AllyHitIncoming's ally may stand unless written, in blocks. */
    public static final double DEFAULT_ALLY_RADIUS = 8.0;

    /** The farthest an AllyHitIncoming's ally may stand, whatever {@code AllyRadius} says. */
    public static final double ALLY_RADIUS_CAP = 64.0;

    /** The Cooldown group: how long the proc waits after it runs, for the player or for each target. */
    public static final class Cooldown {

        @Nullable protected FactorFormula seconds;
        @Nullable protected Boolean perTarget;

        public static final BuilderCodec<Cooldown> CODEC = BuilderCodec.builder(Cooldown.class, Cooldown::new)
                .appendInherited(new KeyedCodec<>("Seconds", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.seconds = v, o -> o.seconds, (o, p) -> o.seconds = p.seconds)
                .documentation("Seconds before it can run again, a formula (a talent's rank shortens it), cut by "
                        + "the holder's ProcCooldownCut stat held at ProcCooldownCutMax. 0 or less: no wait.").add()
                .appendInherited(new KeyedCodec<>("PerTarget", Codec.BOOLEAN, false),
                        (o, v) -> o.perTarget = v, o -> o.perTarget, (o, p) -> o.perTarget = p.perTarget)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the wait is kept for each target apart (once per foe every few seconds). "
                        + "False: one wait for the player. False when unauthored.").add()
                .build();

        public Cooldown() {
        }

        @Nullable
        public FactorFormula seconds() {
            return seconds;
        }

        public boolean perTarget() {
            return Boolean.TRUE.equals(perTarget);
        }
    }

    /** The Seek group: where a moment with no target of its own finds one. */
    public static final class Seek {

        /** The radius in blocks unless written. */
        public static final double DEFAULT_RADIUS = 3.0;

        @Nullable protected Double radius;

        public static final BuilderCodec<Seek> CODEC = BuilderCodec.builder(Seek.class, Seek::new)
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE, false),
                        (o, v) -> o.radius = v, o -> o.radius, (o, p) -> o.radius = p.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, a foe may stand. The nearest foe meeting the proc's Target "
                        + "conditions and off its wait is the target. 3 when unauthored.").add()
                .build();

        public Seek() {
        }

        public double radius() {
            return radius == null || !Double.isFinite(radius) ? DEFAULT_RADIUS : Math.max(0.0, radius);
        }
    }

    /**
     * The Ability group: which move a hit or a cast must come from, by the RootInteraction its chain started from or
     * by the ability a mod names for its own moves. Either list matching passes; neither written asks nothing.
     */
    public static final class Ability {

        @Nullable protected String[] roots;
        @Nullable protected String[] names;

        public static final BuilderCodec<Ability> CODEC = BuilderCodec.builder(Ability.class, Ability::new)
                .appendInherited(new KeyedCodec<>("Roots", Codec.STRING_ARRAY, false),
                        (o, v) -> o.roots = v, o -> o.roots, (o, p) -> o.roots = p.roots)
                .metadata(EditorSchema.assetRef(RootInteraction.class))
                .documentation("RootInteraction ids: a hit counts when the chain that dealt it started from one of "
                        + "these (a direct hit, while that chain still runs; a projectile's hit lands after it), a "
                        + "cast when its chain did. None when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Names", Codec.STRING_ARRAY, false),
                        (o, v) -> o.names = v, o -> o.names, (o, p) -> o.names = p.names)
                .documentation("Ability ids a mod names for its own moves (MMO Skill Tree's abilities, Fireball "
                        + "or Berserker_Charge), any case: a hit that mod says one of these dealt (a damage-over-time "
                        + "tick of it included), a cast of one of these. None when unauthored.").add()
                .build();

        public Ability() {
        }

        @Nonnull
        public List<String> roots() {
            return ProcAction.trimmed(roots);
        }

        @Nonnull
        public List<String> names() {
            return ProcAction.trimmed(names);
        }

        public boolean isEmpty() {
            return roots().isEmpty() && names().isEmpty();
        }

        /** Does a move from {@code root}, or named {@code ability}, match (any case)? True when nothing is asked. */
        public boolean matches(@Nullable String root, @Nullable String ability) {
            List<String> wantedRoots = roots();
            List<String> wantedNames = names();
            if (wantedRoots.isEmpty() && wantedNames.isEmpty()) {
                return true;
            }
            return containsIgnoreCase(wantedRoots, root) || containsIgnoreCase(wantedNames, ability);
        }
    }

    /** The Effect group: which effects an EffectLanded or EffectEnded proc watches, and on whom. */
    public static final class Effect {

        @Nullable protected String[] effects;
        @Nullable protected String[] families;
        @Nullable protected Boolean onFoe;

        public static final BuilderCodec<Effect> CODEC = BuilderCodec.builder(Effect.class, Effect::new)
                .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                        (o, v) -> o.effects = v, o -> o.effects, (o, p) -> o.effects = p.effects)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("Native effects watched, by id. None when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("Effect families (Server/ZiggfreedCommon/EffectFamilies: Stunned, Frozen) whose "
                        + "effects are watched. None when unauthored.").add()
                .appendInherited(new KeyedCodec<>("OnFoe", Codec.BOOLEAN, false),
                        (o, v) -> o.onFoe = v, o -> o.onFoe, (o, p) -> o.onFoe = p.onFoe)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: watch the effects the holder put on a foe they struck in the last half "
                        + "minute (or that a proc of theirs put on), the foe the moment's target. False: watch the "
                        + "holder's own, whoever put them on (that one the moment's target). False when "
                        + "unauthored.").add()
                .build();

        public Effect() {
        }

        @Nonnull
        public List<String> effects() {
            return ProcAction.trimmed(effects);
        }

        @Nonnull
        public List<String> families() {
            return ProcAction.trimmed(families);
        }

        public boolean onFoe() {
            return Boolean.TRUE.equals(onFoe);
        }

        public boolean isEmpty() {
            return effects().isEmpty() && families().isEmpty();
        }
    }

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean isAbstract;
    @Nullable protected Boolean enabled;
    @Nullable protected String trigger;
    @Nullable protected GateSpec requires;
    @Nullable protected ConditionalStatAsset.Hit hit;
    @Nullable protected String like;
    @Nullable protected Ability ability;
    @Nullable protected Effect effect;
    @Nullable protected Condition[] holder;
    @Nullable protected Condition[] target;
    @Nullable protected Seek seek;
    @Nullable protected Double minAmount;
    @Nullable protected Double leavesUnder;
    @Nullable protected Double allyRadius;
    @Nullable protected Boolean wouldBreakGuard;
    @Nullable protected FactorFormula chance;
    @Nullable protected FactorFormula strength;
    @Nullable protected Cooldown cooldown;
    @Nullable protected ProcAction[] actions;
    @Nullable private volatile GateSpec[] live;
    @Nullable private volatile String[] track;

    public static final AssetBuilderCodec<String, ProcAsset> CODEC = AssetBuilderCodec.builder(
                    ProcAsset.class,
                    ProcAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false), (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("A skeleton other procs take their fields from through Parent: it never runs. Never "
                    + "inherited. False when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to switch the proc off. True when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Trigger", Codec.STRING, false),
                    (a, v) -> a.trigger = v, a -> a.trigger, (a, p) -> a.trigger = p.trigger)
            .metadata(EditorSchema.oneOfDocumented(ProcTrigger.documented()))
            .documentation("The moment the proc answers. A proc naming none, or an unknown one, never runs.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation(StoreGates.DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Hit", ConditionalStatAsset.Hit.CODEC, false),
                    (a, v) -> a.hit = v, a -> a.hit, (a, p) -> a.hit = p.hit)
            .documentation("What the hit must be, on a moment that carries one: the conditional stats' Hit group "
                    + "(FirstHit, Projectile, Classes, distances, Headshot), read as the attacker dealt it. OverTime "
                    + "is false unless written. Nothing else asked when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Like", Codec.STRING, false),
                    (a, v) -> a.like = v, a -> a.like, (a, p) -> a.like = p.like)
            .metadata(EditorSchema.assetRef(EntityStatType.class))
            .documentation("A damage stat: count only a hit on which that stat's own damage line counts (a weapon "
                    + "skill's line, which counts only with its weapon). Every hit when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Ability", Ability.CODEC, false),
                    (a, v) -> a.ability = v, a -> a.ability, (a, p) -> a.ability = p.ability)
            .documentation("For a hit or a cast: which move it must come from, by the RootInteraction its chain "
                    + "started from (Roots) or the ability a mod names for its own moves (Names). Any move when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Effect", Effect.CODEC, false),
                    (a, v) -> a.effect = v, a -> a.effect, (a, p) -> a.effect = p.effect)
            .documentation("For EffectLanded and EffectEnded: the effects (by id or family) watched, on the holder "
                    + "or on the foes they struck. An effect proc naming none never runs.").add()
            .appendInherited(new KeyedCodec<>("Holder", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                    (a, v) -> a.holder = v, a -> a.holder, (a, p) -> a.holder = p.holder)
            .documentation("Entity conditions every one of which must hold on the holder (Sprinting, "
                    + "ZigCrouching, ZigStatPercent). None when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Target", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                    (a, v) -> a.target = v, a -> a.target, (a, p) -> a.target = p.target)
            .documentation("Entity conditions every one of which must hold on the moment's target (ZigFoeFamily, "
                    + "ZigEffectFamily, ZigStatPercent for a health band). Written, a moment with no target never "
                    + "runs the proc. None when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Seek", Seek.CODEC, false),
                    (a, v) -> a.seek = v, a -> a.seek, (a, p) -> a.seek = p.seek)
            .documentation("For a moment with no target of its own (CrouchStill, Dodge, Landing, Cast): the "
                    + "nearest foe within Radius becomes it, or the proc does not run. No target sought when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("MinAmount", Codec.DOUBLE, false),
                    (a, v) -> a.minAmount = v, a -> a.minAmount, (a, p) -> a.minAmount = p.minAmount)
            .documentation("The least the moment's amount may be: the damage of a hit dealt or taken, the blocks "
                    + "fallen on Landing, the seconds held on CrouchStill. No least when unauthored.").add()
            .appendInherited(new KeyedCodec<>("LeavesUnder", Codec.DOUBLE, false),
                    (a, v) -> a.leavesUnder = v, a -> a.leavesUnder, (a, p) -> a.leavesUnder = p.leavesUnder)
            .documentation("For HitIncoming and AllyHitIncoming: only a hit that would drop the one it lands on from "
                    + "above this share of their maximum health to it or under, a percent (25 is a quarter); 0 asks "
                    + "only for a hit that would kill them. Every coming hit when unauthored.").add()
            .appendInherited(new KeyedCodec<>("AllyRadius", Codec.DOUBLE, false),
                    (a, v) -> a.allyRadius = v, a -> a.allyRadius, (a, p) -> a.allyRadius = p.allyRadius)
            .metadata(EditorSchema.defaultValue(DEFAULT_ALLY_RADIUS))
            .documentation("For AllyHitIncoming: how far, in blocks, the ally may stand from the holder, never past "
                    + "64. 8 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("WouldBreakGuard", Codec.BOOLEAN, false),
                    (a, v) -> a.wouldBreakGuard = v, a -> a.wouldBreakGuard,
                    (a, p) -> a.wouldBreakGuard = p.wouldBreakGuard)
            .documentation("For Blocked: true runs only on a block whose stamina cost is more than the holder has "
                    + "left (it would break their guard), false only on one that would not. Either when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Chance", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.chance = v, a -> a.chance, (a, p) -> a.chance = p.chance)
            .documentation("The percent chance it runs when everything else holds, a formula (25 is one time in "
                    + "four), raised by the holder's ProcChance stat. 0 or less never runs. Always when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Strength", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.strength = v, a -> a.strength, (a, p) -> a.strength = p.strength)
            .documentation("What the actions' amounts are multiplied by, a formula (a talent's rank, a stat), "
                    + "raised by the holder's ProcStrength stat. 0 or less: the proc never runs. 1 when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Cooldown", Cooldown.CODEC, false),
                    (a, v) -> a.cooldown = v, a -> a.cooldown, (a, p) -> a.cooldown = p.cooldown)
            .documentation("How long it waits after it runs. No wait when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Actions", new ArrayCodec<>(ProcAction.CODEC, ProcAction[]::new), false),
                    (a, v) -> a.actions = v, a -> a.actions, (a, p) -> a.actions = p.actions)
            .documentation("What it does, in order. A proc with none does nothing.").add()
            .build();

    public ProcAsset() {
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

    /** The trigger named, or null when none or an unknown one was. */
    @Nullable
    public ProcTrigger trigger() {
        return ProcTrigger.parse(trigger);
    }

    @Nullable
    public String rawTrigger() {
        return trigger;
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

    /**
     * The track this proc's talent sits on: the track the first {@code ziggfreedcommon:track_pick} it asks for names
     * ({@code Param} {@code <Track>/<Pick>}, a {@code Min} of 1 or more, at the top of its {@code Requires} or in an
     * {@code AllOf} group), lifted once; null for a proc gated on no pick. What a heal the proc gives names as its
     * source, so that track's healing source can pay it.
     */
    @Nullable
    public String track() {
        String[] held = track;
        if (held == null) {
            held = new String[] {trackOf(requires)};
            track = held;
        }
        return held[0];
    }

    /** The track of the first pick {@code requires} asks for, or null. */
    @Nullable
    static String trackOf(@Nullable GateSpec requires) {
        for (FactorCondition condition : askedPicks(requires)) {
            String param = condition.getParam();
            int slash = param == null ? -1 : param.indexOf('/');
            if (slash > 0) {
                return param.substring(0, slash).trim();
            }
        }
        return null;
    }

    /**
     * Every {@code ziggfreedcommon:track_pick} condition with a {@code Min} of 1 or more at the top of
     * {@code requires} or in one of its {@code AllOf} groups, in order: the picks it asks for (the validator's reach
     * audit and {@link #track} read the same list).
     */
    @Nonnull
    static List<FactorCondition> askedPicks(@Nullable GateSpec requires) {
        if (requires == null) {
            return List.of();
        }
        List<FactorCondition> asked = new ArrayList<>(List.of(requires.factorsOrEmpty()));
        for (GateClause clause : requires.allOfOrEmpty()) {
            if (clause != null) {
                asked.addAll(List.of(clause.factorsOrEmpty()));
            }
        }
        List<FactorCondition> picks = new ArrayList<>(asked.size());
        for (FactorCondition condition : asked) {
            if (condition != null && TrackFactors.PICK.equalsIgnoreCase(condition.getFactor())
                    && condition.getMin() != null && condition.getMin() >= 1.0) {
                picks.add(condition);
            }
        }
        return picks;
    }

    @Nullable
    public ConditionalStatAsset.Hit hit() {
        return hit;
    }

    @Nullable
    public String like() {
        return like == null || like.isBlank() ? null : like.trim();
    }

    @Nullable
    public Ability ability() {
        return ability == null || ability.isEmpty() ? null : ability;
    }

    @Nullable
    public Effect effect() {
        return effect == null || effect.isEmpty() ? null : effect;
    }

    @Nonnull
    public Condition[] holderConditions() {
        return holder == null ? new Condition[0] : holder;
    }

    @Nonnull
    public Condition[] targetConditions() {
        return target == null ? new Condition[0] : target;
    }

    @Nullable
    public Seek seek() {
        return seek;
    }

    @Nullable
    public Double minAmount() {
        return minAmount == null || !Double.isFinite(minAmount) ? null : minAmount;
    }

    /** The health share, a percent held to [0, 100], a coming hit must drop its target to; null asks nothing. */
    @Nullable
    public Double leavesUnder() {
        return leavesUnder == null || !Double.isFinite(leavesUnder) ? null
                : Math.max(0.0, Math.min(100.0, leavesUnder));
    }

    /** How far an AllyHitIncoming's ally may stand, in blocks, held to [0, {@value #ALLY_RADIUS_CAP}]. */
    public double allyRadius() {
        return allyRadius == null || !Double.isFinite(allyRadius) ? DEFAULT_ALLY_RADIUS
                : Math.max(0.0, Math.min(ALLY_RADIUS_CAP, allyRadius));
    }

    /** Is {@code AllyRadius} written? */
    public boolean allyRadiusWritten() {
        return allyRadius != null;
    }

    /** True: only a block that would break the guard; false: only one that would not; null: either. */
    @Nullable
    public Boolean wouldBreakGuard() {
        return wouldBreakGuard;
    }

    @Nullable
    public FactorFormula chance() {
        return chance;
    }

    @Nullable
    public FactorFormula strength() {
        return strength;
    }

    @Nullable
    public Cooldown cooldown() {
        return cooldown;
    }

    @Nonnull
    public List<ProcAction> actions() {
        if (actions == null || actions.length == 0) {
            return List.of();
        }
        List<ProcAction> out = new ArrayList<>(actions.length);
        for (ProcAction action : actions) {
            if (action != null && !action.isEmpty()) {
                out.add(action);
            }
        }
        return List.copyOf(out);
    }

    private static boolean containsIgnoreCase(@Nonnull List<String> values, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String wanted = value.trim();
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }
}
