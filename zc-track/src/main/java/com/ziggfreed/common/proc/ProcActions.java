package com.ziggfreed.common.proc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.EffectAdds;
import com.ziggfreed.common.effect.control.AggroControl;
import com.ziggfreed.common.entity.Allies;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.interaction.NativeChainFire;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.stats.condition.FirstHits;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * What a proc does ({@link ProcAction}), each through the engine's own call: an effect put on with the holder as its
 * owner (so its damage and a kill it lands credit them) and this application's damage multiplier; effects taken off
 * by id or effect family; a stat's current value moved (the engine holds it between its bounds); a counter changed; a
 * push or pull through the engine's velocity; a creature taunted onto the holder (zc-effects' {@code AggroControl});
 * a guard broken; a RootInteraction queued on the holder or the target as a server chain. An entry's {@code Area} aims
 * its target groups at each one reached in turn. World thread; each action is guarded on its own, so one that fails
 * costs only itself.
 */
final class ProcActions {

    /**
     * The label a proc's own chain runs under: the engine's own label for a chain it runs for a stat's effect, a
     * server chain no input starts, that no other chain blocks or is blocked by and that keeps no default cooldown
     * ({@code InteractionTypeUtils}). Never one of the input types the procs listen to, so a proc's chain never reads
     * as the holder's own dodge, guard or cast, and the damage it deals carries this label, which marks it a proc's
     * own ({@link ProcMarks}).
     */
    static final InteractionType CHAIN_TYPE = InteractionType.EntityStatEffect;

    private ProcActions() {
    }

    /** Run every action of {@code proc} on {@code moment} (its target already sought) at {@code strength}. */
    static void run(@Nonnull ProcAsset proc, @Nonnull ProcMoment moment, double strength,
            @Nonnull FactorContext context) {
        for (ProcAction action : proc.actions()) {
            List<Ref<EntityStore>> aims = aims(moment, action.area());
            runSelf(action, moment, strength, context);
            for (Ref<EntityStore> aim : aims) {
                ProcMoment about = aim == moment.target() ? moment : moment.about(aim);
                FactorContext aimContext = aim == moment.target() ? context : Procs.context(moment, aim);
                runAimed(action, about, strength, aimContext);
            }
            if (action.counter() != null) {
                guarded("Counter", () -> counter(moment, action.counter(), context));
            }
            ProcAction.RunInteraction run = action.runInteraction();
            if (run != null) {
                if (action.area() == null) {
                    guarded("RunInteraction", () -> runInteraction(moment, run));
                } else {
                    for (Ref<EntityStore> aim : aims) {
                        guarded("RunInteraction", () -> runInteraction(moment.about(aim), run));
                    }
                }
            }
        }
    }

    /** The entry's groups aimed at the holder, once. */
    private static void runSelf(@Nonnull ProcAction action, @Nonnull ProcMoment moment, double strength,
            @Nonnull FactorContext context) {
        if (action.removeEffect() != null && action.removeEffect().onSelf()) {
            guarded("RemoveEffect", () -> removeEffect(moment, moment.holder(), action.removeEffect()));
        }
        if (action.applyEffect() != null && action.applyEffect().onSelf()) {
            guarded("ApplyEffect", () -> applyEffect(moment, moment.holder(), action.applyEffect(), strength,
                    context));
        }
        if (action.changeStat() != null && action.changeStat().onSelf()) {
            guarded("ChangeStat", () -> changeStat(moment, moment.holder(), action.changeStat(), strength, context));
        }
        if (action.push() != null && action.push().onSelf()) {
            guarded("Push", () -> push(moment, moment.holder(), moment.target(), action.push(), context));
        }
    }

    /** The entry's groups aimed at one target ({@code moment.target()}). */
    private static void runAimed(@Nonnull ProcAction action, @Nonnull ProcMoment moment, double strength,
            @Nonnull FactorContext context) {
        Ref<EntityStore> on = moment.target();
        if (on == null || !on.isValid()) {
            return;
        }
        if (action.removeEffect() != null && !action.removeEffect().onSelf()) {
            guarded("RemoveEffect", () -> removeEffect(moment, on, action.removeEffect()));
        }
        if (action.applyEffect() != null && !action.applyEffect().onSelf()) {
            guarded("ApplyEffect", () -> applyEffect(moment, on, action.applyEffect(), strength, context));
        }
        if (action.changeStat() != null && !action.changeStat().onSelf()) {
            guarded("ChangeStat", () -> changeStat(moment, on, action.changeStat(), strength, context));
        }
        if (action.push() != null && !action.push().onSelf()) {
            guarded("Push", () -> push(moment, on, moment.holder(), action.push(), context));
        }
        if (action.taunt() != null) {
            guarded("Taunt", () -> taunt(moment, on, action.taunt(), context));
        }
        if (action.breakGuard()) {
            guarded("BreakGuard", () -> breakGuard(moment.accessor(), on));
        }
    }

