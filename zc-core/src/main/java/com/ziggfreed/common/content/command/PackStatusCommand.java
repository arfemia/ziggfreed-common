package com.ziggfreed.common.content.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.i18n.Msg;

/** Where the server content pack is, whether a save can land in it, and how much it holds. */
final class PackStatusCommand extends AbstractAsyncCommand {

    PackStatusCommand() {
        super(PackCommandLine.STATUS, PackAdminMessages.desc(PackCommandLine.STATUS));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        ServerContent.Status status = ServerContent.status();
        List<Message> lines = new ArrayList<>(List.of(
                PackAdminMessages.line("status.pack", status.packId(), PackAdminMessages.state(status.state())),
                PackAdminMessages.line("status.folder", status.folder().toString()),
                PackAdminMessages.line("status.counts", status.assetFiles(), status.texts())));
        if (status.fileWatcher()) {
            // The engine watches only the folders there when the pack loaded, and those a save made since.
            lines.add(PackAdminMessages.line("status.watcher.on", status.watchedFolders()));
            lines.add(PackAdminMessages.line("status.watcher.new"));
        } else {
            lines.add(PackAdminMessages.line("status.watcher.off"));
        }
        ctx.sendMessage(Msg.lines(lines));
        return CompletableFuture.completedFuture(null);
    }
}
