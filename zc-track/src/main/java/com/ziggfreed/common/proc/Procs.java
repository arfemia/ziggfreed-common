package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.ConditionalStatAsset;
import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.stats.condition.FirstHits;
import com.ziggfreed.common.stats.condition.HitReads;
import com.ziggfreed.common.stats.condition.Nearby;
import com.ziggfreed.common.stats.condition.ZigCrouchingCondition;
import com.ziggfreed.common.stats.condition.ZigMovingCondition;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * The proc runtime ({@link ProcAsset}): a moment comes in ({@link #fire}), every live proc answering its trigger is
 * asked, cheapest first, whether it runs (its {@code Requires} of the holder, the moment's amount, the hit's reads, the
 * holder's and target's conditions, its wait, its chance, its strength), and each that does runs its actions
 * ({@link ProcActions}) and starts its wait. The counters a moment clears are emptied first. Players only; world
 * thread; never throws into the producer.
 *
 * <p><b>Numbers.</b> A proc's chance, strength and wait, and a counter's size, are formulas over the factor
 * vocabulary (a talent's rank through {@code ziggfreedcommon:track_pick}, a stat through {@code hytale:stat}), and the
 * library's bounded families raise them for every proc at once, so gear and buffs reach them too (R757, R758):
 * {@value #CHANCE_STAT} (percent points on a rolled chance), {@value #STRENGTH_STAT} (percent on the strength),
 * {@value #COOLDOWN_CUT_STAT} (percent off a wait, held at {@value #COOLDOWN_CUT_MAX_STAT}) and
 * {@value #COUNTER_SIZE_STAT} (percent on a counter's size). Each reads off its stat's maximum.
 *
 * <p><b>Crit.</b> The library has no critical roll, so {@link ProcTrigger#CRIT} fires only when a consumer that decides
 * crits says one landed ({@link #crit}); on a server with none, Crit procs never run.
 *
 * <p>No proc runs inside another proc's actions (a guard per thread), and a hit a proc dealt itself (an effect it put
 * on, a chain it ran: {@link ProcMarks}) is no moment for any proc or counter clear, so content can never build a proc
 * that sets itself off.
 */
public final class Procs {

    /** The owner the library's proc readings and moment reaction are registered under. */
    public static final String OWNER = "ziggfreedcommon";

    /** Whole percent points added to every rolled proc chance. */
    public static final String CHANCE_STAT = "ProcChance";

    /** Whole percent points added to every proc's strength. */
    public static final String STRENGTH_STAT = "ProcStrength";

    /** Whole percent points cut from every proc's wait. */
    public static final String COOLDOWN_CUT_STAT = "ProcCooldownCut";

    /** The ceiling {@value #COOLDOWN_CUT_STAT} is held to. */
    public static final String COOLDOWN_CUT_MAX_STAT = "ProcCooldownCutMax";

    /** Whole percent points added to every counter's size. */
    public static final String COUNTER_SIZE_STAT = "CounterSize";

    /** The cooldown-cut ceiling when its stat is not loaded; mirrors the shipped {@code ProcCooldownCutMax} asset. */
    static final double FALLBACK_COOLDOWN_CUT_MAX = 50.0;

    /** How often a holder's pass reads its counters, held effects and crouch again: four times a second. */
    public static final long PASS_EVERY_NANOS = 250_000_000L;

    /** How many foes a {@code Seek} asks before it gives up. */
    private static final int SEEK_LIMIT = 8;

    /** The vocabulary a proc's numbers and gates read first: the engine readings (stats, the held item). */
    private static final FactorRegistry FACTORS = factors();

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private static final AtomicBoolean FAILURE_WARNED = new AtomicBoolean();

    /** One holder's pass: when it is read next, and since when it has stood crouched and still (-1 while not). */
    private static final class PassState {
        long nextNanos;
        long crouchSince = -1L;
    }

    private static final Map<Ref<EntityStore>, PassState> PASSES = new ConcurrentHashMap<>();

    private Procs() {
    }

    private static FactorRegistry factors() {
        FactorRegistry registry = new FactorRegistry("procs");
        HytaleFactors.registerInto(registry, OWNER);
        return registry;
    }

    // ==================== The moments ====================

    /** Does any live proc answer {@code trigger}, or any counter clear on it? One lookup; a producer asks it first. */
    public static boolean listens(@Nonnull ProcTrigger trigger) {
        return !ProcConfig.getInstance().forTrigger(trigger).isEmpty()
                || !CounterConfig.getInstance().clearedBy(trigger).isEmpty();
    }

    /**
     * A moment happened to the player {@code moment.holder()}: empty the counters it clears, then run every proc
     * answering it that passes. World thread. A consumer with a moment of one of these kinds the library does not
     * produce itself fires it here (its own dodge, its own guard). Never throws.
     */
    public static void fire(@Nonnull ProcMoment moment) {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            return;
        }
        List<ProcConfig.Def> procs = ProcConfig.getInstance().forTrigger(moment.trigger());
        List<CounterConfig.Def> clears = CounterConfig.getInstance().clearedBy(moment.trigger());
        if ((procs.isEmpty() && clears.isEmpty()) || ProcMarks.fromProc(moment.damage())) {
            return;
        }
        depth[0]++;
        try {
            Ref<EntityStore> holder = moment.holder();
            if (!holder.isValid() || moment.accessor().getComponent(holder, PlayerRef.getComponentType()) == null) {
                return;
            }
            Counters.clearOn(moment, clears);
            for (ProcConfig.Def def : procs) {
                try {
                    run(def, moment);
                } catch (Throwable t) {
                    failed("running the proc '" + def.key() + "'", t);
                }
            }
        } catch (Throwable t) {
            failed("a " + moment.trigger().id() + " moment", t);
        } finally {
            depth[0]--;
        }
    }

    /**
     * The seam a consumer that decides critical hits calls when one lands: the player {@code attacker}'s hit
     * {@code damage} on {@code target} was critical. World thread, from the consumer's damage step; pass its command
     * buffer. The library decides no crit itself, so on a server with no consumer calling this Crit procs never run.
     */
    public static void crit(@Nonnull Store<EntityStore> store, @Nullable CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> attacker, @Nullable Ref<EntityStore> target, @Nonnull Damage damage) {
        if (!listens(ProcTrigger.CRIT)) {
            return;
        }
        fire(new ProcMoment(ProcTrigger.CRIT, store, commandBuffer, attacker, target, damage, damage.getAmount(),
                null));
    }

    /** One proc on one moment. */
    private static void run(@Nonnull ProcConfig.Def def, @Nonnull ProcMoment moment) {
        ProcAsset proc = def.asset();
        ComponentAccessor<EntityStore> accessor = moment.accessor();
        Ref<EntityStore> holder = moment.holder();
        FactorContext context = context(moment, moment.target());
        if (!gatePasses(proc.liveRequires(), context)) {
            return;
        }
        Double minAmount = proc.minAmount();
        if (minAmount != null && moment.amount() < minAmount) {
            return;
        }
        if (!hitPasses(proc, moment, accessor)) {
            return;
        }
        Instant now = ConditionalStats.now(accessor);
        if (!Nearby.allHold(accessor, holder, proc.holderConditions(), now)) {
            return;
        }
        UUID holderId = FirstHits.idOf(accessor, holder);
        if (holderId == null) {
            return;
        }
        ProcAsset.Cooldown cooldown = proc.cooldown();
        boolean perTarget = cooldown != null && cooldown.perTarget();
        long nanos = System.nanoTime();
        Ref<EntityStore> target = moment.target();
        ProcAsset.Seek seek = proc.seek();
        if (target == null && seek != null) {
            target = seekTarget(accessor, holder, proc, holderId, def.key(), perTarget, now, nanos);
            if (target == null) {
                return;
            }
            context = context(moment, target);
        } else if (proc.targetConditions().length > 0) {
            if (target == null || !target.isValid() || !Nearby.allHold(accessor, target, proc.targetConditions(), now)) {
                return;
            }
        }
        if (!ProcCooldowns.ready(holderId, def.key(), perTarget ? target : null, nanos)) {
            return;
        }
        if (!rolls(proc, accessor, holder, context)) {
            return;
        }
        double strength = proc.strength() == null ? 1.0 : evaluate(proc.strength(), context);
        if (!(strength > 0.0)) {
            return;
        }
        strength *= 1.0 + statPoints(accessor, holder, STRENGTH_STAT) / 100.0;
        if (!(strength > 0.0)) {
            return;
        }
        ProcActions.run(proc, moment.about(target), strength, context);
        if (cooldown != null && cooldown.seconds() != null) {
            double seconds = evaluate(cooldown.seconds(), context);
            ProcCooldowns.stamp(holderId, def.key(), perTarget ? target : null, nanos, seconds,
                    statPoints(accessor, holder, COOLDOWN_CUT_STAT), cooldownCeiling(accessor, holder));
        }
    }

    /**
     * Does the moment's hit pass the proc's {@code Hit} group and {@code Like}, read as the hit's attacker dealt it?
     * {@code OverTime} is false unless written. A proc that asks either of a moment that carries no hit never runs.
     */
    private static boolean hitPasses(@Nonnull ProcAsset proc, @Nonnull ProcMoment moment,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        ConditionalStatAsset.Hit hit = proc.hit();
        Damage damage = moment.damage();
        if (damage == null) {
            return proc.like() == null && (hit == null || hit.isEmpty());
        }
        boolean overTime = hit != null && Boolean.TRUE.equals(hit.overTime());
        if (overTime != HitReads.isOverTime(damage)) {
            return false;
        }
        Ref<EntityStore> attacker = moment.trigger().taken() ? moment.target() : moment.holder();
        Ref<EntityStore> struck = moment.trigger().taken() ? moment.holder() : moment.target();
        if (hit != null && !hit.isEmpty()) {
            if (attacker == null || !attacker.isValid() || !HitReads.meets(accessor, attacker, struck, damage, hit)) {
                return false;
            }
        }
        String like = proc.like();
        if (like != null) {
            if (attacker == null || !attacker.isValid()) {
                return false;
            }
            DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
            return TypedDamageBonuses.countsOn(accessor, attacker, damage, cause == null ? null : cause.getId(), like);
        }
        return true;
    }

    /** The nearest foe within the proc's {@code Seek} radius that meets its Target conditions and is off its wait. */
    @Nullable
    private static Ref<EntityStore> seekTarget(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull ProcAsset proc, @Nonnull UUID holderId, @Nonnull String key,
            boolean perTarget, @Nonnull Instant now, long nanos) {
        ProcAsset.Seek seek = proc.seek();
        if (seek == null) {
            return null;
        }
        for (Ref<EntityStore> foe : Nearby.foesWithin(accessor, holder, seek.radius(), proc.targetConditions(), now,
                SEEK_LIMIT)) {
            if (!perTarget || ProcCooldowns.ready(holderId, key, foe, nanos)) {
                return foe;
            }
        }
        return null;
    }

    /** Does the proc's chance come up? Always with no {@code Chance}; never at 0 or less. */
    private static boolean rolls(@Nonnull ProcAsset proc, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull FactorContext context) {
        if (proc.chance() == null) {
            return true;
        }
        double chance = evaluate(proc.chance(), context);
        if (!(chance > 0.0)) {
            return false;
        }
        chance += statPoints(accessor, holder, CHANCE_STAT);
        return chance >= 100.0 || ThreadLocalRandom.current().nextDouble() * 100.0 < chance;
    }

    // ==================== The holder pass ====================

    /**
     * The holder pass, every tick for every living player, reading again a few times a second
     * ({@value #PASS_EVERY_NANOS} ns): the counters' sweep and decay, the held effects, and the crouch a
     * {@code CrouchStill} proc answers (its amount the seconds held). World thread; never throws.
     */
    static void holderPass(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder) {
        try {
            long nanos = System.nanoTime();
            PassState state = PASSES.computeIfAbsent(holder, r -> new PassState());
            if (nanos < state.nextNanos) {
                return;
            }
            state.nextNanos = nanos + PASS_EVERY_NANOS;
            ProcMoment self = new ProcMoment(ProcTrigger.CROUCH_STILL, store, commandBuffer, holder, null, null, 0.0,
                    null);
            FactorContext context = context(self, null);
            Counters.pass(commandBuffer, holder, context, nanos);
            HeldEffects.pass(commandBuffer, holder, context, ConditionalStats.now(store));
            if (!listens(ProcTrigger.CROUCH_STILL)) {
                state.crouchSince = -1L;
                return;
            }
            if (!crouchedStill(commandBuffer, holder)) {
                state.crouchSince = -1L;
                return;
            }
            if (state.crouchSince < 0L) {
                state.crouchSince = nanos;
            }
            fire(new ProcMoment(ProcTrigger.CROUCH_STILL, store, commandBuffer, holder, null, null,
                    (nanos - state.crouchSince) / 1_000_000_000.0, null));
        } catch (Throwable t) {
            failed("a holder pass", t);
        }
    }

    /** Is the player crouched and standing still (their client's horizontal speed under a walk)? */
    static boolean crouchedStill(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        if (!ZigCrouchingCondition.crouching(accessor, ref)) {
            return false;
        }
        Velocity velocity = accessor.getComponent(ref, Velocity.getComponentType());
        Vector3d v = velocity == null ? null : velocity.getClientVelocity();
        return v == null || Math.sqrt(v.x * v.x + v.z * v.z) < ZigMovingCondition.DEFAULT_MIN_SPEED;
    }

    /** Forget everything kept about {@code ref} (it left its store): as a holder and as a target. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        PASSES.remove(ref);
        Counters.forget(ref);
        Counters.targetGone(ref);
        ProcCooldowns.forgetTarget(ref);
    }

    // ==================== The reads every part shares ====================

    /** The factor context a proc's gates and numbers are read in: the holder as subject, about {@code target}. */
    @Nonnull
    static FactorContext context(@Nonnull ProcMoment moment, @Nullable Ref<EntityStore> target) {
        World world = null;
        try {
            world = moment.store().getExternalData().getWorld();
        } catch (Throwable ignored) {
            // A store with no world (a test): read without one.
        }
        return FactorContext.builder().store(moment.store()).subject(moment.holder()).world(world).target(target)
                .blockId(moment.blockId()).build();
    }

    /** Does {@code live} pass for the context's player? Null (nothing to ask) passes. */
    static boolean gatePasses(@Nullable GateSpec live, @Nonnull FactorContext context) {
        return live == null || StoreGates.passes(live, GateQuery.builder().context(context).local(FACTORS).build());
    }

    /** A formula's value in {@code context}; 0 when it is not a number. Never throws. */
    static double evaluate(@Nonnull FactorFormula formula, @Nonnull FactorContext context) {
        try {
            double value = formula.evaluate(FACTORS, context);
            return Double.isFinite(value) ? value : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** A stat's reading off its maximum, where gear, effects and picks raise it; 0 when it is not there. */
    static double statPoints(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId) {
        try {
            int index = StatIndexCache.resolve(statId);
            if (index == StatIndexCache.UNRESOLVED) {
                return 0.0;
            }
            EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
            EntityStatValue value = stats == null ? null : stats.get(index);
            double max = value == null ? 0.0 : value.getMax();
            return Double.isFinite(max) ? max : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** The holder's cooldown-cut ceiling: its {@value #COOLDOWN_CUT_MAX_STAT}, else the shipped default. */
    private static double cooldownCeiling(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder) {
        return StatIndexCache.resolve(COOLDOWN_CUT_MAX_STAT) == StatIndexCache.UNRESOLVED
                ? FALLBACK_COOLDOWN_CUT_MAX : statPoints(accessor, holder, COOLDOWN_CUT_MAX_STAT);
    }

    /** Name a failure once at WARN, then at FINE. */
    static void failed(@Nonnull String what, @Nonnull Throwable t) {
        if (FAILURE_WARNED.compareAndSet(false, true)) {
            SafeLog.warn("[proc] " + what + " failed (named once): " + t);
        } else {
            SafeLog.fine("[proc] " + what + " failed: " + t.getMessage());
        }
    }
}
