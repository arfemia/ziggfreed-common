package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A critical hit landed: the library's crit step ({@link CritSystem}) rolled {@code attacker}'s critical chance on
 * {@code damage}, dealt to {@code target}, and multiplied it by {@link #multiplier}. Any attacker, a creature as much
 * as a player (R860). Fired once per critical hit, after the hit's amount took the multiplier, so a listener reads the
 * hit as it now stands (zc-track's Crit procs, a consumer's sound or notice).
 *
 * <p>Dispatched synchronously on the world thread, inside the engine's Filter damage group: a listener reads freely
 * and writes only through {@link #commandBuffer} (never adds a component to an entity there), and never blocks.
 */
public final class ZigCritEvent implements IEvent<Void> {

    @Nonnull private final Store<EntityStore> store;
    @Nonnull private final CommandBuffer<EntityStore> commandBuffer;
    @Nonnull private final Ref<EntityStore> attacker;
    @Nonnull private final Ref<EntityStore> target;
    @Nonnull private final Damage damage;
    private final double multiplier;

    public ZigCritEvent(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> attacker, @Nonnull Ref<EntityStore> target, @Nonnull Damage damage,
            double multiplier) {
        this.store = store;
        this.commandBuffer = commandBuffer;
        this.attacker = attacker;
        this.target = target;
        this.damage = damage;
        this.multiplier = multiplier;
    }

    /** The store both entities live in. */
    @Nonnull
    public Store<EntityStore> store() {
        return store;
    }

    /** The damage step's command buffer: every write a listener makes goes through it. */
    @Nonnull
    public CommandBuffer<EntityStore> commandBuffer() {
        return commandBuffer;
    }

    /** Who dealt the critical hit (a projectile's shooter). */
    @Nonnull
    public Ref<EntityStore> attacker() {
        return attacker;
    }

    /** Who took it. */
    @Nonnull
    public Ref<EntityStore> target() {
        return target;
    }

    /** The hit, its amount already multiplied. */
    @Nonnull
    public Damage damage() {
        return damage;
    }

    /** What the hit was multiplied by: one plus the attacker's crit damage, never below one. */
    public double multiplier() {
        return multiplier;
    }
}
