package com.ziggfreed.common.calendar.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.calendar.CalendarContent;
import com.ziggfreed.common.calendar.CalendarRuntime;

/** Read {@code mods/ziggfreedcommon/calendar.json} again, so a switch lands without a restart. */
final class CalendarReloadCommand extends AbstractAsyncCommand {

    CalendarReloadCommand() {
        super(CalendarCommandLine.RELOAD, CalendarAdminMessages.desc(CalendarCommandLine.RELOAD));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        CalendarContent.reloadOwnerFile();
        CalendarAdminMessages.done(ctx, CalendarRuntime.service().isGloballyEnabled() ? "reload.done" : "reload.done.off");
        return CompletableFuture.completedFuture(null);
    }
}
