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
import com.ziggfreed.common.settings.page.SettingsPages;

/**
 * Open the caller's Settings tab: {@code /zighud open}, and plain {@code /zighud} through
 * {@link #openSettings}. A player's own HUD choices live there; the server's HUD layout is a tile on it.
 */
final class HudOpenCommand extends AbstractAsyncCommand {

    HudOpenCommand() {
        super(HudCommandLine.OPEN, HudMessages.desc(HudCommandLine.OPEN));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        return openSettings(this, ctx);
    }

    /** Open the Settings tab for the command's caller, on their world thread; the console is refused. */
    @Nonnull
    static CompletableFuture<Void> openSettings(@Nonnull AbstractAsyncCommand command, @Nonnull CommandContext ctx) {
        // A screen is the whole point of the verb, and only a player has one.
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            HudMessages.refused(ctx, "open.needsPlayer");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return command.runAsync(ctx, () -> onWorldThread(ctx, store, ref), world);
    }

    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !SettingsPages.open(store, ref, player)) {
            HudMessages.refused(ctx, "open.failed");
        }
    }
}
