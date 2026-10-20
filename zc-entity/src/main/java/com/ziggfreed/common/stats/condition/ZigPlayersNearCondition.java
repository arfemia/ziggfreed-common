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
 * An entity condition: how many other players are near it? The game has no party or team, so every other living player
 * within {@code Radius} blocks counts as an ally, meeting every one of {@code Conditions} (each asked of that player:
 * one under half health). It holds while the count is at least {@code Min} and, when written, at most {@code Max}.
 * Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigPlayersNear", "Radius": 8 }
 * { "Id": "ZigPlayersNear", "Radius": 8,
 *   "Conditions": [ { "Id": "ZigStatPercent", "Stat": "Health", "Below": 50 } ] }
 * }</pre>
 */
public final class ZigPlayersNearCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigPlayersNear";

    /** The radius in blocks unless written. */
    public static final double DEFAULT_RADIUS = 8.0;

    @Nullable
    private Double radius;
    @Nullable
    private Integer min;
    @Nullable
    private Integer max;
    @Nullable
    private Condition[] conditions;

    private static final class Holder {
        static final BuilderCodec<ZigPlayersNearCondition> CODEC = BuilderCodec.builder(
                        ZigPlayersNearCondition.class, ZigPlayersNearCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the number of other players near the entity is inside a range. With no "
                        + "party in the game, every other living player counts.")
                .append(new KeyedCodec<>("Radius", Codec.DOUBLE, false), (c, v) -> c.radius = v, c -> c.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, a player counts. 8 when unauthored.")
                .add()
                .append(new KeyedCodec<>("Min", Codec.INTEGER, false), (c, v) -> c.min = v, c -> c.min)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("The fewest players for it to hold. 1 when unauthored.")
                .add()
                .append(new KeyedCodec<>("Max", Codec.INTEGER, false), (c, v) -> c.max = v, c -> c.max)
                .documentation("The most players for it to hold. No limit when unauthored.")
                .add()
                .append(new KeyedCodec<>("Conditions", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                        (c, v) -> c.conditions = v, c -> c.conditions)
                .documentation("Entity conditions each player must meet to count, asked of that player. Every "
                        + "player counts when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigPlayersNearCondition> codec() {
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
        int stopAt = highest == null ? lowest : highest + 1;
        if (stopAt <= 0) {
            return lowest <= 0;
        }
        int count = Nearby.players(componentAccessor, ref, radius(), conditions, currentTime, stopAt);
        return ZigFoesNearCondition.inRange(count, lowest, highest);
    }
}
