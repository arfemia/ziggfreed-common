package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * An entity condition: is the entity moving across the ground (its horizontal speed, in blocks a second, at least
 * {@code MinSpeed})? {@code Inverse} makes it "standing still". A player's speed is the one their client last reported
 * ({@code Velocity.getClientVelocity}), a creature's the server's own, so it suits a bonus, never a gate on anything
 * else. Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigMoving", "Inverse": true }     // standing still
 * }</pre>
 */
public final class ZigMovingCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigMoving";

    /** The horizontal speed, blocks a second, from which an entity counts as moving unless written. */
    public static final double DEFAULT_MIN_SPEED = 0.5;

    @Nullable
    private Double minSpeed;

    private static final class Holder {
        static final BuilderCodec<ZigMovingCondition> CODEC = BuilderCodec.builder(
                        ZigMovingCondition.class, ZigMovingCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity moves across the ground. With Inverse, while it stands "
                        + "still. A player's speed is the one their client reports.")
                .append(new KeyedCodec<>("MinSpeed", Codec.DOUBLE, false), (c, v) -> c.minSpeed = v, c -> c.minSpeed)
                .metadata(EditorSchema.defaultValue(DEFAULT_MIN_SPEED))
                .documentation("The horizontal speed, in blocks a second, from which the entity counts as moving. "
                        + "0.5 when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigMovingCondition> codec() {
        return Holder.CODEC;
    }

    /** The threshold, 0 or more. */
    public double minSpeed() {
        return minSpeed == null || !Double.isFinite(minSpeed) ? DEFAULT_MIN_SPEED : Math.max(0.0, minSpeed);
    }

    /** The horizontal length of a velocity. Pure. */
    static double horizontal(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        try {
            Velocity velocity = componentAccessor.getComponent(ref, Velocity.getComponentType());
            if (velocity == null) {
                return false;
            }
            boolean player = componentAccessor.getComponent(ref, PlayerRef.getComponentType()) != null;
            Vector3d v = player ? velocity.getClientVelocity() : velocity.getVelocity();
            return horizontal(v.x, v.z) >= minSpeed();
        } catch (Throwable t) {
            return false;
        }
    }
}
