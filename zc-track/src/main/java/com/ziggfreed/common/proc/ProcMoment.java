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
 * target: the foe hit, the attacker of a hit taken, the creature killed; null when the moment is about no one), the
 * hit when it carries one, its amount (the damage dealt or taken, the blocks fallen, the seconds crouched), and the
 * block a break was about. World thread; the command buffer is the producing system's, null outside one.
 */
public record ProcMoment(@Nonnull ProcTrigger trigger, @Nonnull Store<EntityStore> store,
        @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> holder,
        @Nullable Ref<EntityStore> target, @Nullable Damage damage, double amount, @Nullable String blockId) {

    /** Where components are read and written: the system's command buffer when there is one, else the store. */
    @Nonnull
    public ComponentAccessor<EntityStore> accessor() {
        return commandBuffer != null ? commandBuffer : store;
    }

    /** The same moment about {@code other} (the target a proc sought). */
    @Nonnull
    ProcMoment about(@Nullable Ref<EntityStore> other) {
        return new ProcMoment(trigger, store, commandBuffer, holder, other, damage, amount, blockId);
    }
}
