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

/**
 * An entity condition: does the entity carry an effect of a family ({@link EffectFamilyAsset}: Burning, Poisoned,
 * Controlled)? The engine's own {@code HasEffect} names one effect; a family names every burn at once, a mod's own
 * included through its {@code ZigEffectFamily} tag. Registered into the engine's entity condition map as
 * {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigEffectFamily", "Family": "Burning" }
 * }</pre>
 */
public final class ZigEffectFamilyCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigEffectFamily";

    @Nullable
    private String family;

    private static final class Holder {
        static final BuilderCodec<ZigEffectFamilyCondition> CODEC = BuilderCodec.builder(
                        ZigEffectFamilyCondition.class, ZigEffectFamilyCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity carries an effect of the family (an EffectFamilies file).")
                .append(new KeyedCodec<>("Family", Codec.STRING, false), (c, v) -> c.family = v, c -> c.family)
                .documentation("The family's id: an EffectFamilies file name (Burning, Poisoned, Slowed, "
                        + "Controlled). An unknown family never holds.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigEffectFamilyCondition> codec() {
        return Holder.CODEC;
    }

    /** The family as written, trimmed, or null. */
    @Nullable
    public String family() {
        return family == null || family.isBlank() ? null : family.trim();
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return EffectFamilies.carries(componentAccessor, ref, family());
    }
}
