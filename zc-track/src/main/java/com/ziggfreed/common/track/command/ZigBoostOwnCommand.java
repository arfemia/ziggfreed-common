package com.ziggfreed.common.track.command;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.boost.BoostAsset;
import com.ziggfreed.common.track.boost.BoostConfig;
import com.ziggfreed.common.track.boost.BoostText;
import com.ziggfreed.common.track.boost.Boosts;

/**
 * {@code own [--boost=<id>]}: the caller starts a reusable boost they may start themselves ({@code Reusable}, its
 * {@code Reusable.Requires} met), through {@link Boosts#startOwn}, so every limit and wait applies and the start is
 * announced as any is; bare, it lists the reusable boosts they may start, with any wait left. A PLAYER's verb in the
 * admin family: it sits in the engine's adventurer group, which every player holds, and acts only for the caller.
 * A consumer with its own page for these (a boosts page) keeps it; this is the surface a server with no such mod has.
 */
final class ZigBoostOwnCommand extends AbstractAsyncCommand {

    private final OptionalArg<String> boostArg;

    ZigBoostOwnCommand() {
        super(ZigBoostCommand.OWN, BoostCommandMessages.desc(ZigBoostCommand.OWN));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        this.boostArg = withOptionalArg("boost", BoostCommandMessages.desc("arg.own"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        Ref<EntityStore> ref = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        if (ref == null || !ref.isValid()) {
            BoostCommandMessages.refused(ctx, "own.needs_player");
            return CompletableFuture.completedFuture(null);
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        String typed = boostArg.provided(ctx) ? boostArg.get(ctx) : null;
        return runAsync(ctx, () -> onWorldThread(ctx, store, ref, typed), world);
    }

    /** On the caller's world thread: start the boost named, or list the ones they may start. */
    private static void onWorldThread(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, String typed) {
        PlayerRef self = store.getComponent(ref, PlayerRef.getComponentType());
        if (self == null) {
            BoostCommandMessages.refused(ctx, "player.offline");
            return;
        }
        UUID id = self.getUuid();
        if (typed == null || typed.isBlank()) {
            list(ctx, id);
            return;
        }
        Boosts.Start start = Boosts.startOwn(id, typed.trim(), self.getUsername());
        if (start.started()) {
            return;
        }
        BoostCommandMessages.refused(ctx, "own.refused");
        Message reason = BoostText.refusal(start);
        if (reason != null) {
            BoostCommandMessages.refusedLine(ctx, reason);
        }
    }

    /** Every reusable boost {@code id} may start, with any wait left. */
    private static void list(@Nonnull CommandContext ctx, @Nonnull UUID id) {
        boolean any = false;
        for (BoostAsset boost : BoostConfig.getInstance().reusable()) {
            if (!Boosts.mayStartOwn(id, boost)) {
                continue;
            }
            if (!any) {
                BoostCommandMessages.done(ctx, "own.list");
                any = true;
            }
            long waitMs = Boosts.waitMs(id, boost.getId());
            if (waitMs > 0L) {
                BoostCommandMessages.done(ctx, "own.line_wait", BoostText.name(boost), boost.getId(),
                        BoostText.duration(waitMs));
            } else {
                BoostCommandMessages.done(ctx, "own.line", BoostText.name(boost), boost.getId());
            }
        }
        if (!any) {
            BoostCommandMessages.refused(ctx, "own.none");
        }
    }
}
