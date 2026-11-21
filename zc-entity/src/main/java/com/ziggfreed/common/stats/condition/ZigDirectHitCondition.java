package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition that holds only on a hit: a direct hit its holder deals (a swing, a shot, a spell's hit; any hit
 * but a damage-over-time tick, {@link HitReads#isOverTime}), and never outside a hit, whatever its {@code Inverse}
 * says. Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700). It is hit-aware
 * ({@link HitAwareCondition}), so a bound grant naming it never sits on its stat: the holder pass reads it as not
 * holding, and the bound hit term pays the grant on each direct hit where its other conditions hold (R1099: "your hits
 * while you sprint deal more" lifts a sprinting swing, never the bleed it leaves ticking). A proc whose {@code Holder}
 * names it fires only on a direct hit. {@code Inverse} turns the hit's answer around: a damage-over-time tick only.
 *
 * <pre>{@code
 * { "Kind": "Stat", "Stat": "DamageBonus", "Add": 5,
 *   "While": [ { "Id": "Sprinting" }, { "Id": "ZigDirectHit" } ] }
 * }</pre>
 */
public final class ZigDirectHitCondition extends Condition implements HitAwareCondition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigDirectHit";

    private static final class Holder {
        static final BuilderCodec<ZigDirectHitCondition> CODEC = BuilderCodec.builder(
                        ZigDirectHitCondition.class, ZigDirectHitCondition::new, Condition.BASE_CODEC)
                .documentation("Holds only on a direct hit the entity deals (any hit but a damage-over-time tick), "
                        + "never outside a hit: a bound grant naming it counts hit by hit and never sits on its stat. "
                        + "Inverse: a damage-over-time tick only.")
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigDirectHitCondition> codec() {
        return Holder.CODEC;
    }

    /**
     * Make the engine's entity conditions know this kind when nothing has registered it yet: a consumer's file read
     * before setup (a unit-test JVM, which never runs setup) still decodes. On a server the library's setup has
     * registered it first, so this does nothing. Idempotent.
     */
    public static void ensureKnown() {
        if (Condition.CODEC.getCodecFor(TYPE_NAME) == null) {
            Condition.CODEC.register(TYPE_NAME, ZigDirectHitCondition.class, codec());
        }
    }

    /** Outside a hit it never holds, its {@code Inverse} included: there is no hit to be direct. */
    @Override
    public boolean eval(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return false;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return false;
    }

    @Override
    public boolean evalOnHit(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull Instant now) {
        return inverse != isDirect(damage);
    }

    /** Is {@code damage} a direct hit, no damage-over-time tick? */
    static boolean isDirect(@Nonnull Damage damage) {
        return !HitReads.isOverTime(damage);
    }
}
