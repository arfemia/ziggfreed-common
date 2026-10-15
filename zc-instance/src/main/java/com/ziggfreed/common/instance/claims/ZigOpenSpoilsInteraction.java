package com.ziggfreed.common.instance.claims;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.interaction.type.InteractionOutcome;
import com.ziggfreed.common.util.SafeLog;

/**
 * Open a spoils chest for the player who used it ({@link SpoilsChests}): the step a chest's Use runs, through the
 * root interaction {@link SpoilsChests#ROOT_INTERACTION} its {@code Interactions} name. Takes no authored parameter:
 * the chest it was used on says which fight, by its {@link SpoilsChest} mark, and {@link SpoilsView#open} shows the
 * player their own Claims from that fight.
 *
 * <pre>{@code
 * { "Type": "ZigOpenSpoils" }
 * }</pre>
 *
 * <p>Finished once the view is on its way (it opens on a world task of its own, outside the interaction system);
 * Failed for a chain no player owns or a target that is no spoils chest. Deciding needs the server, hence
 * {@link WaitForDataFrom#Server}.
 */
public final class ZigOpenSpoilsInteraction extends SimpleInstantInteraction {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigOpenSpoils";

    /** Lazy holder: an eager codec would class-init {@code Interaction}, which throws outside a live server. */
    private static final class Holder {
        static final BuilderCodec<ZigOpenSpoilsInteraction> CODEC = BuilderCodec.builder(
                ZigOpenSpoilsInteraction.class, ZigOpenSpoilsInteraction::new, SimpleInstantInteraction.CODEC)
                .build();
    }

    /** The codec, built on first call. Only registration invokes this. */
    @Nonnull
    public static BuilderCodec<ZigOpenSpoilsInteraction> codec() {
        return Holder.CODEC;
    }

    /** This Type resolves Failed on a miss, so the client waits for the server's state. */
    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    /** Only the server opens a page; a simulated run would open nothing. */
    @Override
    protected void simulateFirstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        // Server tick only.
    }

    @Override
    protected void firstRun(@Nonnull InteractionType interactionType, @Nonnull InteractionContext ctx,
            @Nonnull CooldownHandler cooldownHandler) {
        InteractionOutcome.guard(ctx, TYPE_NAME, () -> {
            Ref<EntityStore> owner = InteractionCtx.owner(ctx);
            Ref<EntityStore> chest = InteractionCtx.target(ctx);
            CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
            if (owner == null || chest == null || buffer == null || SpoilsChest.TYPE == null) {
                SafeLog.fine("[interaction] " + TYPE_NAME + ": no player or no chest in this chain, so it takes the"
                        + " Failed branch");
                return false;
            }
            SpoilsChest mark = buffer.getComponent(chest, SpoilsChest.TYPE);
            World world = InteractionCtx.world(ctx);
            String fight = mark == null ? null : mark.fight();
            if (fight == null || world == null || buffer.getComponent(owner, Player.getComponentType()) == null) {
                return false;
            }
            // Opened on a task of its own: paying what is not an item may write the player's components, which a
            // store refuses while the interaction system runs.
            world.execute(() -> openFor(owner, fight));
            return true;
        });
    }

    /** On the world thread, outside every system: open the spoils for the player behind {@code owner}, still here. */
    private static void openFor(@Nonnull Ref<EntityStore> owner, @Nonnull String fight) {
        if (!owner.isValid()) {
            return;
        }
        Store<EntityStore> store = owner.getStore();
        SpoilsView.open(store, owner, store.getComponent(owner, Player.getComponentType()), fight);
    }
}
