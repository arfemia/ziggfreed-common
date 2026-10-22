package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
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
 * <p><b>Crit and Finisher.</b> The library has no critical roll and counts no combo, so {@link ProcTrigger#CRIT} and
 * {@link ProcTrigger#FINISHER} fire only when a consumer that decides them says one landed ({@link #crit},
 * {@link #finisher}); on a server with none, those procs never run.
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

    /** Per thread: how deep inside a proc's actions it is ([0]), and whether it is running the moments held back ([1]). */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[2]);

    /** Per thread: the moments a proc's own actions raised (a heal it gave), run once it is done. */
    private static final ThreadLocal<List<ProcMoment>> LATER = ThreadLocal.withInitial(ArrayList::new);

    /** The most moments held back for after one proc run. */
    private static final int LATER_CAP = 16;

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
            // Inside a proc's actions no proc runs; a heal those actions gave is still a heal given, so it runs once
            // that proc is done (never one a held-back moment's own actions raise).
            if (depth[1] == 0 && runsAfter(moment.trigger())) {
                List<ProcMoment> later = LATER.get();
                if (later.size() < LATER_CAP) {
                    later.add(moment);
                }
            }
            return;
        }
        fireNow(moment, depth);
        List<ProcMoment> later = LATER.get();
        if (depth[1] != 0 || later.isEmpty()) {
            return;
        }
        List<ProcMoment> held = List.copyOf(later);
        later.clear();
        depth[1] = 1;
        try {
            for (ProcMoment next : held) {
                fireNow(next, depth);
            }
        } finally {
            depth[1] = 0;
            LATER.get().clear();
        }
    }

    /** Is a moment a proc's actions raise held back and run once that proc is done (else dropped)? */
    static boolean runsAfter(@Nonnull ProcTrigger trigger) {
        return trigger == ProcTrigger.HEAL_GIVEN;
    }

    /** One moment's run: its clears, then every proc answering it. */
    private static void fireNow(@Nonnull ProcMoment moment, @Nonnull int[] depth) {
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

    /**
     * The seam a consumer that decides combos calls when one closes: the player {@code attacker}'s hit {@code damage}
     * on {@code target} was the finishing hit of a combo (the last swing of a chain). World thread, from the
     * consumer's damage step; pass its command buffer. The library counts no combo itself, so on a server with no
     * consumer calling this Finisher procs never run.
     */
    public static void finisher(@Nonnull Store<EntityStore> store, @Nullable CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> attacker, @Nullable Ref<EntityStore> target, @Nonnull Damage damage) {
        if (!listens(ProcTrigger.FINISHER)) {
            return;
        }
        fire(new ProcMoment(ProcTrigger.FINISHER, store, commandBuffer, attacker, target, damage, damage.getAmount(),
                null));
    }

    /**
     * The seam a consumer that casts abilities of its own calls when one of the player {@code holder}'s casts truly
     * goes off (past its cost and its checks), naming it {@code ability}: a Cast moment a proc's {@code Ability.Names}
     * can ask for. World thread, from the consumer's cast step; pass its command buffer when it has one. A consumer
     * that calls this for the casts its own keys start says so through {@link ProcAbilities.Namer#castsItself}, so the
     * chain those keys start never counts as a second cast.
     */
    public static void cast(@Nonnull Store<EntityStore> store, @Nullable CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, @Nullable String ability) {
        if (!listens(ProcTrigger.CAST)) {
            return;
        }
        fire(new ProcMoment(ProcTrigger.CAST, store, commandBuffer, holder, null, null, 1.0, null, null,
                ability == null || ability.isBlank() ? null : ability.trim(), null));
    }

    /**
     * The seam a consumer that took the player {@code holder}'s ability key calls when it hands a press back to the
     * move the key displaced (the held item's own, a slotted rune's), just before it runs that move's {@code root} as
     * a server chain: that chain counts as the player's own press, so its Cast is proven like any other (it finished,
     * or still runs half a second on), never on the press alone. World thread.
     */
    public static void handBack(@Nonnull Ref<EntityStore> holder, @Nullable String root) {
        if (root == null || root.isBlank() || !listens(ProcTrigger.CAST)) {
            return;
        }
        try {
            MoveProofs.handBack(holder, root, System.nanoTime());
        } catch (Throwable t) {
            failed("a press handed back", t);
        }
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
        if (!effectPasses(proc, moment) || !hitPasses(proc, moment, accessor) || !movePasses(proc, moment, accessor)
                || !comingPasses(proc, moment, accessor) || !blockPasses(proc, moment, accessor)) {
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
        if (damage == null || !moment.trigger().struck()) {
            return proc.like() == null && (hit == null || hit.isEmpty());
        }
        // A coming hit (a burn tick that would kill as well as a blow) asks OverTime only when it is written.
        Boolean overTimeWanted = hit == null ? null : hit.overTime();
        if ((overTimeWanted != null || !moment.trigger().incoming())
                && Boolean.TRUE.equals(overTimeWanted) != HitReads.isOverTime(damage)) {
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

    /**
     * Does the move behind the moment pass the proc's {@code Ability} group? A cast reads the root its chain started
     * from and the ability its consumer named; a hit reads the root of the attacker's chain that dealt it
     * ({@link ChainRoots}) and the ability a consumer names for it ({@link ProcAbilities}). A proc that asks it of a
     * moment that names no move never runs.
     */
    private static boolean movePasses(@Nonnull ProcAsset proc, @Nonnull ProcMoment moment,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        ProcAsset.Ability ability = proc.ability();
        if (ability == null) {
            return true;
        }
        ProcTrigger trigger = moment.trigger();
        if (trigger == ProcTrigger.CAST) {
            return ability.matches(moment.root(), moment.ability());
        }
        Damage damage = moment.damage();
        if (!trigger.struck() || damage == null) {
            return false;
        }
        Ref<EntityStore> attacker = trigger.taken() ? moment.target() : moment.holder();
        String root = ability.roots().isEmpty() ? null : ChainRoots.rootOf(attacker, damage);
        String named = ability.names().isEmpty() ? null : ProcAbilities.hitAbility(accessor, attacker, damage);
        return ability.matches(root, named);
    }

    /**
     * Does a coming hit pass the proc's {@code LeavesUnder} (it would drop the one it lands on from above that share of
     * their health to it or under; 0: it would kill them) and, on an ally's, its {@code AllyRadius}? Every other
     * moment passes; a proc asking {@code LeavesUnder} of a moment that is no coming hit never runs.
     */
    private static boolean comingPasses(@Nonnull ProcAsset proc, @Nonnull ProcMoment moment,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        ProcTrigger trigger = moment.trigger();
        Double under = proc.leavesUnder();
        if (!trigger.incoming()) {
            return under == null;
        }
        Ref<EntityStore> struck = moment.struck();
        Damage damage = moment.damage();
        if (struck == null || !struck.isValid() || damage == null || damage.isCancelled()) {
            return false;
        }
        if (trigger == ProcTrigger.ALLY_HIT_INCOMING) {
            Vector3d holderAt = Nearby.positionOf(accessor, moment.holder());
            Vector3d allyAt = Nearby.positionOf(accessor, struck);
            double reach = proc.allyRadius();
            if (holderAt == null || allyAt == null || holderAt.distanceSquared(allyAt) > reach * reach) {
                return false;
            }
        }
        if (under == null) {
            return true;
        }
        EntityStatMap stats = accessor.getComponent(struck, EntityStatMap.getComponentType());
        EntityStatValue health = stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
        return health != null && dropsUnder(health.get(), health.getMin(), health.getMax(), damage.getAmount(), under);
    }

    /**
     * Would a hit of {@code hit} (rounded to whole points, as the engine lands it) drop health at {@code current},
     * between {@code min} and {@code max}, from above {@code percent} of the maximum to it or under? A percent of 0 or
     * less asks whether it would kill (health at its minimum or under). Pure.
     */
    static boolean dropsUnder(double current, double min, double max, double hit, double percent) {
        double after = current - Math.round(hit);
        if (!(percent > 0.0)) {
            return current > min && after <= min;
        }
        double line = Math.max(min, max * percent / 100.0);
        return current > line && after <= line;
    }

    /**
     * Does a block pass the proc's {@code WouldBreakGuard}: its stamina cost more than the holder has left (the
     * guard's own drain would take it under nothing, which breaks it), or not? Every other moment passes; a proc
     * asking it of a moment that is no block never runs.
     */
    private static boolean blockPasses(@Nonnull ProcAsset proc, @Nonnull ProcMoment moment,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        Boolean wanted = proc.wouldBreakGuard();
        if (wanted == null) {
            return true;
        }
        if (moment.trigger() != ProcTrigger.BLOCKED) {
            return false;
        }
        EntityStatMap stats = accessor.getComponent(moment.holder(), EntityStatMap.getComponentType());
        EntityStatValue stamina = stats == null ? null : stats.get(DefaultEntityStatTypes.getStamina());
        return stamina != null && breaksGuard(stamina.get(), moment.cost()) == wanted;
    }

    /** Would a block costing {@code cost} stamina, with {@code stamina} left, break the guard? Pure. */
    static boolean breaksGuard(double stamina, double cost) {
        return cost > 0.0 && stamina - cost < 0.0;
    }

    /**
     * Does an effect moment's effect pass the proc's {@code Effect} group (on the same side, named by id or family)?
     * Every other moment passes. An effect proc naming no effect never runs.
     */
    private static boolean effectPasses(@Nonnull ProcAsset proc, @Nonnull ProcMoment moment) {
        if (!moment.trigger().aboutEffect()) {
            return true;
        }
        ProcAsset.Effect wanted = proc.effect();
        ProcMoment.EffectSeen seen = moment.effect();
        if (wanted == null || seen == null || wanted.onFoe() != seen.onFoe()) {
            return false;
        }
        return EffectWatch.matches(wanted, seen.id(), EntityEffect.getAssetMap().getIndex(seen.id()));
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
     * The holder pass, every tick for every living player: the moves waiting for their proof and a guard going up
     * ({@link MoveProofs}) and the holder's own watched effects ({@link EffectWatch}) each tick; then, a few times a
     * second ({@value #PASS_EVERY_NANOS} ns), the counters' sweep and decay, the held effects, the watched effects on
     * the foes they struck, and the crouch a {@code CrouchStill} proc answers (its amount the seconds held). World
     * thread; never throws.
     */
    static void holderPass(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder) {
        try {
            long nanos = System.nanoTime();
            MoveProofs.check(store, commandBuffer, holder, nanos);
            GuardBreaks.check(store, commandBuffer, holder, nanos);
            EffectWatch.checkOwn(store, commandBuffer, holder);
            PassState state = PASSES.computeIfAbsent(holder, r -> new PassState());
            if (nanos < state.nextNanos) {
                return;
            }
            state.nextNanos = nanos + PASS_EVERY_NANOS;
            ProcMoment self = new ProcMoment(ProcTrigger.CROUCH_STILL, store, commandBuffer, holder, null, null, 0.0,
                    null);
            FactorContext context = context(self, null);
            Counters.pass(commandBuffer, holder, context, nanos);
            HeldEffects.pass(commandBuffer, holder, context, ConditionalStats.now(store), nanos);
            EffectWatch.checkFoes(store, commandBuffer, holder, nanos);
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
        MoveProofs.forget(ref);
        ChainRoots.forget(ref);
        EffectWatch.forget(ref);
        HeldEffects.forget(ref);
        GuardBreaks.forget(ref);
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
