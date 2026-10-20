package com.ziggfreed.common.entity;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * An entity condition: is the entity standing on the ground? Read off the engine's own movement states, which a
 * player's client reports and every NPC's motion controller keeps current each tick (the NPC module's
 * {@code MovementStatesSystem}). Registered into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * "ApplyConditions": [ { "Id": "ZigGrounded", "Inverse": true, "GraceSeconds": 0.3 } ]
 * }</pre>
 *
 * <p>With {@code Inverse} on an effect's {@code ApplyConditions}, the effect lasts while the entity is off the
 * ground and comes off when it lands ({@code Zig_Airborne}). {@code GraceSeconds} is for exactly that: an effect
 * put on with the hit that launches a foe is put on while the foe still stands, so for its first seconds the
 * entity does not count as grounded. The grace is read off the effect asking (the active effect whose asset holds
 * this condition), and with no such effect active yet (the engine asks once before putting it on) the entity does
 * not count as grounded either. An infinite effect has no age to read, so its grace counts as passed. Asked of
 * anything else (a stat's regeneration conditions), a grace is the same rule: it never counts as grounded there.
 */
public final class ZigGroundedCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigGrounded";

    @Nullable
    private Double graceSeconds;

    private static final class Holder {
        static final BuilderCodec<ZigGroundedCondition> CODEC = BuilderCodec.builder(
                        ZigGroundedCondition.class, ZigGroundedCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity stands on the ground. With Inverse on an effect's "
                        + "ApplyConditions, the effect lasts until the entity lands.")
                .append(new KeyedCodec<>("GraceSeconds", Codec.DOUBLE, false),
                        (c, v) -> c.graceSeconds = v, c -> c.graceSeconds)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("For this long after the effect asking was put on, the entity does not count as "
                        + "grounded, so an effect put on with the hit that launches a foe is not taken off before "
                        + "the foe leaves the ground. 0 when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigGroundedCondition> codec() {
        return Holder.CODEC;
    }

    /** The grace in seconds, 0 or more. */
    public double graceSeconds() {
        return graceSeconds == null || !Double.isFinite(graceSeconds) ? 0.0 : Math.max(0.0, graceSeconds);
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        MovementStatesComponent states = componentAccessor.getComponent(ref, MovementStatesComponent.getComponentType());
        if (states == null || states.getMovementStates() == null || !states.getMovementStates().onGround) {
            return false;
        }
        double grace = graceSeconds();
        return grace <= 0.0 || gracePassed(componentAccessor, ref, grace);
    }

    /** Has the active effect holding this condition run for {@code grace} seconds? False while none is active. */
    private boolean gracePassed(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            double grace) {
        EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ctrl == null) {
            return false;
        }
        for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
            EntityEffect effect = EntityEffect.getAssetMap().getAsset(active.getEntityEffectIndex());
            if (effect == null || !holdsThis(effect.getApplyConditions())) {
                continue;
            }
            if (active.isInfinite()) {
                return true;
            }
            return active.getInitialDuration() - active.getRemainingDuration() >= grace;
        }
        return false;
    }

    private boolean holdsThis(@Nullable Condition[] conditions) {
        if (conditions != null) {
            for (Condition condition : conditions) {
                if (condition == this) {
                    return true;
                }
            }
        }
        return false;
    }
}