    // ==================== Who an entry aims at ====================

    /**
     * The targets an entry's target groups aim at: the moment's target alone with no {@code Area}; with one, each
     * foe (or ally) in reach, nearest the centre first, held to its {@code Max}.
     */
    @Nonnull
    static List<Ref<EntityStore>> aims(@Nonnull ProcMoment moment, @Nullable ProcAction.Area area) {
        Ref<EntityStore> target = moment.target();
        if (area == null) {
            return target == null || !target.isValid() ? List.of() : List.of(target);
        }
        try {
            ComponentAccessor<EntityStore> accessor = moment.accessor();
            Ref<EntityStore> holder = moment.holder();
            Ref<EntityStore> centreOn = area.aroundTarget() ? target : holder;
            if (centreOn == null || !centreOn.isValid() || area.max() <= 0) {
                return List.of();
            }
            Vector3d centre = Nearby.positionOf(accessor, centreOn);
            if (centre == null) {
                return List.of();
            }
            Instant now = ConditionalStats.now(accessor);
            // One more than Max, so leaving the moment's own target out still reaches Max others.
            int ask = area.max() + (area.skipTarget() ? 1 : 0);
            List<Ref<EntityStore>> found = area.allies()
                    ? allies(accessor, pvpOn(moment), holder, centre, area, now, ask)
                    : Nearby.foesAround(accessor, holder, centre, area.radius(), area.conditions(), now, ask);
            List<Ref<EntityStore>> out = new ArrayList<>(found.size());
            for (Ref<EntityStore> one : found) {
                if (area.skipTarget() && Nearby.same(one, target)) {
                    continue;
                }
                if (out.size() < area.max()) {
                    out.add(one);
                }
            }
            return out;
        } catch (Throwable t) {
            Procs.failed("finding an area's targets", t);
            return List.of();
        }
    }

