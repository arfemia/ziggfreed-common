package com.ziggfreed.common.effect.control;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.duration.EffectDurations;
import com.ziggfreed.common.effect.landing.EffectLandings;
import com.ziggfreed.common.stats.condition.BossRules;
import com.ziggfreed.common.stats.condition.ControlKind;
import com.ziggfreed.common.stats.condition.ControlKinds;
import com.ziggfreed.common.stats.condition.EffectFamilies;

/**
 * The one way any control lands on a foe: a stun, a stagger, a root, a freeze, a slow, a wither, a weakness, a pack's
 * own kind, whoever deals it (a proc, the {@code ZigStun} Type, a consumer's ability or minigame), so a foe held over
 * and over is held the same way whoever holds it (R828, R960). Every rule it follows is data: the foe's rules
 * (zc-entity's {@code BossRules}), each kind of control's own row ({@code ControlKinds}, the Control Kinds screen) and
 * the Crowd Control settings; nothing here is per kind.
 *
 * <p><b>One landing, in order.</b>
 * <ol>
 *   <li>The foe's rules, for the kinds the control is (a step's own kind, else its effect's families'), asked as a
 *       talent's when it is one ({@link Landing#talent}, R1149: a proc's step with no setting of its own is): a
 *       refusal is told to the caster as the shrug word.</li>
 *   <li>The foe's immunity, when one of its kinds is {@code RefusedByImmune}: the game's {@code Immune}, or a stun
 *       immunity of its own no window it wears opens for those kinds ({@link StunControl#isImmune}).</li>
 *   <li>Who it is: a boss or an elite ({@code BossRules.isBossOrElite}) takes it with no owner, so nothing of the
 *       caster's lengthens it on one (R672), and its length is read at that rank ({@link Amount#at}).</li>
 *   <li>The length: the effect families' Duration rules as it lands (zc-effects' {@code EffectDurations}: the owner's
 *       added seconds, the carrier's resistance, each kind's longest hold), then, when one of its kinds
 *       {@code Shrinks}, times 1 less the share of Immunity the foe already carries ({@link #kept}).</li>
 *   <li>The ramp: once it lands, when one of its kinds {@code Ramps}, the foe's Immunity rises by the kind's amount
 *       (the Crowd Control settings' unless the kind writes its own), so it turns {@code Immune} for a while.</li>
 * </ol>
 * A fixed control (its effect tagged {@code "ZigControl": ["Fixed"]}, R864, R880) skips what the settings' {@code Fixed}
 * says: the immunity (no refusal, no shrink, no ramp), and in the Duration rules the carrier's resistance and the
 * owner's added seconds. A step may override its kinds' shrink and ramp ({@link Landing#shrinks}, {@link
 * Landing#ramp}, a proc's {@code Control}, {@code ZigStun}'s {@code Diminish} and {@code ImmunityRamp}), and a fight's
 * own stun writes past the immunity ({@link Landing#ignoringImmunity}: no refusal and no shrink).
 *
 * <p><b>One cast, one share and one step.</b> A consumer whose one cast lands several controls on one foe (a storm's
 * slows then its freeze) keeps one {@link Tally#perCast()} on the cast and hands it to every landing, so the share is
 * read once per foe and one landing ramps; anything else lands with {@link Tally#EACH}.
 *
 * <p>World thread. Every call answers, never throws: a landing that cannot tell lands nothing.
 */
public final class ControlLanding {

    private ControlLanding() {
    }

    /** A number a step reads at the foe's rank: a boss or an elite reads it at the cast's own strength (R1091). */
    @FunctionalInterface
    public interface Amount {

        /** The number, for a foe that is ({@code true}) or is not a boss or an elite. */
        double at(boolean bossOrElite);

        /** The same number at every rank. */
        @Nonnull
        static Amount of(double value) {
            return bossOrElite -> value;
        }
    }

    /**
     * One cast's record of each foe it holds: the Immunity share its landings shrink by, read at its first landing on
     * that foe, and the one landing on that foe that may ramp, so a cast never shrinks its own controls.
     */
    public interface Tally {

        /** Each landing reads its own share and ramps: anything that is not one cast of many controls. */
        Tally EACH = new Tally() {
            @Override
            public float share(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
                    @Nonnull Supplier<Float> read) {
                return read.get();
            }

            @Override
            public boolean step(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe) {
                return true;
            }
        };

        /** The share this landing shrinks by: {@code read} the first time for {@code foe}, the same after. */
        float share(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
                @Nonnull Supplier<Float> read);

        /** May this landing on {@code foe} ramp its Immunity (the cast's first that does)? */
        boolean step(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe);

