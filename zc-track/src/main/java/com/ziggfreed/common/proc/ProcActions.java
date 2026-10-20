package com.ziggfreed.common.proc;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.EffectAdds;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.interaction.NativeChainFire;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.EffectFamilies;

/**
 * What a proc does ({@link ProcAction}), each through the engine's own call: an effect put on with the holder as its
 * owner (so its damage and a kill it lands credit them) and this application's damage multiplier; effects taken off
 * by id or effect family; a stat's current value moved (the engine holds it between its bounds); a counter changed; a
 * RootInteraction queued on the holder or the target as a server chain. World thread; each action is guarded on its
 * own, so one that fails costs only itself.
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
            if (action.removeEffect() != null) {
                guarded("RemoveEffect", () -> removeEffect(moment, action.removeEffect()));
            }
            if (action.applyEffect() != null) {
                guarded("ApplyEffect", () -> applyEffect(moment, action.applyEffect(), strength, context));
            }
            if (action.changeStat() != null) {
                guarded("ChangeStat", () -> changeStat(moment, action.changeStat(), strength, context));
            }
            if (action.counter() != null) {
                guarded("Counter", () -> counter(moment, action.counter(), strength, context));
            }
            if (action.runInteraction() != null) {
                guarded("RunInteraction", () -> runInteraction(moment, action.runInteraction()));
            }
        }
    }

    private static void applyEffect(@Nonnull ProcMoment moment, @Nonnull ProcAction.ApplyEffect apply, double strength,
            @Nonnull FactorContext context) {
        Ref<EntityStore> on = apply.onSelf() ? moment.holder() : moment.target();
        String id = apply.effect();
        if (on == null || !on.isValid() || id == null) {
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
            active.setDamageMultiplier((float) Math.max(0.0, multiplier * strength));
            ProcMarks.markApplied(active);
        }
    }

    private static void removeEffect(@Nonnull ProcMoment moment, @Nonnull ProcAction.RemoveEffect remove) {
        Ref<EntityStore> on = remove.onSelf() ? moment.holder() : moment.target();
        if (on == null || !on.isValid()) {
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

    private static void changeStat(@Nonnull ProcMoment moment, @Nonnull ProcAction.ChangeStat change, double strength,
            @Nonnull FactorContext context) {
        Ref<EntityStore> on = change.onSelf() ? moment.holder() : moment.target();
        String stat = change.stat();
        if (on == null || !on.isValid() || stat == null || change.amount() == null) {
            return;
        }
        int index = StatIndexCache.resolve(stat);
        EntityStatMap stats = index == StatIndexCache.UNRESOLVED ? null
                : moment.accessor().getComponent(on, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        double amount = Procs.evaluate(change.amount(), context) * strength;
        if (amount != 0.0 && Double.isFinite(amount)) {
            stats.addStatValue(index, (float) amount);
        }
    }

    private static void counter(@Nonnull ProcMoment moment, @Nonnull ProcAction.CounterChange change, double strength,
            @Nonnull FactorContext context) {
        CounterConfig.Def def = CounterConfig.getInstance().live(change.of());
        if (def == null) {
            return;
        }
        double add = change.add() == null ? 0.0 : Procs.evaluate(change.add(), context) * strength;
        if (change.byAmount()) {
            add *= moment.amount();
        }
        double consume = change.consume() == null ? 0.0 : Procs.evaluate(change.consume(), context);
        Counters.change(moment, def, add, consume, change.clear(), context);
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
