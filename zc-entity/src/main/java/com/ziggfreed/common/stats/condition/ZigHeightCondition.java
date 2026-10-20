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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition: is the entity's feet inside a band of world height ({@code Above} and {@code Below}, block
 * heights, either optional)? Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigHeight", "Above": 120 }   // high on a mountain
 * { "Id": "ZigHeight", "Below": 40 }    // deep down
 * }</pre>
 */
public final class ZigHeightCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigHeight";

    @Nullable
    private Double above;
    @Nullable
    private Double below;

    private static final class Holder {
        static final BuilderCodec<ZigHeightCondition> CODEC = BuilderCodec.builder(
                        ZigHeightCondition.class, ZigHeightCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity's feet are inside a band of world height.")
                .append(new KeyedCodec<>("Above", Codec.DOUBLE, false), (c, v) -> c.above = v, c -> c.above)
                .documentation("Holds only while the feet are above this height. No limit when unauthored.")
                .add()
                .append(new KeyedCodec<>("Below", Codec.DOUBLE, false), (c, v) -> c.below = v, c -> c.below)
                .documentation("Holds only while the feet are below this height. No limit when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigHeightCondition> codec() {
        return Holder.CODEC;
    }

    /** Is {@code y} inside the band (null: no limit)? Pure. */
    static boolean inBand(double y, @Nullable Double above, @Nullable Double below) {
        return Double.isFinite(y) && (above == null || y > above) && (below == null || y < below);
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        Vector3d at = Nearby.positionOf(componentAccessor, ref);
        return at != null && inBand(at.y, above, below);
    }
}
