package com.ziggfreed.common.instance.dungeon.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.instance.dungeon.CopyRouter;
import com.ziggfreed.common.instance.dungeon.LiveWorlds;

/**
 * {@code status}: every dungeon copy open now, one line each: its areas in use, its players inside and on the
 * way, its recent tick time, and whether it takes new parties, is standing empty before it closes, or is
 * being closed.
 */
final class DungeonStatusCommand extends AbstractAsyncCommand {

    DungeonStatusCommand() {
        super(ZigDungeonCommand.STATUS, DungeonCommandMessages.desc(ZigDungeonCommand.STATUS));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        List<CopyRouter.CopyView> copies = CopyRouter.shared().copies(LiveWorlds.INSTANCE);
        if (copies.isEmpty()) {
            DungeonCommandMessages.detail(ctx, "status.none");
            return CompletableFuture.completedFuture(null);
        }
        DungeonCommandMessages.heading(ctx, "status.header", copies.size());
        long now = System.currentTimeMillis();
        for (CopyRouter.CopyView copy : copies) {
            DungeonCommandMessages.detail(ctx, "status.copy", copy.world(), copy.dungeon(), copy.busyAreas(),
                    copy.areas(), copy.present(), copy.onTheWay(), tick(copy.world()), standing(copy, now));
        }
        return CompletableFuture.completedFuture(null);
    }

    /** The copy's recent average tick, to a tenth of a millisecond, or that it is not known yet. */
    @Nonnull
    private static Message tick(@Nonnull String world) {
        double ms = LiveWorlds.INSTANCE.tickAverageMs(world);
        return Double.isFinite(ms) ? DungeonCommandMessages.word("status.tick", Math.round(ms * 10.0) / 10.0)
                : DungeonCommandMessages.word("status.tick.unknown");
    }

    /** Being closed, standing empty until it closes, or taking parties. */
    @Nonnull
    private static Message standing(@Nonnull CopyRouter.CopyView copy, long now) {
        if (copy.closing()) {
            return DungeonCommandMessages.word("status.closing");
        }
        Long closesAt = copy.closesAtMs();
        if (closesAt != null) {
            return DungeonCommandMessages.word("status.empty", Math.max(0L, (closesAt - now + 999L) / 1000L));
        }
        return DungeonCommandMessages.word("status.taking");
    }
}
