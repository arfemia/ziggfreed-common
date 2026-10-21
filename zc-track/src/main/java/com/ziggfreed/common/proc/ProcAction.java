package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * One entry of a proc's {@code Actions}: one or more of its groups, run in a fixed order: the groups on the moment
 * itself ({@code Soak} on a coming hit, {@code GuardCost} on a block), then those aimed at the holder ({@code OnSelf}
 * true: {@code RemoveEffect}, {@code ApplyEffect}, {@code ChangeStat}, {@code Heal}, {@code Push}), then those aimed at
 * the target ({@code RemoveEffect}, {@code ApplyEffect}, {@code ChangeStat}, {@code Heal}, {@code Push},
 * {@code Taunt}, {@code BreakGuard}), then {@code Counter}, then {@code RunInteraction}; entries run in the order
 * written. A group aims at the moment's target unless it writes {@code OnSelf} true; one aimed at a target the moment
 * has none of does nothing. An entry's {@code Area} aims its target groups at every foe (or every ally) in reach
 * instead, each in turn, and runs its {@code RunInteraction} once for each; its {@code OnSelf} groups run once, and its
 * {@code Counter} once too, except a per-foe ({@code PerTarget}) counter's, which changes the stacks about each one
 * reached.
 *
 * <p><b>Strength.</b> The proc's strength scales what an action gives the world, once: an applied effect's damage
 * multiplier, a stat change and a heal (unless it writes {@code Scaled: false}, as a refund of a cost does). A counter's
 * add is never scaled (a stack counts a moment; a stored share of a hit is scaled where it is spent, by the effect that
 * spends it), nor is a push (a distance), a taunt's time, a soak or a guard's cost (shares of what the moment is).
 *
 * <pre>{@code
 * "Actions": [
 *   { "RemoveEffect": { "Families": [ "Burning", "Poisoned", "Slowed" ], "OnSelf": true } },
 *   { "ApplyEffect": { "Effect": "My_Exposed", "Duration": { "Base": 5 } } },
 *   { "Area": { "Radius": 4, "Around": "Target", "Max": 5 }, "Push": { "Force": { "Base": 8 } } },
 *   { "Counter": { "Of": "My_Flow", "Add": { "Base": 1 } } }
 * ]
 * }</pre>
 */
public final class ProcAction {

    @Nullable protected Area area;
    @Nullable protected ApplyEffect applyEffect;
    @Nullable protected RemoveEffect removeEffect;
    @Nullable protected ChangeStat changeStat;
    @Nullable protected CounterChange counter;
    @Nullable protected Push push;
    @Nullable protected Taunt taunt;
    @Nullable protected Boolean breakGuard;
    @Nullable protected RunInteraction runInteraction;
    @Nullable protected Heal heal;
    @Nullable protected Soak soak;
    @Nullable protected GuardCost guardCost;

