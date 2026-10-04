package com.ziggfreed.common.calendar.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.calendar.CalendarRuntime;

/** One calendar event in detail. */
final class CalendarStatusCommand extends AbstractAsyncCommand {

    private final RequiredArg<String> eventArg;

    CalendarStatusCommand() {
        super(CalendarCommandLine.STATUS, CalendarAdminMessages.desc(CalendarCommandLine.STATUS));
        this.eventArg = withRequiredArg(CalendarCommandLine.ARG_EVENT, CalendarAdminMessages.desc("arg.event"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        for (CalendarStatusLines.Line line : CalendarStatusLines.detail(CalendarRuntime.service(), eventArg.get(ctx),
                CalendarRuntime.now())) {
            CalendarAdminMessages.line(ctx, line);
        }
        return CompletableFuture.completedFuture(null);
    }
}
