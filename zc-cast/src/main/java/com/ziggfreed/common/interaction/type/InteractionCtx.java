package com.ziggfreed.common.interaction.type;

import java.util.UUID;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Null-safe, guarded accessors for the values a custom interaction Type reads out of an {@link
 * InteractionContext}: the firing entity, the chain owner, the Selector-forked target, the
 * command buffer, the held stack, position / eye position / look direction, the world, the
 * player, the entity uuid.
 *
 * <p><b>Owner vs firing entity (engine-verified).</b> A Selector's {@code HitEntity} fork does
 * {@code context.duplicate()} - which carries the ORIGINAL owning/running entity forward
 * unchanged - and only overwrites the meta-store's {@code TARGET_ENTITY} ({@code
 * SelectInteraction.java:331-336}). So inside a hit leaf, {@link #owner} is the CASTER and
 * {@link #target} is the swept entity. This is the same pair the engine's own {@code
 * DamageEntityInteraction} resolves.
 *
 * <p>Every accessor returns {@code null} rather than throwing: a null context, a missing
 * component, an invalid ref, or any engine throw all degrade to {@code null} plus a guarded
 * FINE. World-thread (component reads).
 */
public final class InteractionCtx {

    /** Default eye height for a humanoid caster, matching the MMO's teleport/dash sampling. */
    public static final double DEFAULT_EYE_HEIGHT = 1.6;

    private InteractionCtx() {
    }

    /** The live command buffer for this interaction tick, or null. */
    @Nullable
    public static CommandBuffer<EntityStore> buffer(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getCommandBuffer();
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read command buffer", t);
            return null;
        }
    }

    /** {@code ctx.getEntity()} when valid, else null. */
    @Nullable
    public static Ref<EntityStore> firingEntity(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            Ref<EntityStore> entity = ctx.getEntity();
            return entity != null && entity.isValid() ? entity : null;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read firing entity", t);
            return null;
        }
    }

    /** {@code ctx.getOwningEntity()} when valid, else {@link #firingEntity}, else null. */
    @Nullable
    public static Ref<EntityStore> owner(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            Ref<EntityStore> owning = ctx.getOwningEntity();
            if (owning != null && owning.isValid()) {
                return owning;
            }
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read owning entity", t);
        }
        return firingEntity(ctx);
    }

    /** {@code ctx.getTargetEntity()} (the {@code TARGET_ENTITY} meta) when valid, else null. */
    @Nullable
    public static Ref<EntityStore> target(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            Ref<EntityStore> target = ctx.getTargetEntity();
            return target != null && target.isValid() ? target : null;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read target entity", t);
            return null;
        }
    }

    /** The held item stack for this interaction, or null. */
    @Nullable
    public static ItemStack heldItem(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getHeldItem();
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read held item", t);
            return null;
        }
    }

    /** {@code buffer(ctx).getExternalData().getWorld()}. */
    @Nullable
    public static World world(@Nullable InteractionContext ctx) {
        CommandBuffer<EntityStore> buffer = buffer(ctx);
        if (buffer == null) {
            return null;
        }
        try {
            EntityStore externalData = buffer.getExternalData();
            return externalData != null ? externalData.getWorld() : null;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to resolve world", t);
            return null;
        }
    }

    /**
     * The {@code PlayerRef} COMPONENT on {@code ref} (never the deprecated {@code
     * Player.getPlayerRef()}).
     */
    @Nullable
    public static PlayerRef player(@Nullable InteractionContext ctx, @Nullable Ref<EntityStore> ref) {
        CommandBuffer<EntityStore> buffer = buffer(ctx);
        if (buffer == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            return buffer.getComponent(ref, PlayerRef.getComponentType());
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read PlayerRef component", t);
            return null;
        }
    }

    /** The {@code UUIDComponent} uuid on {@code ref}. */
    @Nullable
    public static UUID entityId(@Nullable InteractionContext ctx, @Nullable Ref<EntityStore> ref) {
        CommandBuffer<EntityStore> buffer = buffer(ctx);
        if (buffer == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            UUIDComponent component = buffer.getComponent(ref, UUIDComponent.getComponentType());
            return component != null ? component.getUuid() : null;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read UUIDComponent", t);
            return null;
        }
    }

    /**
     * A DEFENSIVE COPY of {@code TransformComponent.getPosition()} (the engine returns the live
     * vector).
     */
    @Nullable
    public static Vector3d position(@Nullable InteractionContext ctx, @Nullable Ref<EntityStore> ref) {
        CommandBuffer<EntityStore> buffer = buffer(ctx);
        if (buffer == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            TransformComponent transform = buffer.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return null;
            }
            Vector3d live = transform.getPosition();
            return live != null ? new Vector3d(live) : null;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read position", t);
            return null;
        }
    }

    /** {@link #position} raised by {@code eyeHeight} on Y. Negative eyeHeight is clamped to 0. */
    @Nullable
    public static Vector3d eyePosition(@Nullable InteractionContext ctx, @Nullable Ref<EntityStore> ref, double eyeHeight) {
        Vector3d pos = position(ctx, ref);
        if (pos == null) {
            return null;
        }
        double clamped = Math.max(0.0, eyeHeight);
        pos.y += clamped;
        return pos;
    }

    /**
     * A DEFENSIVE COPY of {@code HeadRotation.getDirection()}; null when the entity has no
     * HeadRotation.
     */
    @Nullable
    public static Vector3d lookDirection(@Nullable InteractionContext ctx, @Nullable Ref<EntityStore> ref) {
        CommandBuffer<EntityStore> buffer = buffer(ctx);
        if (buffer == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            HeadRotation headRotation = buffer.getComponent(ref, HeadRotation.getComponentType());
            if (headRotation == null) {
                return null;
            }
            Vector3d live = headRotation.getDirection();
            return live != null ? new Vector3d(live) : null;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to read look direction", t);
            return null;
        }
    }
}
