package com.ziggfreed.common.instance.dungeon.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.instance.dungeon.CopyRouter;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.LiveWorlds;
import com.ziggfreed.common.instance.world.InstanceWorlds;

/**
 * {@code close <copy>}: close a dungeon copy now, by its world name. The router takes no one into it again,
 * and the world is removed on zc's remover thread; the engine sends everyone inside back to their own return
 * point as it drains the world. A copy the router no longer tracks is still closed when its live world's name
 * is a loaded dungeon's {@code <id>-<n>}; any other world is refused, so this never closes a world zc did not
 * open as a dungeon copy.
 */
final class DungeonCloseCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> copyArg;

    DungeonCloseCommand() {
        super(ZigDungeonCommand.CLOSE, DungeonCommandMessages.desc(ZigDungeonCommand.CLOSE));
        this.copyArg = withRequiredArg(ZigDungeonCommand.ARG_COPY, DungeonCommandMessages.desc("arg.copy"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String name = copyArg.get(ctx).trim();
        boolean tracked = CopyRouter.shared().closing(name);
        World world = LiveWorlds.world(name);
        String dungeon = CopyRouter.dungeonOf(name);
        boolean dungeonCopy = tracked || (world != null && dungeon != null && DungeonConfig.getInstance().has(dungeon));
        if (!dungeonCopy) {
            DungeonCommandMessages.refused(ctx, "close.unknown", name);
            return CompletableFuture.completedFuture(null);
        }
        InstanceWorlds.removeOffThread(world);
        DungeonCommandMessages.done(ctx, "close.done", world == null ? name : world.getName());
        return CompletableFuture.completedFuture(null);
    }
}
