package com.ziggfreed.common.almanac.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.almanac.AlmanacSwitch;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.page.AlmanacPages;

/** Open the Almanac for the caller, optionally on one season ({@code --event=<id>}). */
final class AlmanacOpenCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> eventArg;

    AlmanacOpenCommand() {
        super(AlmanacCommandLine.OPEN, AlmanacText.desc(AlmanacCommandLine.OPEN));
        this.eventArg = withOptionalArg("event", AlmanacText.desc("arg.event"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        // A screen is the whole point of the verb, and only a player has one.
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            AlmanacText.refused(ctx, "open.needsPlayer");
            return CompletableFuture.completedFuture(null);
        }
        if (!AlmanacSwitch.isOn()) {
            AlmanacText.refused(ctx, "open.off");
            return CompletableFuture.completedFuture(null);
        }
        String eventId = eventArg.provided(ctx) ? eventArg.get(ctx) : null;
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref, eventId), world);
    }

    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nullable String eventId) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !AlmanacPages.open(eventId, store, ref, player)) {
            AlmanacText.refused(ctx, "open.failed");
        }
    }
}
