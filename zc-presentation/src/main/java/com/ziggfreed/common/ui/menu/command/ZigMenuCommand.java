package com.ziggfreed.common.ui.menu.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.menu.MenuText;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * {@code /ziggui} - open the shared menu: a consumer's landing, else the first tab that shows, else one
 * line saying nothing is available. Every player holds it (the engine's adventurer group), like the
 * library's other screen-opening verbs; from the console it says only a player can open it.
 */
public final class ZigMenuCommand extends AbstractAsyncCommand {

    public static final String NAME = "ziggui";

    public ZigMenuCommand() {
        super(NAME, MenuText.desc());
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        // A screen is the whole point of the verb, and only a player has one.
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            MenuText.refused(ctx, MenuText.NEEDS_PLAYER);
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref), world);
    }

    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !ZigMenu.openLanding(DestinationContext.of(store, ref, player))) {
            MenuText.refused(ctx, MenuText.NOTHING);
        }
    }
}
