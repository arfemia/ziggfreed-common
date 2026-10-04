package com.ziggfreed.common.calendar.command;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.ziggfreed.common.calendar.CalendarForces;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.CalendarService;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;

/** {@code force on}, {@code force off} or {@code force clear}: one registered verb each. */
final class CalendarForceCommand extends AbstractAsyncCommand {

    /** Which verb this instance is. */
    enum Move {
        ON, OFF, CLEAR;

        @Nonnull
        String verb() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Move move;
    private final RequiredArg<String> eventArg;

    CalendarForceCommand(@Nonnull Move move) {
        super(move.verb(), CalendarAdminMessages.desc(CalendarCommandLine.FORCE + "." + move.verb()));
        this.move = move;
        this.eventArg = withRequiredArg(CalendarCommandLine.ARG_EVENT, CalendarAdminMessages.desc("arg.event"),
                ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        CalendarService service = CalendarRuntime.service();
        String asked = eventArg.get(ctx);
        CalendarEventAsset event = service.event(asked);
        if (event == null) {
            CalendarAdminMessages.refused(ctx, "force.unknown", asked);
            return CompletableFuture.completedFuture(null);
        }
        String id = event.getId();
        if (!service.isEnabled(id)) {
            CalendarAdminMessages.refused(ctx, "force.absent", id);
            return CompletableFuture.completedFuture(null);
        }
        switch (move) {
            case ON -> CalendarForces.getInstance().force(id, true);
            case OFF -> CalendarForces.getInstance().force(id, false);
            case CLEAR -> CalendarForces.getInstance().clear(id);
        }
        CalendarRuntime.ticker().requestEvaluation();
        CalendarAdminMessages.done(ctx, "force." + move.verb() + ".done", id);
        return CompletableFuture.completedFuture(null);
    }
}
