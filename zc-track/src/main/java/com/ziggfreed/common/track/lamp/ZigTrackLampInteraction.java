package com.ziggfreed.common.track.lamp;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.track.TrackText;

/**
 * A lamp: opens its picker ({@link TrackLampPage}) for the player using it, as a native interaction Type an item's
 * chain names. The lamp's numbers ride the stack in hand ({@link TrackLamp}); the lamp is spent only when the picker's
 * Use pays its picks, never here, so the chain names no consume.
 *
 * <pre>{@code
 * "Interactions": { "Secondary": { "Interactions": [ { "Type": "ZigTrackLamp" } ] } }
 * }</pre>
 *
 * <p>Resolves Failed for a stack carrying no lamp (one taken from the creative menu), telling the player it is empty.
 * Deciding that needs the server's answer, hence {@link WaitForDataFrom#Server}.
 */
public final class ZigTrackLampInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigTrackLamp";

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigTrackLampInteraction> CODEC = BuilderCodec.builder(
                ZigTrackLampInteraction.class, ZigTrackLampInteraction::new, SimpleInstantInteraction.CODEC).build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigTrackLampInteraction> getCODEC() {
        return Holder.CODEC;
    }

    /** This Type can resolve Failed, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server opens the picker. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            Ref<EntityStore> user = InteractionCtx.owner(ctx);
            PlayerRef player = InteractionCtx.player(ctx, user);
            if (player == null || user == null) {
                return false;
            }
            TrackLamp lamp = TrackLamp.of(ctx.getHeldItem());
            if (lamp == null) {
                player.sendMessage(TrackText.line("lamp.empty"));
                return false;
            }
            return TrackLampPage.open(user.getStore(), user, lamp);
        });
    }
}
