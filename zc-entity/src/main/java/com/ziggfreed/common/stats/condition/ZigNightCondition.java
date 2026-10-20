package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * An entity condition: is it night where the entity is? Read off its world's own sunlight
 * ({@code WorldTimeResource.getSunlightFactor}, 1 at midday and 0 through the night), registered into the engine's
 * entity condition map as {@value #TYPE_NAME} (R700). {@code Inverse} makes it day.
 *
 * <pre>{@code
 * { "Id": "ZigNight" }
 * }</pre>
 */
public final class ZigNightCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigNight";

    /** The sunlight below which it counts as night, unless the condition says otherwise. */
    public static final double DEFAULT_SUNLIGHT_BELOW = 0.05;

    @Nullable
    private Double sunlightBelow;

    private static final class Holder {
        static final BuilderCodec<ZigNightCondition> CODEC = BuilderCodec.builder(
                        ZigNightCondition.class, ZigNightCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while it is night in the entity's world.")
                .append(new KeyedCodec<>("SunlightBelow", Codec.DOUBLE, false),
                        (c, v) -> c.sunlightBelow = v, c -> c.sunlightBelow)
                .metadata(EditorSchema.defaultValue(DEFAULT_SUNLIGHT_BELOW))
                .documentation("It is night while the world's sunlight (1 at midday, 0 through the night) is below "
                        + "this. 0.05 when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigNightCondition> codec() {
        return Holder.CODEC;
    }

    /** The threshold, held to [0, 1]. */
    public double sunlightBelow() {
        return sunlightBelow == null || !Double.isFinite(sunlightBelow) ? DEFAULT_SUNLIGHT_BELOW
                : Math.max(0.0, Math.min(1.0, sunlightBelow));
    }

    /** Is a sunlight reading night under this threshold? Pure. */
    static boolean isNight(double sunlight, double below) {
        return Double.isFinite(sunlight) && sunlight < below;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        try {
            WorldTimeResource time = componentAccessor.getResource(WorldTimeResource.getResourceType());
            return time != null && isNight(time.getSunlightFactor(), sunlightBelow());
        } catch (Throwable t) {
            return false;
        }
    }
}
