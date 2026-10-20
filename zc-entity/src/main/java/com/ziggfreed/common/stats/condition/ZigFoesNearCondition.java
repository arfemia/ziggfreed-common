package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * An entity condition: how many foes are near it? A foe is a living creature hostile to the entity or locked on to it
 * ({@link Nearby#foes}), within {@code Radius} blocks, meeting every one of {@code Conditions} (each asked of the foe:
 * a burning foe, a slowed one). It holds while the count is at least {@code Min} and, when written, at most
 * {@code Max}. Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigFoesNear", "Radius": 6, "Min": 3 }             // three or more foes within 6
 * { "Id": "ZigFoesNear", "Radius": 10, "Min": 1, "Max": 1 }  // one foe alone within 10
 * { "Id": "ZigFoesNear", "Radius": 4, "Min": 0, "Max": 0 }   // no foe within 4
 * }</pre>
 */
public final class ZigFoesNearCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigFoesNear";

    /** The radius in blocks unless written. */
    public static final double DEFAULT_RADIUS = 6.0;

    @Nullable
    private Double radius;
    @Nullable
    private Integer min;
    @Nullable
    private Integer max;
    @Nullable
    private Condition[] conditions;

    private static final class Holder {
        static final BuilderCodec<ZigFoesNearCondition> CODEC = BuilderCodec.builder(
                        ZigFoesNearCondition.class, ZigFoesNearCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the number of foes near the entity is inside a range. A foe is a living "
                        + "creature hostile to the entity or locked on to it.")
                .append(new KeyedCodec<>("Radius", Codec.DOUBLE, false), (c, v) -> c.radius = v, c -> c.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, a foe counts. 6 when unauthored.")
                .add()
                .append(new KeyedCodec<>("Min", Codec.INTEGER, false), (c, v) -> c.min = v, c -> c.min)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("The fewest foes for it to hold. 1 when unauthored; 0 with Max 0 means no foe near.")
                .add()
                .append(new KeyedCodec<>("Max", Codec.INTEGER, false), (c, v) -> c.max = v, c -> c.max)
                .documentation("The most foes for it to hold. No limit when unauthored.")
                .add()
                .append(new KeyedCodec<>("Conditions", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                        (c, v) -> c.conditions = v, c -> c.conditions)
                .documentation("Entity conditions each foe must meet to count, asked of the foe (a burning foe: "
                        + "ZigEffectFamily Burning). Every foe counts when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigFoesNearCondition> codec() {
        return Holder.CODEC;
    }

    /** The radius, 0 or more. */
    public double radius() {
        return radius == null || !Double.isFinite(radius) ? DEFAULT_RADIUS : Math.max(0.0, radius);
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        int lowest = min == null ? 1 : Math.max(0, min);
        Integer highest = max == null ? null : Math.max(0, max);
        // Count no further than the answer needs: one past Max, or Min when there is no Max.
        int stopAt = highest == null ? lowest : highest + 1;
        if (stopAt <= 0) {
            return lowest <= 0;
        }
        int count = Nearby.foes(componentAccessor, ref, radius(), conditions, currentTime, stopAt);
        return inRange(count, lowest, highest);
    }

    /** Is {@code count} at least {@code lowest} and, when {@code highest} is not null, at most it? Pure. */
    static boolean inRange(int count, int lowest, @Nullable Integer highest) {
        return count >= lowest && (highest == null || count <= highest);
    }
}