    /** The holder's allies in reach: the other players there the ally rule names (zc-core's {@code Allies}). */
    @Nonnull
    private static List<Ref<EntityStore>> allies(@Nonnull ComponentAccessor<EntityStore> accessor, boolean pvp,
            @Nonnull Ref<EntityStore> holder, @Nonnull Vector3d centre, @Nonnull ProcAction.Area area,
            @Nonnull Instant now, int limit) {
        UUID holderId = FirstHits.idOf(accessor, holder);
        if (holderId == null) {
            return List.of();
        }
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (Ref<EntityStore> other : Nearby.playersAround(accessor, holder, centre, area.radius(), area.conditions(),
                now, 0)) {
            UUID otherId = FirstHits.idOf(accessor, other);
            if (otherId != null && Allies.areAllies(holderId, otherId, pvp)) {
                out.add(other);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** Is PvP on in the moment's world? Read as on when it cannot be read, so no stranger becomes an ally. */
    private static boolean pvpOn(@Nonnull ProcMoment moment) {
        try {
            World world = moment.store().getExternalData().getWorld();
            return world == null || world.getWorldConfig().isPvpEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    // ==================== The groups ====================

    private static void applyEffect(@Nonnull ProcMoment moment, @Nonnull Ref<EntityStore> on,
            @Nonnull ProcAction.ApplyEffect apply, double strength, @Nonnull FactorContext context) {
        String id = apply.effect();
        if (!on.isValid() || id == null) {
            return;
        }
        int index = EntityEffect.getAssetMap().getIndex(id);
        EntityEffect effect = index == AssetMapWithIndexes.NOT_FOUND ? null : EntityEffect.getAssetMap().getAsset(index);
        ComponentAccessor<EntityStore> accessor = moment.accessor();
        EffectControllerComponent controller = effect == null ? null
                : accessor.getComponent(on, EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }
        boolean added;
        if (apply.duration() == null && effect.isInfinite()) {
            added = EffectAdds.asAuthored(controller, on, index, effect, accessor);
        } else {
            float seconds = apply.duration() == null ? effect.getDuration()
                    : (float) Math.max(0.0, Procs.evaluate(apply.duration(), context));
            OverlapBehavior overlap = apply.overlap() == null ? effect.getOverlapBehavior() : apply.overlap();
            added = controller.addEffect(on, index, effect, seconds, overlap, accessor, moment.holder());
        }
        if (!added) {
            return;
        }
        double multiplier = apply.damageMultiplier() == null ? 1.0 : Procs.evaluate(apply.damageMultiplier(), context);
        ActiveEntityEffect active = controller.getActiveEffects().get(index);
        if (active != null) {
            active.setDamageMultiplier((float) scaledMultiplier(multiplier, strength));
            ProcMarks.markApplied(active);
        }
        if (!Nearby.same(on, moment.holder())) {
            EffectWatch.noteFoe(moment.holder(), on, System.nanoTime());
        }
    }

    private static void removeEffect(@Nonnull ProcMoment moment, @Nonnull Ref<EntityStore> on,
            @Nonnull ProcAction.RemoveEffect remove) {
        if (!on.isValid()) {
            return;
        }
        ComponentAccessor<EntityStore> accessor = moment.accessor();
        EffectControllerComponent controller = accessor.getComponent(on, EffectControllerComponent.getComponentType());
        if (controller == null) {
            return;
        }
        List<String> ids = remove.effects();
        List<String> families = remove.families();
        int left = remove.count();
        for (int index : controller.getActiveEffects().keySet().toIntArray()) {
            if (!matches(index, ids, families)) {
                continue;
            }
            controller.removeEffect(on, index, accessor);
            if (left > 0 && --left == 0) {
                return;
            }
        }
    }

    /** Is the effect at {@code index} one of {@code ids}, or in one of {@code families}? */
    private static boolean matches(int index, @Nonnull List<String> ids, @Nonnull List<String> families) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(index);
        String id = effect == null ? null : effect.getId();
        if (id != null) {
            for (String wanted : ids) {
                if (wanted.equalsIgnoreCase(id)) {
                    return true;
                }
            }
        }
        for (String family : families) {
            if (EffectFamilies.contains(family, index)) {
                return true;
            }
        }
        return false;
    }

    private static void changeStat(@Nonnull ProcMoment moment, @Nonnull Ref<EntityStore> on,
            @Nonnull ProcAction.ChangeStat change, double strength, @Nonnull FactorContext context) {
        String stat = change.stat();
        if (!on.isValid() || stat == null || change.amount() == null) {
            return;
        }
        int index = StatIndexCache.resolve(stat);
        EntityStatMap stats = index == StatIndexCache.UNRESOLVED ? null
                : moment.accessor().getComponent(on, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        double amount = statAmount(Procs.evaluate(change.amount(), context), change.scaled(), strength);
        if (amount != 0.0 && Double.isFinite(amount)) {
            stats.addStatValue(index, (float) amount);
        }
    }

    private static void counter(@Nonnull ProcMoment moment, @Nonnull ProcAction.CounterChange change,
            @Nonnull FactorContext context) {
        CounterConfig.Def def = CounterConfig.getInstance().live(change.of());
        if (def == null) {
            return;
        }
        double add = counterAdd(change.add() == null ? 0.0 : Procs.evaluate(change.add(), context), change.byAmount(),
                moment.amount());
        double consume = change.consume() == null ? 0.0 : Procs.evaluate(change.consume(), context);
        Counters.change(moment, def, add, consume, change.clear(), context);
    }

    /**
     * The stacks a counter's add puts on: the authored add, times the moment's amount when {@code byAmount}. Never
     * the proc's strength: a stored share of a hit is spent by an effect whose damage multiplier takes the strength
     * ({@link #scaledMultiplier}), so the strength counts once. Pure.
     */
    static double counterAdd(double add, boolean byAmount, double momentAmount) {
        return byAmount ? add * momentAmount : add;
    }

    /**
     * A stat change's amount: the authored one, times the proc's strength unless the change writes
     * {@code Scaled: false} (a refund of a cost, which must never pay back more than was spent). Pure.
     */
    static double statAmount(double amount, boolean scaled, double strength) {
        return scaled ? amount * strength : amount;
    }

    /** An application's damage multiplier: the authored one times the proc's strength, never below 0. Pure. */
    static double scaledMultiplier(double multiplier, double strength) {
        return Math.max(0.0, multiplier * strength);
    }

    /**
     * Move {@code on} away from {@code from} along the ground at the push's {@code Force} (negative: toward it), and
     * up at its {@code Up}: one Add instruction on the engine's velocity, which a player's client applies and a
     * creature's role scales by its knockback scale. Never the knockback component.
     */
    private static void push(@Nonnull ProcMoment moment, @Nonnull Ref<EntityStore> on,
            @Nullable Ref<EntityStore> from, @Nonnull ProcAction.Push push, @Nonnull FactorContext context) {
        if (!on.isValid()) {
            return;
        }
        double force = push.force() == null ? 0.0 : Procs.evaluate(push.force(), context);
        double up = push.up() == null ? 0.0 : Procs.evaluate(push.up(), context);
        ComponentAccessor<EntityStore> accessor = moment.accessor();
        double dx = 0.0;
        double dz = 0.0;
        if (force != 0.0 && from != null && from.isValid() && !Nearby.same(on, from)) {
            Vector3d at = Nearby.positionOf(accessor, on);
            Vector3d away = Nearby.positionOf(accessor, from);
            if (at != null && away != null) {
                double x = at.x - away.x;
                double z = at.z - away.z;
                double length = Math.sqrt(x * x + z * z);
                if (length > 1e-4) {
                    dx = x / length * force;
                    dz = z / length * force;
                }
            }
        }
        if (dx == 0.0 && dz == 0.0 && up == 0.0) {
            return;
        }
        Velocity velocity = accessor.getComponent(on, Velocity.getComponentType());
        if (velocity != null) {
            velocity.addInstruction(new Vector3d(dx, up, dz), null, ChangeVelocityType.Add);
        }
    }

    private static void taunt(@Nonnull ProcMoment moment, @Nonnull Ref<EntityStore> on, @Nonnull ProcAction.Taunt taunt,
            @Nonnull FactorContext context) {
        double seconds = taunt.seconds() == null ? ProcAction.Taunt.DEFAULT_SECONDS
                : Procs.evaluate(taunt.seconds(), context);
        if (seconds > 0.0) {
            AggroControl.tauntOne(moment.accessor(), moment.holder(), on, (float) seconds);
        }
    }

    /**
     * Break the guard {@code on} holds up: its block stops at once (the engine's current wielding cleared) and its
     * guard chain is cancelled through the engine's own cancel, so it may raise its guard again on its next press. Not
     * the engine's {@code Stamina_Broken}: that effect is infinite, and only the stamina system takes it off.
     */
    static void breakGuard(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> on) {
        DamageDataComponent data = accessor.getComponent(on, DamageDataComponent.getComponentType());
        if (data == null || data.getCurrentWielding() == null) {
            return;
        }
        data.setCurrentWielding(null);
        InteractionManager manager = accessor.getComponent(on,
                InteractionModule.get().getInteractionManagerComponent());
        if (manager == null) {
            return;
        }
        List<InteractionChain> guards = new ArrayList<>(1);
        for (InteractionChain chain : manager.getChains().values()) {
            if (chain != null && chain.getServerState() == InteractionState.NotFinished && holdsGuard(chain)) {
                guards.add(chain);
            }
        }
        for (InteractionChain chain : guards) {
            manager.cancelChains(chain);
        }
    }

    /**
     * Is {@code chain} the one holding a guard up: an input chain whose type a guard rides (a sword's Secondary, a
     * shield's), or a Wielding chain? Ability and Primary chains are never cancelled.
     */
    private static boolean holdsGuard(@Nonnull InteractionChain chain) {
        InteractionType type = chain.getType();
        return type == InteractionType.Secondary || type == InteractionType.Wielding;
    }

    private static void runInteraction(@Nonnull ProcMoment moment, @Nonnull ProcAction.RunInteraction run) {
        String id = run.interaction();
        Ref<EntityStore> runner = run.onSelf() ? moment.holder() : moment.target();
        Ref<EntityStore> other = run.onSelf() ? moment.target() : moment.holder();
        if (id == null || runner == null || !runner.isValid()) {
            return;
        }
        NativeChainFire.fire(moment.store(), runner, id, CHAIN_TYPE, context -> {
            if (other != null && other.isValid()) {
                context.getMetaStore().putMetaObject(Interaction.TARGET_ENTITY, other);
            }
        });
    }

    private static void guarded(@Nonnull String what, @Nonnull Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Procs.failed("a proc's " + what, t);
        }
    }
}
