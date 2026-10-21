package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One moment a proc may answer ({@link Procs#fire}): what happened, to which player (the holder), about whom (the
 * target: the foe hit, the attacker of a hit taken, the creature killed, the owner of an effect that landed, the ally
 * a coming hit is about, the one a heal healed; null when the moment is about no one), the hit when it carries one,
 * its amount (the damage dealt or taken, the blocks fallen, the seconds crouched, the health a heal restores), the
 * block a break was about, what names the move behind it: the RootInteraction a cast's chain started from
 * ({@code root}) and the ability a consumer names for its own cast ({@code ability}), the effect an effect moment is
 * about ({@code effect}), and what the moment costs the holder ({@code cost}: on Blocked, the stamina their guard is
 * about to pay for the block; 0 on every other moment). World thread; the command buffer is the producing system's,
 * null outside one.
 */
public record ProcMoment(@Nonnull ProcTrigger trigger, @Nonnull Store<EntityStore> store,
        @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> holder,
        @Nullable Ref<EntityStore> target, @Nullable Damage damage, double amount, @Nullable String blockId,
        @Nullable String root, @Nullable String ability, @Nullable EffectSeen effect, double cost) {

    /** The effect an EffectLanded or EffectEnded moment is about: its id, and whether it is on a foe. */
    public record EffectSeen(@Nonnull String id, boolean onFoe) {
    }

    /** A moment that costs the holder nothing. */
    public ProcMoment(@Nonnull ProcTrigger trigger, @Nonnull Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> holder,
            @Nullable Ref<EntityStore> target, @Nullable Damage damage, double amount, @Nullable String blockId,
            @Nullable String root, @Nullable String ability, @Nullable EffectSeen effect) {
        this(trigger, store, commandBuffer, holder, target, damage, amount, blockId, root, ability, effect, 0.0);
    }

    /** A moment that names no move and no effect, and costs the holder nothing. */
    public ProcMoment(@Nonnull ProcTrigger trigger, @Nonnull Store<EntityStore> store,
            @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> holder,
            @Nullable Ref<EntityStore> target, @Nullable Damage damage, double amount, @Nullable String blockId) {
        this(trigger, store, commandBuffer, holder, target, damage, amount, blockId, null, null, null, 0.0);
    }

    /** Where components are read and written: the system's command buffer when there is one, else the store. */
    @Nonnull
    public ComponentAccessor<EntityStore> accessor() {
        return commandBuffer != null ? commandBuffer : store;
    }

    /**
     * Who a coming hit lands on: the ally an AllyHitIncoming is about (its target), else the holder. The one a
     * {@code Soak} protects and {@code LeavesUnder} reads.
     */
    @Nullable
    Ref<EntityStore> struck() {
        return trigger == ProcTrigger.ALLY_HIT_INCOMING ? target : holder;
    }

    /** The same moment about {@code other} (the target a proc sought, or one foe of an area). */
    @Nonnull
    ProcMoment about(@Nullable Ref<EntityStore> other) {
        return new ProcMoment(trigger, store, commandBuffer, holder, other, damage, amount, blockId, root, ability,
                effect, cost);
    }
}
