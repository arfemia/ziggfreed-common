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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * An entity condition: is the entity outnumbered? Its side is itself and its allies within {@code Radius} blocks (zc-core's
 * one ally rule: the party, else other players while the world's PvP is off); the other side is the enemy players there
 * (the players it may harm: PvP on, not its allies) when {@code Players}, and the creatures hostile to it or locked on to
 * it when {@code Creatures}. It holds while the other side outnumbers its own by at least {@code By}. Registered into the
 * engine's entity condition map as {@value #TYPE_NAME} (R700), and read from the bound grants' spatial memo like
 * {@code ZigFoesNear}, so a grant bound {@code While} it costs one walk a few times a second.
 *
 * <pre>{@code
 * { "Id": "ZigOutnumbered", "Radius": 16 }                    // more enemy players than you and your party
 * { "Id": "ZigOutnumbered", "Radius": 12, "By": 2, "Creatures": true }
 * }</pre>
 */
public final class ZigOutnumberedCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigOutnumbered";

    /** The radius in blocks unless written. */
    public static final double DEFAULT_RADIUS = 16.0;

    @Nullable
    private Double radius;
    @Nullable
    private Integer by;
    @Nullable
    private Boolean players;
    @Nullable
    private Boolean creatures;

    private static final class Holder {
        static final BuilderCodec<ZigOutnumberedCondition> CODEC = BuilderCodec.builder(
                        ZigOutnumberedCondition.class, ZigOutnumberedCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity's foes near it outnumber the entity and its allies near it. "
                        + "Its foes are the enemy players it may harm and, when Creatures is on, the creatures "
                        + "hostile to it.")
                .append(new KeyedCodec<>("Radius", Codec.DOUBLE, false), (c, v) -> c.radius = v, c -> c.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, either side counts. 16 when unauthored.")
                .add()
                .append(new KeyedCodec<>("By", Codec.INTEGER, false), (c, v) -> c.by = v, c -> c.by)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many more foes than the entity's own side it takes: 1 holds at any lead, 2 at "
                        + "two or more. 1 when unauthored.")
                .add()
                .append(new KeyedCodec<>("Players", Codec.BOOLEAN, false), (c, v) -> c.players = v, c -> c.players)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether enemy players count as foes: the players it may harm, which with PvP off is "
                        + "nobody. True when unauthored.")
                .add()
                .append(new KeyedCodec<>("Creatures", Codec.BOOLEAN, false),
                        (c, v) -> c.creatures = v, c -> c.creatures)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether creatures hostile to it or locked on to it count as foes. False when "
                        + "unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigOutnumberedCondition> codec() {
        return Holder.CODEC;
    }

    /** The radius, 0 or more. */
    public double radius() {
        return radius == null || !Double.isFinite(radius) ? DEFAULT_RADIUS : Math.max(0.0, radius);
    }

    /** The lead it takes, at least 1. */
    public int by() {
        return by == null ? 1 : Math.max(1, by);
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        boolean countPlayers = players == null || players;
        boolean countCreatures = Boolean.TRUE.equals(creatures);
        if (!countPlayers && !countCreatures) {
            return false;
        }
        double r = radius();
        int side = 1 + Nearby.players(componentAccessor, ref, r, null, currentTime, 0);
        int needed = side + by();
        int foes = countPlayers ? Nearby.enemyPlayers(componentAccessor, ref, r, null, currentTime, needed) : 0;
        if (countCreatures && foes < needed) {
            foes += Nearby.foes(componentAccessor, ref, r, null, currentTime, needed - foes);
        }
        return outnumbered(foes, side, by());
    }

    /** Does {@code foes} outnumber {@code side} by at least {@code by}? Pure. */
    static boolean outnumbered(int foes, int side, int by) {
        return foes - side >= Math.max(1, by);
    }
}
