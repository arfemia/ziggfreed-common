package com.ziggfreed.common.health;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A heal someone gives is landing: {@link HealthUtil#heal(Store, Ref, Ref, float)} was asked to heal {@code target}
 * with {@code healer} as its giver, and the target had room for it. Fired once per heal, as it lands and before the
 * health moves, so a listener still reads the target's health as the heal found it; {@code amount} is what the heal
 * restores (the asked amount raised by the healer's healing power, held to the room the target had). A heal with no
 * giver (a drain, a regeneration, a full restore) fires none, nor does one on a target already full. The healer may be
 * the target (a self-heal).
 *
 * <p>Dispatched synchronously on the world thread that healed, inside whatever system or interaction asked for the
 * heal, so a listener reads and writes only that world's entities and never blocks.
 */
public final class ZigHealGivenEvent implements IEvent<Void> {

    @Nonnull private final Store<EntityStore> store;
    @Nonnull private final Ref<EntityStore> healer;
    @Nonnull private final Ref<EntityStore> target;
    private final float amount;

    public ZigHealGivenEvent(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> healer,
            @Nonnull Ref<EntityStore> target, float amount) {
        this.store = store;
        this.healer = healer;
        this.target = target;
        this.amount = amount;
    }

    /** The store both entities live in, the world the heal lands in. */
    @Nonnull
    public Store<EntityStore> store() {
        return store;
    }

    /** Who gives the heal. */
    @Nonnull
    public Ref<EntityStore> healer() {
        return healer;
    }

    /** Who is healed (the healer, on a self-heal). */
    @Nonnull
    public Ref<EntityStore> target() {
        return target;
    }

    /** The health the heal restores: above 0, never more than the room the target had. */
    public float amount() {
        return amount;
    }

    /** Is this a heal the healer gives themselves? */
    public boolean selfHeal() {
        return healer == target
                || (healer.getIndex() == target.getIndex() && healer.getStore() == target.getStore());
    }
}
