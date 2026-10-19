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
 * Makes the creatures around an entity lose track of it once, through {@link AggroControl#drop}:
 *
 * <pre>{@code
 * { "Type": "ZigAggroDrop", "Radius": 30 }
 * }</pre>
 *
 * <p>A creature still close enough to see the entity may find it again; {@code ZigAggroSuppress} holds them off for
 * a while instead. Always resolves Finished.
 */
public final class ZigAggroDropInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigAggroDrop";

    static final double DEFAULT_RADIUS = 12.0;

    @Nullable
    protected InteractionTarget entity;
    @Nullable
    protected Double radius;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigAggroDropInteraction> CODEC = BuilderCodec.builder(
                        ZigAggroDropInteraction.class, ZigAggroDropInteraction::new, SimpleInstantInteraction.CODEC)
                .documentation("Drops aggro: every creature near the entity stops targeting it and forgets it, once."
                        + " One still close enough to see it may come back.")
                .appendInherited(new KeyedCodec<>("Entity", InteractionTarget.CODEC),
                        (i, v) -> i.entity = v, i -> i.entity, (i, p) -> i.entity = p.entity)
                .documentation("Who is forgotten: Owner (the default, the entity running this chain), User or Target.")
                .add()
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE),
                        (i, v) -> i.radius = v, i -> i.radius, (i, p) -> i.radius = p.radius)
                .documentation("How far it reaches, in blocks around the entity. 12 when unauthored; 0 or less"
                        + " reaches the whole world.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and the codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigAggroDropInteraction> getCODEC() {
        return Holder.CODEC;
    }

    @Nonnull
    InteractionTarget entity() {
        return entity != null ? entity : InteractionTarget.OWNER;
    }

    double radius() {
        return ControlTargets.orDefault(radius, DEFAULT_RADIUS);
    }

    /** Only the server drops aggro. */
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
                AggroControl.drop(buffer, source, radius());
            }
            return true;
        });
    }
}
