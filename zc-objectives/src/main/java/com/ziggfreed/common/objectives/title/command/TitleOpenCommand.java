package com.ziggfreed.common.objectives.title.command;

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
import com.ziggfreed.common.objectives.title.page.TitlePickerPages;

/**
 * Open the title picker for the caller: the way in on a server whose installed mods give it no
 * button of their own. A PLAYER's verb in the engine's adventurer group, so every player holds it
 * without the admin family's node (a verb in a permission group skips its family's node).
 */
final class TitleOpenCommand extends AbstractAsyncCommand {

    TitleOpenCommand() {
        super(TitleCommandLine.OPEN, TitleCommandMessages.desc(TitleCommandLine.OPEN));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            TitleCommandMessages.refused(ctx, "open.needs_player");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref), world);
    }

    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !TitlePickerPages.open(store, ref, player)) {
            TitleCommandMessages.refused(ctx, "open.failed");
        }
    }
}
