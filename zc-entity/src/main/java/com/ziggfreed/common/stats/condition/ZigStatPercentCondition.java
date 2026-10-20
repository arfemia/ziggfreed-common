package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.EntityStatBoundCondition;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition: is one of its stats inside a band of its range, in percent ({@code EntityStatValue.asPercentage},
 * 0 at the stat's minimum and 100 at its maximum)? The engine's own {@code Stat} condition compares a stat's value with
 * a fixed amount, so a health band ("under half health") is this one. Registered into the engine's entity condition
 * map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigStatPercent", "Stat": "Health", "Below": 50 }
 * { "Id": "ZigStatPercent", "Stat": "Mana", "Above": 80 }
 * }</pre>
 */
public final class ZigStatPercentCondition extends EntityStatBoundCondition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigStatPercent";

    @Nullable
    private Double below;
    @Nullable
    private Double above;

    private static final class Holder {
        static final BuilderCodec<ZigStatPercentCondition> CODEC = BuilderCodec.builder(
                        ZigStatPercentCondition.class, ZigStatPercentCondition::new, EntityStatBoundCondition.CODEC)
                .documentation("Holds while the stat is inside a band of its range, in percent of it (0 at its "
                        + "minimum, 100 at its maximum).")
                .append(new KeyedCodec<>("Below", Codec.DOUBLE, false), (c, v) -> c.below = v, c -> c.below)
                .documentation("Holds only while the stat is under this percent of its range (50 is under half). "
                        + "No limit when unauthored.")
                .add()
                .append(new KeyedCodec<>("Above", Codec.DOUBLE, false), (c, v) -> c.above = v, c -> c.above)
                .documentation("Holds only while the stat is over this percent of its range (80 is over four "
                        + "fifths). No limit when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigStatPercentCondition> codec() {
        return Holder.CODEC;
    }

    /** Is {@code percent} (0 to 100) inside the band {@code below} and {@code above} draw (null: no limit)? Pure. */
    static boolean inBand(double percent, @Nullable Double below, @Nullable Double above) {
        if (!Double.isFinite(percent)) {
            return false;
        }
        if (below != null && Double.isFinite(below) && !(percent < below)) {
            return false;
        }
        return above == null || !Double.isFinite(above) || percent > above;
    }

    @Override
    public boolean eval0(@Nonnull Ref<EntityStore> ref, @Nonnull Instant currentTime,
            @Nonnull EntityStatValue statValue) {
        return inBand(statValue.asPercentage() * 100.0, below, above);
    }
}