        /**
         * A fresh record for one cast: the first landing on a foe reads its share and every later one on that foe
         * reuses it, and only the first landing on it that ramps takes the step, so a cast never shrinks its own
         * controls (a storm's slows, then its freeze; a carry's full-length stun, then the throw's). A foe is known by
         * its UUID, so a new creature in a dead one's entity slot is a new foe. Keep it on the cast, one per cast;
         * one world thread.
         */
        @Nonnull
        static Tally perCast() {
            CastRecord record = new CastRecord();
            return new Tally() {
                @Override
                public float share(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
                        @Nonnull Supplier<Float> read) {
                    return record.share(keyOf(accessor, foe), read);
                }

                @Override
                public boolean step(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe) {
                    return record.step(keyOf(accessor, foe));
                }
            };
        }
    }

    /**
     * One cast's record of each foe it held, by any key that names a foe: the share its first landing read, and
     * whether a landing on it has ramped. The pure half of {@link Tally#perCast}. One world thread.
     */
    public static final class CastRecord {

        private final Map<Object, Float> shares = new HashMap<>();
        private final Set<Object> stepped = new HashSet<>();

        /** The share for {@code foe}: {@code read} the first time, the same after. */
        public float share(@Nonnull Object foe, @Nonnull Supplier<Float> read) {
            Float held = shares.get(foe);
            if (held == null) {
                Float first = read.get();
                held = first == null ? 0f : first;
                shares.put(foe, held);
            }
            return held;
        }

        /** True the first time a landing on {@code foe} ramps, false after. */
        public boolean step(@Nonnull Object foe) {
            return stepped.add(foe);
        }
    }

