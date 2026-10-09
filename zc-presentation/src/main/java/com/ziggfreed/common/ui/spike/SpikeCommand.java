package com.ziggfreed.common.ui.spike;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;

/**
 * {@code /zigspike} - open the 1.7.0 redesign's spike bench ({@link SpikePage}). Spike branch {@code rd-spike}
 * only, never merged, so its words are plain English. Admin only: no permission group is set, so the engine
 * derives a node nobody holds until a server grants it (an operator holds everything).
 */
public final class SpikeCommand extends AbstractAsyncCommand {

    public static final String NAME = "zigspike";

    public SpikeCommand() {
        super(NAME, "Open the 1.7.0 redesign spike bench (admin, dev only).");
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            ctx.sendMessage(Msg.raw("Only a player can open the spike bench."));
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref), world);
    }

    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            ctx.sendMessage(Msg.raw("The spike bench could not find your player."));
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new SpikePage(playerRef));
    }
}
