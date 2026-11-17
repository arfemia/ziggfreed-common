package com.ziggfreed.common.ui.route;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.util.SafeLog;

/**
 * Open a destination from any interaction chain: a block's Use, a pad's {@code CollisionEnter}, an item's use. The
 * same {@code "Open"} a placement's press-F and a conversation's answer author, so anything that can be opened
 * there can be opened from a block or an item too, in the same words:
 *
 * <pre>{@code
 * { "Type": "ZigOpen", "Open": "Overworld" }
 * { "Type": "ZigOpen", "Open": { "Type": "Instance", "Instance": "Forgotten_Temple_Goblins" } }
 * }</pre>
 *
 * <p>It hands the destination to {@link Destinations#open} for the player who owns the chain, as the player-only
 * context (no character in front of them). <b>Finished means handed over:</b> the chain finishes, running
 * {@code Next}, once a registered destination was given to its opener, whether or not a screen opened then (a
 * travel destination moves the player on a world task of its own a moment later, and says why itself when it
 * cannot). It takes the {@code Failed} branch when no player owns the chain, the file names no destination, or
 * nothing on this server registered that destination's type. Deciding needs the server, hence
 * {@link WaitForDataFrom#Server}.
 *
 * <p>The temple hub's two pads run it ({@code Zc_Hub_Door_Temple}, {@code Zc_Hub_Door_Overworld}).
 */
public final class ZigOpenInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigOpen";

    @Nullable
    protected Destination open;

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigOpenInteraction> CODEC = BuilderCodec.builder(ZigOpenInteraction.class,
                        ZigOpenInteraction::new, SimpleInstantInteraction.CODEC)
                .appendInherited(new KeyedCodec<>("Open", Destination.CODEC, false),
                        (i, v) -> i.open = v, i -> i.open, (i, p) -> i.open = p.open)
                .documentation("What to open for the player whose chain this is, written as a placement's or a "
                        + "conversation's Open is: a page, a conversation, a way to another place. A type with no "
                        + "fields can be written as its bare name (\"Overworld\"). A Type nothing on the server "
                        + "registered fails this file's read, naming it. Unauthored, the chain takes its Failed "
                        + "branch.").add()
                .build();
    }

    /** The codec, built on first call. Only registration and its codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigOpenInteraction> codec() {
        return Holder.CODEC;
    }

    /** {@code Open}, or null when unauthored. */
    @Nullable
    public Destination destination() {
        return open;
    }

    /** This Type can resolve Failed, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server opens anything; a simulated run would open it twice. */
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
            Ref<EntityStore> owner = InteractionCtx.owner(ctx);
            Destination destination = open;
            if (buffer == null || owner == null || destination == null) {
                return false;
            }
            Player player = buffer.getComponent(owner, Player.getComponentType());
            if (player == null) {
                SafeLog.fine("[destination] " + TYPE_NAME + ": no player owns this chain, so it takes the Failed "
                        + "branch");
                return false;
            }
            if (Destinations.typeIdOf(destination) == null) {
                return false;
            }
            Destinations.open(destination, DestinationContext.of(owner.getStore(), owner, player));
            return true;
        });
    }
}
