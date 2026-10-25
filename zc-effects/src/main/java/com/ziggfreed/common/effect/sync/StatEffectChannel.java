package com.ziggfreed.common.effect.sync;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.grid.RuntimeEffectGrid;
import com.ziggfreed.common.util.SafeLog;

/**
 * One stat family carried to the engine through native effects (a per-type damage bonus, a resistance, speed and
 * knockback): what a carrier should wear of its {@link RuntimeEffectGrid} is worked out from its stats
 * ({@link #wanted}), compared with what it wears, and reconciled only on a difference. The comparison, the
 * reconcile, the one-time failure line, the consumer's {@link #sync} and the teardown are this class's; a family
 * writes only {@link #wanted}. {@link StatEffectSync} runs every registered channel over every carrier.
 *
 * <p>State lives on the entity: what it wears is read off its own {@code EffectControllerComponent}, never kept here,
 * so a death (the engine clears every effect), a world change or another mod's removal heals on the next pass.
 */
public abstract class StatEffectChannel {

    @Nonnull
    private final String logPrefix;
    @Nonnull
    private final String what;
    @Nonnull
    private final RuntimeEffectGrid grid;
    @Nonnull
    private final AtomicBoolean failureWarned = new AtomicBoolean();

    /**
     * @param logPrefix the family's log prefix ({@code [resist]})
     * @param what      what keeping it in step is, for its one failure line ("a carrier's damage resistance")
     * @param grid      the family's effect grid
     */
    protected StatEffectChannel(@Nonnull String logPrefix, @Nonnull String what, @Nonnull RuntimeEffectGrid grid) {
        this.logPrefix = logPrefix;
        this.what = what;
        this.grid = grid;
    }

    /** The family's effect grid. */
    @Nonnull
    public final RuntimeEffectGrid grid() {
        return grid;
    }

    /**
     * The buckets the carrier at {@code ref} should wear now, worked out from {@code stats} (and, for a family whose
     * room depends on it, what {@code ctrl} already wears): none at all while a consumer's rule says no. Empty when
     * every stat of the family reads 0.
     */
    @Nonnull
    protected abstract List<RuntimeEffectGrid.Bucket> wanted(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref, @Nonnull EntityStatMap stats, @Nonnull EffectControllerComponent ctrl);

    /**
     * The per-pass step: the wanted buckets, a look at the worn ones, and a reconcile only when the two differ. A
     * bucket still being written applies on a later pass. Never throws.
     */
    public final void keepInStep(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull EntityStatMap stats, @Nonnull EffectControllerComponent ctrl) {
        try {
            List<RuntimeEffectGrid.Bucket> wanted = wanted(accessor, ref, stats, ctrl);
            if (!grid.carriesExactly(ctrl, wanted)) {
                grid.reconcile(accessor, ref, wanted, null);
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /**
     * Bring {@code ref}'s effects of this family into line with its stats now: for a consumer that has just written
     * one of them and wants it counted before the next pass. Nothing for a dead or effect-less entity. World thread
     * only; idempotent; never throws.
     */
    public final void sync(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid() || accessor.getComponent(ref, DeathComponent.getComponentType()) != null) {
                return;
            }
            EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
            EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (stats != null && ctrl != null) {
                keepInStep(accessor, ref, stats, ctrl);
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    /** Take every effect of this family off the entity (a full teardown). World thread only; never throws. */
    public final void removeAll(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        if (ref != null) {
            grid.removeAll(accessor, ref);
        }
    }

    /** One line per process for a pass that throws, which would otherwise repeat on every pass. */
    private void warnOnce(@Nonnull Throwable t) {
        if (failureWarned.compareAndSet(false, true)) {
            SafeLog.warn(logPrefix + " keeping " + what + " in step failed (named once): " + t);
        } else {
            SafeLog.fine(logPrefix + " a pass failed: " + t.getMessage());
        }
    }
}
