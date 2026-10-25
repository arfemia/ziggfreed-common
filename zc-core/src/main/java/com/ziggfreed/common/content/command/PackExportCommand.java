package com.ziggfreed.common.content.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.content.ContentRefusedException;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Zip the server content pack and its text as a content pack another server loads from its mods
 * folder, and say where the zip went. The zip is written on the asset writer thread, after every save
 * queued before it, and the answer comes from there.
 */
final class PackExportCommand extends AbstractAsyncCommand {

    PackExportCommand() {
        super(PackCommandLine.EXPORT, PackAdminMessages.desc(PackCommandLine.EXPORT));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        return ServerContent.export().handle((export, failure) -> {
            if (failure != null) {
                SafeLog.warn("[content] /zigpack export failed", failure);
                ctx.sendMessage(PackAdminMessages.line("export.failed", ContentRefusedException.problemOf(failure).message()));
                return null;
            }
            SafeLog.info("[content] exported " + export.packId() + " to " + export.zip());
            ctx.sendMessage(PackAdminMessages.line("export.done", export.files(), export.texts(), export.zip().toString()));
            return null;
        });
    }
}