    /** How a cast's record names {@code foe}: its UUID, else the reference itself. Never throws. */
    @Nonnull
    private static Object keyOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe) {
        try {
            UUIDComponent id = accessor.getComponent(foe, UUIDComponent.getComponentType());
            return id != null && id.getUuid() != null ? id.getUuid() : foe;
        } catch (Throwable t) {
            return foe;
        }
    }

    /**
     * What one landing asks: an effect put on ({@link #effect}), or a stun or a stagger ({@link #stun},
     * {@link #stagger}, under the library's {@code Zig_Stunned}, or a caller's own stun effect through
     * {@link #under}), and the knobs a step may set. Immutable; each wither answers a copy.
     */
    public static final class Landing {

        @Nullable private final String effectId;
        private final boolean stun;
        private final boolean stagger;
        @Nonnull private final Amount seconds;
        @Nullable private final ControlKind kind;
        @Nullable private final Ref<EntityStore> source;
        private final boolean talent;
        @Nullable private final OverlapBehavior overlap;
        private final boolean ignoringImmunity;
        @Nullable private final Boolean shrinks;
        @Nullable private final Amount ramp;
        @Nonnull private final Tally tally;

        private Landing(@Nullable String effectId, boolean stun, boolean stagger, @Nonnull Amount seconds,
                @Nullable ControlKind kind, @Nullable Ref<EntityStore> source, boolean talent,
                @Nullable OverlapBehavior overlap, boolean ignoringImmunity, @Nullable Boolean shrinks,
                @Nullable Amount ramp, @Nonnull Tally tally) {
            this.effectId = effectId;
            this.stun = stun;
            this.stagger = stagger;
            this.seconds = seconds;
            this.kind = kind;
            this.source = source;
            this.talent = talent;
            this.overlap = overlap;
            this.ignoringImmunity = ignoringImmunity;
            this.shrinks = shrinks;
            this.ramp = ramp;
            this.tally = tally;
        }

        /** Put the timed effect {@code effectId} on for {@code seconds}; its kinds are its effect families'. */
        @Nonnull
        public static Landing effect(@Nonnull String effectId, @Nonnull Amount seconds) {
            return new Landing(effectId.trim(), false, false, seconds, null, null, false, null, false, null, null,
                    Tally.EACH);
        }

        /** Stun for {@code seconds} ({@link StunControl#stun}): a Stun. */
        @Nonnull
        public static Landing stun(@Nonnull Amount seconds) {
            return new Landing(null, true, false, seconds, ControlKind.STUN, null, false, null, false, null, null,
                    Tally.EACH);
        }

        /** Stagger for {@code seconds} ({@link StunControl#stagger}): a Stun to the foe's rules. */
        @Nonnull
        public static Landing stagger(@Nonnull Amount seconds) {
            return new Landing(null, true, true, seconds, ControlKind.STUN, null, false, null, false, null, null,
                    Tally.EACH);
        }

        /**
         * A stun under the caller's own stun effect in place of {@code Zig_Stunned} (built on the game's {@code Stun}
         * and in the Stunned family, so every reader of a stun sees it; a fixed one tagged
         * {@code "ZigControl": ["Fixed"]} lasts exactly its length). A no-op on an effect landing or a stagger.
         */
        @Nonnull
        public Landing under(@Nonnull String stunEffectId) {
            return !stun || stagger ? this : new Landing(stunEffectId.trim(), true, false, seconds, kind, source,
                    talent, overlap, ignoringImmunity, shrinks, ramp, tally);
        }

        /** Who cast it: its owner (unless the foe is a boss or an elite) and who hears a refusal. */
        @Nonnull
        public Landing by(@Nullable Ref<EntityStore> caster) {
            return new Landing(effectId, stun, stagger, seconds, kind, caster, talent, overlap, ignoringImmunity,
                    shrinks, ramp, tally);
        }

        /**
         * Is it a talent's control (a talent or a proc added it to the move), which a boss or an elite takes only
         * where its rules let every one of its kinds land? False: an ability's own.
         */
        @Nonnull
        public Landing talent(boolean byTalent) {
            return new Landing(effectId, stun, stagger, seconds, kind, source, byTalent, overlap, ignoringImmunity,
                    shrinks, ramp, tally);
        }

        /** The kind the step names, in place of its effect's families' (null for theirs). */
        @Nonnull
        public Landing kind(@Nullable ControlKind named) {
            return new Landing(effectId, stun, stagger, seconds, named != null ? named : stun ? ControlKind.STUN
                    : null, source, talent, overlap, ignoringImmunity, shrinks, ramp, tally);
        }

        /** What a second landing of the effect does while it is on; the effect's own when null. */
        @Nonnull
        public Landing overlap(@Nullable OverlapBehavior behavior) {
            return new Landing(effectId, stun, stagger, seconds, kind, source, talent, behavior, ignoringImmunity,
                    shrinks, ramp, tally);
        }

        /** A fight's own control on its boss: no immunity refuses it and none shortens it. */
        @Nonnull
        public Landing ignoringImmunity() {
            return new Landing(effectId, stun, stagger, seconds, kind, source, talent, overlap, true, shrinks, ramp,
                    tally);
        }

        /** Whether it is shorter for the foe's Immunity, over its kinds' {@code Shrinks} (null: theirs). */
        @Nonnull
        public Landing shrinks(@Nullable Boolean shrinksOnRepeat) {
            return new Landing(effectId, stun, stagger, seconds, kind, source, talent, overlap, ignoringImmunity,
                    shrinksOnRepeat, ramp, tally);
        }

        /** The Immunity a landing adds, over its kinds' ramp (null: theirs; 0 adds none). */
        @Nonnull
        public Landing ramp(@Nullable Amount amount) {
            return new Landing(effectId, stun, stagger, seconds, kind, source, talent, overlap, ignoringImmunity,
                    shrinks, amount, tally);
        }

        /** One cast's record of each foe it holds ({@link Tally}). */
        @Nonnull
        public Landing tally(@Nonnull Tally record) {
            return new Landing(effectId, stun, stagger, seconds, kind, source, talent, overlap, ignoringImmunity,
                    shrinks, ramp, record);
        }
    }

    /**
     * Land {@code landing} on {@code target}, by the one rule above.
     *
     * @return true when the control went on
     */
    public static boolean land(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target,
            @Nonnull Landing landing) {
        try {
            if (target == null || !target.isValid()) {
                return false;
            }
            String effectId = landing.effectId != null ? landing.effectId
                    : landing.stun ? (landing.stagger ? StunControl.STAGGERED_EFFECT : StunControl.STUNNED_EFFECT)
                    : null;
            int index = effectId == null ? AssetMapWithIndexes.NOT_FOUND : EntityEffect.getAssetMap().getIndex(effectId);
            if (index == AssetMapWithIndexes.NOT_FOUND) {
                return false;
            }
            int kinds = landing.kind != null ? landing.kind.bit() : EffectFamilies.controlKindMask(index);
            Ref<EntityStore> caster = landing.source != null && landing.source.isValid() ? landing.source : null;
            // 1. The foe's rules, asked first, so a refused talent's control shows the shrug word on an immune foe too.
            boolean ruled = landing.kind != null
                    ? BossRules.lands(accessor, target, landing.kind, landing.talent, caster)
                    : BossRules.landsEffect(accessor, target, index, landing.talent, caster);
            if (!ruled) {
                return false;
            }
            // 3. Who, read here since a step's ramp is read at the foe's rank: a boss or an elite takes it unowned, at
            // its rank's length and ramp (R672).
            boolean bossOrElite = BossRules.isBossOrElite(accessor, target);
            Ref<EntityStore> owner = bossOrElite ? null : caster;
            Steps steps = steps(ControlKinds.landing(kinds), EffectFamilies.isFixed(index),
                    ControlKinds.fixedSkipsImmunity(), landing.ignoringImmunity, landing.shrinks,
                    landing.ramp == null ? null : landing.ramp.at(bossOrElite));
            // 2. The foe's immunity.
            if (steps.asksImmunity() && StunControl.isImmune(accessor, target, kinds)) {
                return false;
            }
            float seconds = (float) landing.seconds.at(bossOrElite);
            if (!(seconds > 0f)) {
                return false;
            }
            // 4. The shrink, the share read once per foe per cast.
            float share = steps.readsShare()
                    ? landing.tally.share(accessor, target, () -> StunControl.immunityShare(accessor, target)) : 0f;
            float kept = kept(share, steps.shrinks());
            boolean landed;
            if (landing.stun) {
                // The stun's own Duration rules are the effect-length pass's, as it is seen landing.
                float held = seconds * kept;
                if (!(held > 0f)) {
                    return false;
                }
                landed = landing.stagger ? StunControl.stagger(accessor, target, held, owner)
                        : landing.effectId != null ? StunControl.stunUnder(accessor, target, held, owner, effectId)
                        : StunControl.stun(accessor, target, held, owner);
            } else {
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
                OverlapBehavior overlap = landing.overlap != null ? landing.overlap
                        : effect != null ? effect.getOverlapBehavior() : OverlapBehavior.OVERWRITE;
                landed = EffectDurations.landOwned(accessor, target, effectId, seconds, overlap, owner, kept);
            }
            if (!landed) {
                return false;
            }
            EffectLandings.watch(target, System.nanoTime());
            // 5. The ramp: the cast's one step on this foe.
            if (steps.rampAmount() > 0.0 && landing.tally.step(accessor, target)) {
                StunControl.rampImmunity(accessor, target, (float) steps.rampAmount());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Would a control of the kinds in {@code kindMask} be refused by {@code foe} now, before any length is read: its
     * rules ({@code byTalent} as a talent's, a refusal told to {@code caster}), else its immunity where one of the
     * kinds is {@code RefusedByImmune}. The gate a move that holds a foe for its own length asks before it takes one
     * ({@link CreatureHold#canMove}). False whenever it cannot tell.
     */
    public static boolean refuses(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nonnull ControlKind kind, boolean byTalent, @Nullable Ref<EntityStore> caster) {
        try {
            if (!BossRules.lands(accessor, foe, kind, byTalent, caster)) {
                return true;
            }
            return ControlKinds.landing(kind.bit()).refusedByImmune() && StunControl.isImmune(accessor, foe,
                    kind.bit());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * What one landing does about the foe's Immunity: whether a foe's immunity may refuse it, whether it reads the
     * share and shrinks by it, and the Immunity it adds once it lands.
     */
    public record Steps(boolean asksImmunity, boolean readsShare, boolean shrinks, double rampAmount) {
    }

    /**
     * The landing's steps from its kinds' rule ({@code rule}) and the step's own word: a fixed control the settings
     * exempt ({@code fixed} and {@code fixedSkipsImmunity}) asks no immunity, reads no share, never shrinks and adds
     * none; a fight's own ({@code ignoringImmunity}) asks no immunity, reads no share and never shrinks, and adds what
     * its ramp says; anything else asks the immunity where a kind is refused by it, reads the share, shrinks as
     * {@code shrinksOverride} or its kinds say, and adds {@code rampOverride} or its kinds' amount. Pure.
     */
    @Nonnull
    public static Steps steps(@Nonnull ControlKinds.Landing rule, boolean fixed, boolean fixedSkipsImmunity,
            boolean ignoringImmunity, @Nullable Boolean shrinksOverride, @Nullable Double rampOverride) {
        boolean exempt = fixed && fixedSkipsImmunity;
        boolean past = exempt || ignoringImmunity;
        boolean shrinks = !past && (shrinksOverride != null ? shrinksOverride : rule.shrinks());
        // The share is read whenever the immunity counts, shrunk by or not: a cast's tally keeps the first read for
        // every later landing on that foe (a carry's full-length stun, then the throw's shorter one).
        return new Steps(!past && rule.refusedByImmune(), !past, shrinks,
                exempt ? 0.0 : rampAmount(rampOverride, rule));
    }

    /**
     * What a landing keeps of its length: {@code 1 - share} (the share held to 0 to 1) when it shrinks, else all of
     * it. Pure.
     */
    public static float kept(float share, boolean shrinks) {
        return shrinks ? StunControl.diminished(1f, share) : 1f;
    }

    /**
     * The Immunity a landing adds: the step's own amount when it writes one, else its kinds' ({@code rule}); never
     * below 0. Pure.
     */
    public static double rampAmount(@Nullable Double stepAmount, @Nonnull ControlKinds.Landing rule) {
        double amount = stepAmount != null ? stepAmount : rule.rampAmount();
        return Double.isFinite(amount) ? Math.max(0.0, amount) : 0.0;
    }
}
