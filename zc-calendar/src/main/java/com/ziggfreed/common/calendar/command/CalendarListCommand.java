package com.ziggfreed.common.calendar.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.CalendarService;

/** Every loaded calendar event, one line each. */
final class CalendarListCommand extends AbstractAsyncCommand {

    CalendarListCommand() {
        super(CalendarCommandLine.LIST, CalendarAdminMessages.desc(CalendarCommandLine.LIST));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        CalendarService service = CalendarRuntime.service();
        List<String> ids = service.eventIds();
        if (ids.isEmpty()) {
            CalendarAdminMessages.detail(ctx, "list.none");
            return CompletableFuture.completedFuture(null);
        }
        CalendarAdminMessages.heading(ctx, "list.header", ids.size());
        long now = CalendarRuntime.now();
        for (String id : ids) {
            CalendarAdminMessages.line(ctx, CalendarStatusLines.row(service, id, now));
        }
        return CompletableFuture.completedFuture(null);
    }
}
