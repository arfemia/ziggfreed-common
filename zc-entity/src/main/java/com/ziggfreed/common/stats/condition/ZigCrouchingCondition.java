package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition: is the entity crouching (its movement states' {@code crouching}, which a player's client
 * reports)? {@code Inverse} makes it "not crouching". Pair it with {@code ZigMoving} {@code Inverse} for "crouched
 * still". Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700), so an effect's
 * {@code ApplyConditions}, a conditional stat and a held effect may name it.
 *
 * <pre>{@code
 * { "Id": "ZigCrouching" }
 * }</pre>
 */
public final class ZigCrouchingCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigCrouching";

    private static final class Holder {
        static final BuilderCodec<ZigCrouchingCondition> CODEC = BuilderCodec.builder(
                        ZigCrouchingCondition.class, ZigCrouchingCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity crouches. With Inverse, while it does not. A player's "
                        + "crouch is the one their client reports.")
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigCrouchingCondition> codec() {
        return Holder.CODEC;
    }

    /** Is the entity at {@code ref} crouching? False when it cannot tell. Never throws. */
    public static boolean crouching(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            MovementStatesComponent states = accessor.getComponent(ref, MovementStatesComponent.getComponentType());
            MovementStates now = states == null ? null : states.getMovementStates();
            return now != null && now.crouching;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return crouching(componentAccessor, ref);
    }
}
