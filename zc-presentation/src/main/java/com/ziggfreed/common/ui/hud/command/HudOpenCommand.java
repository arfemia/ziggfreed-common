package com.ziggfreed.common.ui.hud.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.hud.settings.HudSettingsPages;

/**
 * Open the HUD settings page for the caller: the way in on a server whose installed mods give it no
 * button of their own.
 */
final class HudOpenCommand extends AbstractAsyncCommand {

    HudOpenCommand() {
        super(HudCommandLine.OPEN, HudMessages.desc(HudCommandLine.OPEN));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        // A screen is the whole point of the verb, and only a player has one.
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            HudMessages.refused(ctx, "open.needsPlayer");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref), world);
    }

    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !HudSettingsPages.open(store, ref, player)) {
            HudMessages.refused(ctx, "open.failed");
        }
    }
}