    public static final BuilderCodec<ProcAction> CODEC = BuilderCodec.builder(ProcAction.class, ProcAction::new)
            .appendInherited(new KeyedCodec<>("Area", Area.CODEC, false),
                    (o, v) -> o.area = v, o -> o.area, (o, p) -> o.area = p.area)
            .documentation("Aim this entry's target groups at every foe (or, with Allies, every ally) in reach, "
                    + "each in turn, instead of the moment's one target. Its OnSelf groups and its Counter still run "
                    + "once. The moment's target alone when unauthored.").add()
            .appendInherited(new KeyedCodec<>("RemoveEffect", RemoveEffect.CODEC, false),
                    (o, v) -> o.removeEffect = v, o -> o.removeEffect, (o, p) -> o.removeEffect = p.removeEffect)
            .documentation("Take effects off: named ones, or ones of named effect families. Runs first.").add()
            .appendInherited(new KeyedCodec<>("ApplyEffect", ApplyEffect.CODEC, false),
                    (o, v) -> o.applyEffect = v, o -> o.applyEffect, (o, p) -> o.applyEffect = p.applyEffect)
            .documentation("Put a native effect on, owned by the holder, so its damage credits them.").add()
            .appendInherited(new KeyedCodec<>("ChangeStat", ChangeStat.CODEC, false),
                    (o, v) -> o.changeStat = v, o -> o.changeStat, (o, p) -> o.changeStat = p.changeStat)
            .documentation("Add to a stat's current value (stamina, mana, health), held between its minimum and "
                    + "maximum by the engine.").add()
            .appendInherited(new KeyedCodec<>("Counter", CounterChange.CODEC, false),
                    (o, v) -> o.counter = v, o -> o.counter, (o, p) -> o.counter = p.counter)
            .documentation("Add to, take from or clear one of the holder's counters (Server/ZiggfreedCommon/"
                    + "Counters).").add()
            .appendInherited(new KeyedCodec<>("Push", Push.CODEC, false),
                    (o, v) -> o.push = v, o -> o.push, (o, p) -> o.push = p.push)
            .documentation("Move the target away from the holder (a negative Force pulls it in), through the "
                    + "engine's own velocity, which a player's client and a creature's knockback scale both "
                    + "honour.").add()
            .appendInherited(new KeyedCodec<>("Taunt", Taunt.CODEC, false),
                    (o, v) -> o.taunt = v, o -> o.taunt, (o, p) -> o.taunt = p.taunt)
            .documentation("Make the target, a creature, go after the holder for a while.").add()
            .appendInherited(new KeyedCodec<>("BreakGuard", Codec.BOOLEAN, false),
                    (o, v) -> o.breakGuard = v, o -> o.breakGuard, (o, p) -> o.breakGuard = p.breakGuard)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: a guard the target holds up breaks: its block stops at once and its guard chain "
                    + "is cancelled (it may raise its guard again on its next press). False when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("RunInteraction", RunInteraction.CODEC, false),
                    (o, v) -> o.runInteraction = v, o -> o.runInteraction,
                    (o, p) -> o.runInteraction = p.runInteraction)
            .documentation("Run a native RootInteraction chain on the holder or the target. Runs last.").add()
            .appendInherited(new KeyedCodec<>("Heal", Heal.CODEC, false),
                    (o, v) -> o.heal = v, o -> o.heal, (o, p) -> o.heal = p.heal)
            .documentation("Heal the holder or the target through CommonLib's one heal, given by the holder, so "
                    + "their healing power raises it and it counts as a heal they gave (a HealGiven moment, run once "
                    + "this proc is done).").add()
            .appendInherited(new KeyedCodec<>("Soak", Soak.CODEC, false),
                    (o, v) -> o.soak = v, o -> o.soak, (o, p) -> o.soak = p.soak)
            .documentation("For HitIncoming and AllyHitIncoming: lower the coming hit before it lands, or stop it, "
                    + "or leave the one it lands on standing. Runs first.").add()
            .appendInherited(new KeyedCodec<>("GuardCost", GuardCost.CODEC, false),
                    (o, v) -> o.guardCost = v, o -> o.guardCost, (o, p) -> o.guardCost = p.guardCost)
            .documentation("For Blocked: cut the stamina the holder's guard is about to pay for this block, through "
                    + "the engine's own stamina drain, before it is paid. Runs first.").add()
            .build();

    public ProcAction() {
    }

    @Nullable public Area area() { return area; }
    @Nullable public ApplyEffect applyEffect() { return applyEffect; }
    @Nullable public RemoveEffect removeEffect() { return removeEffect; }
    @Nullable public ChangeStat changeStat() { return changeStat; }
    @Nullable public CounterChange counter() { return counter; }
    @Nullable public Push push() { return push; }
    @Nullable public Taunt taunt() { return taunt; }
    public boolean breakGuard() { return Boolean.TRUE.equals(breakGuard); }
    @Nullable public RunInteraction runInteraction() { return runInteraction; }
    @Nullable public Heal heal() { return heal; }
    @Nullable public Soak soak() { return soak; }
    @Nullable public GuardCost guardCost() { return guardCost; }

    /** Does it name no group at all (an Area alone does nothing)? */
    public boolean isEmpty() {
        return applyEffect == null && removeEffect == null && changeStat == null && counter == null
                && push == null && taunt == null && !breakGuard() && runInteraction == null && heal == null
                && soak == null && guardCost == null;
    }

    /** The Heal group: health given through CommonLib's one heal, the holder its giver. */
    public static final class Heal {

        @Nullable protected FactorFormula amount;
        @Nullable protected Boolean byAmount;
        @Nullable protected Boolean onSelf;
        @Nullable protected Boolean scaled;

        public static final BuilderCodec<Heal> CODEC = BuilderCodec.builder(Heal.class, Heal::new)
                .appendInherited(new KeyedCodec<>("Amount", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .documentation("The health given, a formula, times the proc's strength unless Scaled says not, then "
                        + "raised by the holder's healing power as every heal they give is. Nothing when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("ByAmount", Codec.BOOLEAN, false),
                        (o, v) -> o.byAmount = v, o -> o.byAmount, (o, p) -> o.byAmount = p.byAmount)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: Amount is multiplied by the moment's amount (the damage of a hit dealt), so "
                        + "0.25 heals a quarter of it. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: heal the holder. False: heal the moment's target (with an Area of Allies, each "
                        + "ally reached). False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Scaled", Codec.BOOLEAN, false),
                        (o, v) -> o.scaled = v, o -> o.scaled, (o, p) -> o.scaled = p.scaled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("False: the amount is never multiplied by the proc's strength. True when "
                        + "unauthored.").add()
                .build();

        public Heal() {
        }

        @Nullable
        public FactorFormula amount() {
            return amount;
        }

        public boolean byAmount() {
            return Boolean.TRUE.equals(byAmount);
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }

        public boolean scaled() {
            return scaled == null || scaled;
        }
    }

    /**
     * The Soak group: what a coming hit loses before it lands on the one it would strike (the holder on HitIncoming,
     * the ally on AllyHitIncoming). Its {@code Share} comes off first (out of {@code Onto} when that is written), then
     * the hit is lowered so it leaves them at least {@code Leave} health and {@code LeavePercent} of their maximum
     * (a hit that cannot leave them that much is stopped and the rest healed up to it). A hit lowered to nothing is
     * stopped. Never scaled by the proc's strength.
     */
    public static final class Soak {

        @Nullable protected FactorFormula share;
        @Nullable protected String onto;
        @Nullable protected FactorFormula perPoint;
        @Nullable protected FactorFormula leave;
        @Nullable protected FactorFormula leavePercent;

        public static final BuilderCodec<Soak> CODEC = BuilderCodec.builder(Soak.class, Soak::new)
                .appendInherited(new KeyedCodec<>("Share", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.share = v, o -> o.share, (o, p) -> o.share = p.share)
                .documentation("The share of the hit taken off, a formula held to [0, 1] (1: the whole hit, which "
                        + "stops it). Nothing taken off when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Onto", Codec.STRING, false),
                        (o, v) -> o.onto = v, o -> o.onto, (o, p) -> o.onto = p.onto)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("A native stat of the one struck (Mana) that pays for the Share instead of their "
                        + "health, PerPoint of it for each point of damage taken off, and only as much as it holds. "
                        + "The Share simply comes off when unauthored.").add()
                .appendInherited(new KeyedCodec<>("PerPoint", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.perPoint = v, o -> o.perPoint, (o, p) -> o.perPoint = p.perPoint)
                .documentation("How much of the Onto stat one point of damage taken off costs, a formula. 1 when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Leave", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.leave = v, o -> o.leave, (o, p) -> o.leave = p.leave)
                .documentation("The least health the hit leaves the one struck with, a formula (1: they are left "
                        + "standing). Not asked when unauthored.").add()
                .appendInherited(new KeyedCodec<>("LeavePercent", FactorFormula.codec(EditorDataSets.FACTORS),
                                false),
                        (o, v) -> o.leavePercent = v, o -> o.leavePercent, (o, p) -> o.leavePercent = p.leavePercent)
                .documentation("The least share of their maximum health the hit leaves the one struck with, a "
                        + "percent, a formula. Not asked when unauthored.").add()
                .build();

        public Soak() {
        }

        @Nullable
        public FactorFormula share() {
            return share;
        }

        @Nullable
        public String onto() {
            return onto == null || onto.isBlank() ? null : onto.trim();
        }

        @Nullable
        public FactorFormula perPoint() {
            return perPoint;
        }

        @Nullable
        public FactorFormula leave() {
            return leave;
        }

        @Nullable
        public FactorFormula leavePercent() {
            return leavePercent;
        }

        /** Does it ask nothing at all? */
        public boolean isEmpty() {
            return share == null && leave == null && leavePercent == null;
        }
    }

    /** The GuardCost group: the share of a block's stamina cost the holder's guard is spared. */
    public static final class GuardCost {

        @Nullable protected FactorFormula cut;

        public static final BuilderCodec<GuardCost> CODEC = BuilderCodec.builder(GuardCost.class, GuardCost::new)
                .appendInherited(new KeyedCodec<>("Cut", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.cut = v, o -> o.cut, (o, p) -> o.cut = p.cut)
                .documentation("The share of the block's stamina cost taken off, a formula held to [0, 1] (1: the "
                        + "block costs nothing, so it can never break the guard). Never scaled by the proc's "
                        + "strength. 1 when unauthored.").add()
                .build();

        public GuardCost() {
        }

        @Nullable
        public FactorFormula cut() {
            return cut;
        }
    }

    /** The Area group: every foe or ally in reach of the holder or of the moment's target. */
    public static final class Area {

        /** The radius in blocks unless written. */
        public static final double DEFAULT_RADIUS = 4.0;

        /** How many it reaches unless written. */
        public static final int DEFAULT_MAX = 8;

        /** The most it ever reaches, whatever {@code Max} says. */
        public static final int MAX_CAP = 32;

        /** The farthest it ever reaches, in blocks, whatever {@code Radius} says. */
        public static final double RADIUS_CAP = 64.0;

        @Nullable protected Double radius;
        @Nullable protected String around;
        @Nullable protected Integer max;
        @Nullable protected Boolean allies;
        @Nullable protected Boolean skipTarget;
        @Nullable protected Condition[] conditions;

        public static final BuilderCodec<Area> CODEC = BuilderCodec.builder(Area.class, Area::new)
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE, false),
                        (o, v) -> o.radius = v, o -> o.radius, (o, p) -> o.radius = p.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, from the centre, never past 64. 4 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Around", Codec.STRING, false),
                        (o, v) -> o.around = v, o -> o.around, (o, p) -> o.around = p.around)
                .metadata(EditorSchema.oneOfDocumented(
                        "Holder", "Centred on the holder",
                        "Target", "Centred on the moment's target (the foe struck, the one slain); a moment with "
                                + "none reaches no one"))
                .documentation("Where the reach is centred. Holder when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_MAX))
                .documentation("The most it reaches, nearest the centre first, never past 32. 8 when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Allies", Codec.BOOLEAN, false),
                        (o, v) -> o.allies = v, o -> o.allies, (o, p) -> o.allies = p.allies)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the holder's allies instead of their foes: the members of their party, or "
                        + "with no party every other player while the world's PvP is off. The holder is never one "
                        + "(OnSelf reaches them). False when unauthored: every foe hostile to the holder or locked "
                        + "on them.").add()
                .appendInherited(new KeyedCodec<>("SkipTarget", Codec.BOOLEAN, false),
                        (o, v) -> o.skipTarget = v, o -> o.skipTarget, (o, p) -> o.skipTarget = p.skipTarget)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the moment's own target is left out (a splash onto the others). False when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Conditions",
                                new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                        (o, v) -> o.conditions = v, o -> o.conditions, (o, p) -> o.conditions = p.conditions)
                .documentation("Entity conditions each one reached must meet. To keep bosses and elites out, ask "
                        + "ZigFoeFamily of the group Zig_Boss_Or_Elite with Inverse true. None when "
                        + "unauthored.").add()
                .build();

        public Area() {
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

        /** Is the reach centred on the moment's target (else on the holder)? */
        public boolean aroundTarget() {
            return around != null && around.trim().equalsIgnoreCase("Target");
        }

        @Nullable
        public String rawAround() {
            return around;
        }

        /** Is {@code Around} written as neither Holder nor Target? */
        public boolean aroundUnknown() {
            return around != null && !around.isBlank() && !around.trim().equalsIgnoreCase("Target")
                    && !around.trim().equalsIgnoreCase("Holder");
        }

        public int max() {
            int wanted = max == null ? DEFAULT_MAX : max;
            return Math.max(0, Math.min(MAX_CAP, wanted));
        }

        public boolean allies() {
            return Boolean.TRUE.equals(allies);
        }

        public boolean skipTarget() {
            return Boolean.TRUE.equals(skipTarget);
        }

        @Nonnull
        public Condition[] conditions() {
            return conditions == null ? new Condition[0] : conditions;
        }
    }

    /** The Push group. */
    public static final class Push {

        @Nullable protected FactorFormula force;
        @Nullable protected FactorFormula up;
        @Nullable protected Boolean onSelf;
        @Nullable protected Boolean aim;

        public static final BuilderCodec<Push> CODEC = BuilderCodec.builder(Push.class, Push::new)
                .appendInherited(new KeyedCodec<>("Force", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.force = v, o -> o.force, (o, p) -> o.force = p.force)
                .documentation("The speed added along the ground, away from the holder, a formula (8 is a good "
                        + "shove); negative pulls toward them. Not scaled by the proc's strength. None when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Up", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.up = v, o -> o.up, (o, p) -> o.up = p.up)
                .documentation("The speed added upward, a formula. None when unauthored.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the holder moves instead, away from the moment's target (a leap back). False "
                        + "when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Aim", Codec.BOOLEAN, false),
                        (o, v) -> o.aim = v, o -> o.aim, (o, p) -> o.aim = p.aim)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: Force runs along the way the holder looks (level with the ground) instead of "
                        + "away from the other one: with OnSelf the holder is carried forward, else the target is "
                        + "carried the way the holder looks. False when unauthored.").add()
                .build();

        public Push() {
        }

        /** Does Force run along the holder's look instead of away from the other one? */
        public boolean aim() {
            return Boolean.TRUE.equals(aim);
        }

        @Nullable
        public FactorFormula force() {
            return force;
        }

        @Nullable
        public FactorFormula up() {
            return up;
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }
    }

    /** The Taunt group. */
    public static final class Taunt {

        /** The seconds unless written. */
        public static final double DEFAULT_SECONDS = 3.0;

        @Nullable protected FactorFormula seconds;

        public static final BuilderCodec<Taunt> CODEC = BuilderCodec.builder(Taunt.class, Taunt::new)
                .appendInherited(new KeyedCodec<>("Seconds", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.seconds = v, o -> o.seconds, (o, p) -> o.seconds = p.seconds)
                .documentation("How long the creature goes after the holder, a formula; when it ends, it lets go "
                        + "if it is still on the holder. 3 when unauthored.").add()
                .build();

        public Taunt() {
        }

        @Nullable
        public FactorFormula seconds() {
            return seconds;
        }
    }

    /** The ApplyEffect group. */
    public static final class ApplyEffect {

        @Nullable protected String effect;
        @Nullable protected Boolean onSelf;
        @Nullable protected FactorFormula duration;
        @Nullable protected String overlap;
        @Nullable protected FactorFormula damageMultiplier;

        public static final BuilderCodec<ApplyEffect> CODEC = BuilderCodec.builder(ApplyEffect.class, ApplyEffect::new)
                .appendInherited(new KeyedCodec<>("Effect", Codec.STRING, false),
                        (o, v) -> o.effect = v, o -> o.effect, (o, p) -> o.effect = p.effect)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("The native effect to put on, by id.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: on the holder. False: on the moment's target. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Duration", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.duration = v, o -> o.duration, (o, p) -> o.duration = p.duration)
                .documentation("Seconds it lasts, a formula. The effect's own Duration when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Overlap", Codec.STRING, false),
                        (o, v) -> o.overlap = v, o -> o.overlap, (o, p) -> o.overlap = p.overlap)
                .metadata(EditorSchema.oneOfDocumented(
                        "Overwrite", "A second apply restarts its time",
                        "Extend", "A second apply adds its time",
                        "Ignore", "A second apply does nothing while it lasts"))
                .documentation("What a second apply does while the effect is still on. The effect's own "
                        + "OverlapBehavior when unauthored.").add()
                .appendInherited(new KeyedCodec<>("DamageMultiplier", FactorFormula.codec(EditorDataSets.FACTORS),
                                false),
                        (o, v) -> o.damageMultiplier = v, o -> o.damageMultiplier,
                        (o, p) -> o.damageMultiplier = p.damageMultiplier)
                .documentation("What this application's damage is multiplied by (an effect with a "
                        + "DamageCalculator), a formula, times the proc's strength. 1 when unauthored.").add()
                .build();

        public ApplyEffect() {
        }

        @Nullable
        public String effect() {
            return effect == null || effect.isBlank() ? null : effect.trim();
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }

        @Nullable
        public FactorFormula duration() {
            return duration;
        }

        /** The overlap as written, or null when unauthored or unknown. */
        @Nullable
        public OverlapBehavior overlap() {
            return overlapOf(overlap);
        }

        @Nullable
        public String rawOverlap() {
            return overlap;
        }

        @Nullable
        public FactorFormula damageMultiplier() {
            return damageMultiplier;
        }
    }

    /** The RemoveEffect group. */
    public static final class RemoveEffect {

        @Nullable protected String[] effects;
        @Nullable protected String[] families;
        @Nullable protected Boolean onSelf;
        @Nullable protected Integer count;

        public static final BuilderCodec<RemoveEffect> CODEC = BuilderCodec.builder(RemoveEffect.class,
                        RemoveEffect::new)
                .appendInherited(new KeyedCodec<>("Effects", Codec.STRING_ARRAY, false),
                        (o, v) -> o.effects = v, o -> o.effects, (o, p) -> o.effects = p.effects)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .documentation("Native effects to take off, by id.").add()
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("Effect families (Server/ZiggfreedCommon/EffectFamilies: Burning, Poisoned, "
                        + "Slowed) whose effects come off.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: from the holder. False: from the moment's target. False when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many matching effects come off; 0 for every one. 1 when unauthored.").add()
                .build();

        public RemoveEffect() {
        }

        @Nonnull
        public List<String> effects() {
            return trimmed(effects);
        }

        @Nonnull
        public List<String> families() {
            return trimmed(families);
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }

        /** How many come off, 0 for every one. */
        public int count() {
            return count == null ? 1 : Math.max(0, count);
        }
    }

    /** The ChangeStat group. */
    public static final class ChangeStat {

        @Nullable protected String stat;
        @Nullable protected FactorFormula amount;
        @Nullable protected Boolean onSelf;
        @Nullable protected Boolean scaled;
        @Nullable protected Boolean byCost;

        public static final BuilderCodec<ChangeStat> CODEC = BuilderCodec.builder(ChangeStat.class, ChangeStat::new)
                .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                        (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("The native stat whose current value moves, by id (Stamina, Mana).").add()
                .appendInherited(new KeyedCodec<>("Amount", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .documentation("What is added, a formula, times the proc's strength unless Scaled says not; "
                        + "negative takes away.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the holder's stat. False: the target's. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Scaled", Codec.BOOLEAN, false),
                        (o, v) -> o.scaled = v, o -> o.scaled, (o, p) -> o.scaled = p.scaled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("False: the amount is never multiplied by the proc's strength. Write it on a refund "
                        + "that gives back a cost (a dodge's stamina), so gear raising ProcStrength never pays back "
                        + "more than was spent. True when unauthored.").add()
                .appendInherited(new KeyedCodec<>("ByCost", Codec.BOOLEAN, false),
                        (o, v) -> o.byCost = v, o -> o.byCost, (o, p) -> o.byCost = p.byCost)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: Amount is multiplied by what the moment costs the holder (on Blocked, the "
                        + "stamina their guard pays for the block), so 0.5 is half of it. False when "
                        + "unauthored.").add()
                .build();

        public ChangeStat() {
        }

        /** Is the amount multiplied by the moment's cost? */
        public boolean byCost() {
            return Boolean.TRUE.equals(byCost);
        }

        /** Is the amount multiplied by the proc's strength? True unless written false. */
        public boolean scaled() {
            return scaled == null || scaled;
        }

        @Nullable
        public String stat() {
            return stat == null || stat.isBlank() ? null : stat.trim();
        }

        @Nullable
        public FactorFormula amount() {
            return amount;
        }

        public boolean onSelf() {
            return Boolean.TRUE.equals(onSelf);
        }
    }

    /** The Counter group. */
    public static final class CounterChange {

        @Nullable protected String of;
        @Nullable protected FactorFormula add;
        @Nullable protected Boolean byAmount;
        @Nullable protected FactorFormula consume;
        @Nullable protected Boolean clear;

        public static final BuilderCodec<CounterChange> CODEC = BuilderCodec.builder(CounterChange.class,
                        CounterChange::new)
                .appendInherited(new KeyedCodec<>("Of", Codec.STRING, false),
                        (o, v) -> o.of = v, o -> o.of, (o, p) -> o.of = p.of)
                .documentation("The counter, by its file's id (Server/ZiggfreedCommon/Counters).").add()
                .appendInherited(new KeyedCodec<>("Add", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.add = v, o -> o.add, (o, p) -> o.add = p.add)
                .documentation("Stacks added, a formula, up to the counter's Size. Never scaled by the proc's "
                        + "strength: a stored share of a hit takes it where it is spent. Nothing added when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("ByAmount", Codec.BOOLEAN, false),
                        (o, v) -> o.byAmount = v, o -> o.byAmount, (o, p) -> o.byAmount = p.byAmount)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: Add is multiplied by the moment's amount (the damage of a hit dealt or "
                        + "taken), so 0.25 stores a quarter of it. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Consume", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.consume = v, o -> o.consume, (o, p) -> o.consume = p.consume)
                .documentation("Stacks taken away, a formula. Nothing taken when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Clear", Codec.BOOLEAN, false),
                        (o, v) -> o.clear = v, o -> o.clear, (o, p) -> o.clear = p.clear)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: every stack goes, after Add and Consume. False when unauthored.").add()
                .build();

        public CounterChange() {
        }

        @Nullable
        public String of() {
            return of == null || of.isBlank() ? null : of.trim();
        }

        @Nullable
        public FactorFormula add() {
            return add;
        }

        public boolean byAmount() {
            return Boolean.TRUE.equals(byAmount);
        }

        @Nullable
        public FactorFormula consume() {
            return consume;
        }

        public boolean clear() {
            return Boolean.TRUE.equals(clear);
        }
    }

    /** The RunInteraction group. */
    public static final class RunInteraction {

        @Nullable protected String interaction;
        @Nullable protected Boolean onSelf;

        public static final BuilderCodec<RunInteraction> CODEC = BuilderCodec.builder(RunInteraction.class,
                        RunInteraction::new)
                .appendInherited(new KeyedCodec<>("Interaction", Codec.STRING, false),
                        (o, v) -> o.interaction = v, o -> o.interaction, (o, p) -> o.interaction = p.interaction)
                .metadata(EditorSchema.assetRef(RootInteraction.class))
                .documentation("The RootInteraction to run, by id.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("True: it runs on the holder, the moment's target as its target entity. False: it "
                        + "runs on the target, the holder as its target entity. True when unauthored.").add()
                .build();

        public RunInteraction() {
        }

        @Nullable
        public String interaction() {
            return interaction == null || interaction.isBlank() ? null : interaction.trim();
        }

        public boolean onSelf() {
            return onSelf == null || onSelf;
        }
    }

    /** The overlap {@code raw} names (any case), or null when blank or unknown. */
    @Nullable
    static OverlapBehavior overlapOf(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String folded = raw.trim().toUpperCase(Locale.ROOT);
        for (OverlapBehavior behavior : OverlapBehavior.values()) {
            if (behavior.name().equals(folded)) {
                return behavior;
            }
        }
        return null;
    }

    @Nonnull
    static List<String> trimmed(@Nullable String[] values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank() && !out.contains(value.trim())) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }
}
