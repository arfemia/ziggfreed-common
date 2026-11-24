package com.ziggfreed.common.pvp;

import java.time.Instant;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.damage.pvp.PvpHits;
import com.ziggfreed.common.stats.condition.HitAwareCondition;

/**
 * A hit-aware entity condition: does this hit land on the player who last killed its holder, within {@code Seconds} of
 * that kill (a grudge)? Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700). It answers only
 * on a hit (zc-entity's {@link HitAwareCondition}: a grant bound {@code While} it pays on such hits alone through the
 * bound hit term, so {@code DamageBonus} bound {@code While} it is "deal more damage to the player who just beat you"
 * with no stat of its own); outside a hit, it never holds. Who killed whom is the PvP ledger's memory, which a
 * restart or the holder leaving forgets.
 *
 * <pre>{@code
 * "Grants": [ { "Kind": "Stat", "Stat": "DamageBonus", "Add": 5,
 *               "While": [ { "Id": "ZigGrudge", "Seconds": 300 } ] } ]
 * }</pre>
 */
public final class ZigGrudgeCondition extends Condition implements HitAwareCondition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigGrudge";

    /** How long a grudge lasts, unless written: five minutes. */
    public static final double DEFAULT_SECONDS = 300.0;

    @Nullable
    private Double seconds;

    private static final class Holder {
        static final BuilderCodec<ZigGrudgeCondition> CODEC = BuilderCodec.builder(
                        ZigGrudgeCondition.class, ZigGrudgeCondition::new, Condition.BASE_CODEC)
                .documentation("Holds on a hit that lands on the player who last killed the entity, within Seconds "
                        + "of that kill. Never holds outside a hit.")
                .append(new KeyedCodec<>("Seconds", Codec.DOUBLE, false), (c, v) -> c.seconds = v, c -> c.seconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_SECONDS))
                .documentation("How long after being killed the grudge lasts, in seconds. 300 when unauthored; at "
                        + "most half an hour counts.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigGrudgeCondition> codec() {
        return Holder.CODEC;
    }

    /** The grudge's length in milliseconds, 0 or more. */
    long windowMillis() {
        double read = seconds == null || !Double.isFinite(seconds) ? DEFAULT_SECONDS : seconds;
        return read <= 0.0 ? 0L : Math.round(read * 1000.0);
    }

    /** Outside a hit it never holds, its {@code Inverse} included: there is no one the hit lands on. */
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
        UUID target = PvpHits.victimOf(damage);
        UUID holder = PvpLedger.idOf(accessor, attacker);
        return inverse != PvpLedger.killedBy(holder, target, now.toEpochMilli(), windowMillis());
    }
}
