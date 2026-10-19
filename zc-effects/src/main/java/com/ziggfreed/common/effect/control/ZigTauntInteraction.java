package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionTarget;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;

/**
 * Pulls the creatures around an entity onto it for a while, through {@link AggroControl#taunt}:
 *
 * <pre>{@code
 * { "Type": "ZigTaunt", "Radius": 12, "Duration": 5 }
 * }</pre>
 *
 * <p>Every creature in reach that is not friendly toward the taunter turns hostile toward it, targets it and keeps
 * going after it until the taunt ends. Always resolves Finished.
 */
public final class ZigTauntInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigTaunt";

    static final double DEFAULT_RADIUS = 12.0;
    static final double DEFAULT_DURATION = 5.0;

    @Nullable
    protected InteractionTarget entity;
    @Nullable
    protected Double radius;
    @Nullable
    protected Double duration;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigTauntInteraction> CODEC = BuilderCodec.builder(
                        ZigTauntInteraction.class, ZigTauntInteraction::new, SimpleInstantInteraction.CODEC)
                .documentation("Taunts: every creature near the taunter that is not friendly toward it turns on it and"
                        + " targets it for the duration, wherever the taunter goes.")
                .appendInherited(new KeyedCodec<>("Entity", InteractionTarget.CODEC),
                        (i, v) -> i.entity = v, i -> i.entity, (i, p) -> i.entity = p.entity)
                .documentation("Who taunts: Owner (the default, the entity running this chain), User or Target.")
                .add()
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE),
                        (i, v) -> i.radius = v, i -> i.radius, (i, p) -> i.radius = p.radius)
                .documentation("How far the taunt reaches, in blocks around the taunter. 12 when unauthored; 0 or less"
                        + " reaches the whole world.")
                .add()
                .appendInherited(new KeyedCodec<>("Duration", Codec.DOUBLE),
                        (i, v) -> i.duration = v, i -> i.duration, (i, p) -> i.duration = p.duration)
                .documentation("How long the taunt holds, in seconds. 5 when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and the codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigTauntInteraction> getCODEC() {
        return Holder.CODEC;
    }

    @Nonnull
    InteractionTarget entity() {
        return entity != null ? entity : InteractionTarget.OWNER;
    }

    double radius() {
        return ControlTargets.orDefault(radius, DEFAULT_RADIUS);
    }

    double duration() {
        return ControlTargets.orDefault(duration, DEFAULT_DURATION);
    }

    /** Only the server taunts. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
            if (buffer == null) {
                return false;
            }
            Ref<EntityStore> taunter = ControlTargets.resolve(ctx, entity, InteractionTarget.OWNER);
            if (taunter != null) {
                AggroControl.taunt(buffer, taunter, (float) duration(), radius());
            }
            return true;
        });
    }
}
