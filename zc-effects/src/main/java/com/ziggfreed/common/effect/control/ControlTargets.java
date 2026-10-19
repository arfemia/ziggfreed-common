package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionTarget;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;

/**
 * The entity a control Type's {@code Entity} names, read the native way ({@code InteractionTarget}: User, Owner or
 * Target) through the toolkit's null-safe accessors, so an unset or gone entity answers null.
 */
final class ControlTargets {

    private ControlTargets() {
    }

    @Nullable
    static Ref<EntityStore> resolve(@Nullable InteractionContext ctx, @Nullable InteractionTarget authored,
            @Nonnull InteractionTarget fallback) {
        InteractionTarget which = authored != null ? authored : fallback;
        return switch (which) {
            case USER -> InteractionCtx.firingEntity(ctx);
            case OWNER -> InteractionCtx.owner(ctx);
            case TARGET -> InteractionCtx.target(ctx);
        };
    }

    /** An authored number, or its default when unauthored. */
    static double orDefault(@Nullable Double authored, double fallback) {
        return authored != null ? authored : fallback;
    }
}
