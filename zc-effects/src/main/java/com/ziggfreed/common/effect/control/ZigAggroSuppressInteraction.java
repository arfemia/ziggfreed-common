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
 * Makes the creatures around an entity ignore it for a while, through {@link AggroControl#suppress}:
 *
 * <pre>{@code
 * { "Type": "ZigAggroSuppress", "Radius": 12, "Duration": 3 }
 * }</pre>
 *
 * <p>Every creature in reach stops targeting the entity, forgets it and ignores it until the window ends, wherever
 * the entity goes. Always resolves Finished.
 */
public final class ZigAggroSuppressInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigAggroSuppress";

    static final double DEFAULT_RADIUS = 12.0;
    static final double DEFAULT_DURATION = 3.0;

    @Nullable
    protected InteractionTarget entity;
    @Nullable
    protected Double radius;
    @Nullable
    protected Double duration;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigAggroSuppressInteraction> CODEC = BuilderCodec.builder(
                        ZigAggroSuppressInteraction.class, ZigAggroSuppressInteraction::new,
                        SimpleInstantInteraction.CODEC)
                .documentation("Suppresses aggro: every creature near the entity stops targeting it, forgets it and"
                        + " ignores it for the duration, wherever the entity goes.")
                .appendInherited(new KeyedCodec<>("Entity", InteractionTarget.CODEC),
                        (i, v) -> i.entity = v, i -> i.entity, (i, p) -> i.entity = p.entity)
                .documentation("Who is ignored: Owner (the default, the entity running this chain), User or Target.")
                .add()
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE),
                        (i, v) -> i.radius = v, i -> i.radius, (i, p) -> i.radius = p.radius)
                .documentation("How far it reaches, in blocks around the entity. 12 when unauthored; 0 or less"
                        + " reaches the whole world.")
                .add()
                .appendInherited(new KeyedCodec<>("Duration", Codec.DOUBLE),
                        (i, v) -> i.duration = v, i -> i.duration, (i, p) -> i.duration = p.duration)
                .documentation("How long creatures ignore the entity, in seconds. 3 when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and the codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigAggroSuppressInteraction> getCODEC() {
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

    /** Only the server suppresses aggro. */
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
            Ref<EntityStore> source = ControlTargets.resolve(ctx, entity, InteractionTarget.OWNER);
            if (source != null) {
                AggroControl.suppress(buffer, source, (float) duration(), radius());
            }
            return true;
        });
    }
}
