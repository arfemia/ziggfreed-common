package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A lifesteal healed its attacker: the library's lifesteal step ({@link LifestealSystem}) gave {@code attacker}
 * {@link #healed} health back off {@code damage}, the hit it landed on {@code target}. Any attacker, a creature as much
 * as a player (R860). Fired once per hit that healed, after the health moved (a consumer's notice of it).
 *
 * <p>Dispatched synchronously on the world thread, inside the engine's Inspect damage group, once the hit is applied:
 * a listener reads, and never blocks.
 */
public final class ZigLifestealEvent implements IEvent<Void> {

    @Nonnull private final Store<EntityStore> store;
    @Nonnull private final Ref<EntityStore> attacker;
    @Nonnull private final Ref<EntityStore> target;
    @Nonnull private final Damage damage;
    private final float healed;

    public ZigLifestealEvent(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull Damage damage, float healed) {
        this.store = store;
        this.attacker = attacker;
        this.target = target;
        this.damage = damage;
        this.healed = healed;
    }

    /** The store both entities live in. */
    @Nonnull
    public Store<EntityStore> store() {
        return store;
    }

    /** Who was healed: the hit's dealer (a projectile's shooter). */
    @Nonnull
    public Ref<EntityStore> attacker() {
        return attacker;
    }

    /** Whom the hit landed on. */
    @Nonnull
    public Ref<EntityStore> target() {
        return target;
    }

    /** The hit, as it was applied. */
    @Nonnull
    public Damage damage() {
        return damage;
    }

    /** The health the lifesteal gave back: above 0, never more than the room the attacker had. */
    public float healed() {
        return healed;
    }
}
