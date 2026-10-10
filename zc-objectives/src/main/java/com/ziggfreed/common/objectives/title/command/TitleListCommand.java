package com.ziggfreed.common.objectives.title.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.entity.title.PendingTitles;
import com.ziggfreed.common.objectives.title.TitleUnlocks;
import com.ziggfreed.common.objectives.title.command.TitlePlayers.Named;

/**
 * {@code list [--player=<name>]}: every title the player has unlocked, sorted, as the ids a grant or
 * a revoke would be typed with, the shown one marked. Ids are printed raw on purpose. An online
 * player is read off their record; one who is not, off the title record as it stood when they were
 * last here, with a line saying how many changes wait in the queue for their next login.
 */
final class TitleListCommand extends TitleTargetCommand {

    TitleListCommand() {
        super(TitleCommandLine.LIST);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        list(ctx, target.name(), target.playerRef().getUuid(), TitleUnlocks.unlocked(target.store(), target.ref()),
                TitleUnlocks.active(target.store(), target.ref()));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeOffline(@Nonnull CommandContext ctx, @Nonnull Named target) {
        list(ctx, target.name(), target.id(), List.copyOf(ActiveTitles.earned(target.id())),
                ActiveTitles.of(target.id()));
        return CompletableFuture.completedFuture(null);
    }

    private static void list(@Nonnull CommandContext ctx, @Nonnull String name, @Nonnull UUID playerId,
            @Nonnull List<String> ids, @Nullable String shown) {
        if (ids.isEmpty()) {
            TitleCommandMessages.detail(ctx, "list.none", name);
        } else {
            TitleCommandMessages.heading(ctx, "list.header", name, ids.size());
            for (String id : ids) {
                TitleCommandMessages.detail(ctx, id.equals(shown) ? "list.row_shown" : "list.row", id);
            }
        }
        int queued = PendingTitles.of(playerId).size();
        if (queued > 0) {
            TitleCommandMessages.detail(ctx, "list.queued", name, queued);
        }
    }
}
