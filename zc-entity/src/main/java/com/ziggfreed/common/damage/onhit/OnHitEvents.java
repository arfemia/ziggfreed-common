package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Fires the on-hit family's native events ({@link ZigCritEvent}, {@link ZigLifestealEvent}) on the shared engine bus,
 * under the library-wide contract ({@link NativeEventSeam}): built only when somebody listens, dispatched synchronously
 * inside the damage step that decided it, and never able to take that step down. A third party listens on the bus; a
 * host without a bus (a harness, a unit JVM) redirects the family through {@link #publishTo}.
 */
public final class OnHitEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[onhit]");

    private OnHitEvents() {
    }

    /** Route every fire through {@code publisher} instead of the engine bus; null restores the bus. */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /** A critical hit landed: {@code attacker}'s {@code damage} on {@code target}, multiplied by {@code multiplier}. */
    static void fireCrit(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> attacker, @Nonnull Ref<EntityStore> target, @Nonnull Damage damage,
            double multiplier) {
        if (SEAM.listening(ZigCritEvent.class)) {
            SEAM.fire("ZigCrit", ZigCritEvent.class,
                    () -> new ZigCritEvent(store, commandBuffer, attacker, target, damage, multiplier));
        }
    }

    /** A lifesteal healed {@code attacker} by {@code healed} off its {@code damage} on {@code target}. */
    static void fireLifesteal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull Damage damage, float healed) {
        if (SEAM.listening(ZigLifestealEvent.class)) {
            SEAM.fire("ZigLifesteal", ZigLifestealEvent.class,
                    () -> new ZigLifestealEvent(store, attacker, target, damage, healed));
        }
    }
}
