package com.ziggfreed.common.effect.costume;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Take off every costume the caller is wearing, on the caller's own world thread. */
final class CostumeOffCommand extends AbstractAsyncCommand {

    CostumeOffCommand() {
        super(CostumeCommand.OFF, CostumeMessages.desc(CostumeCommand.OFF));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            CostumeMessages.refused(ctx, "off.needs_player");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> takeOff(ctx, store, ref), world);
    }

    private static void takeOff(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        if (Costumes.takeOff(store, ref) > 0) {
            CostumeMessages.done(ctx, "off.done");
            return;
        }
        CostumeMessages.detail(ctx, "off.none");
    }
}
