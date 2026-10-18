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

/**
 * {@code force on}, {@code force off} or {@code force clear}: one registered verb each. An event live by a condition
 * (a Moon or a Live) forced on stays live until cleared, since it has no run whose days could end the force.
 */
final class CalendarForceCommand extends AbstractAsyncCommand {

    /** What forcing a condition on answers: it has no run, so it says when the force ends its own way. */
    private static final String CONDITION_ON_DONE = "force.on.condition.done";

    /** What clearing a condition's force answers: it goes back to its condition, never to dates. */
    private static final String CONDITION_CLEAR_DONE = "force.clear.condition.done";

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
        if (!service.isSwitchedOn(id)) {
            CalendarAdminMessages.refused(ctx, "force.absent", id);
            return CompletableFuture.completedFuture(null);
        }
        boolean condition = event.isCondition();
        switch (move) {
            case ON -> {
                // A condition has no run to take: forced on, it stays live until cleared. An event on dates takes
                // its one run here, as the force is written. A window of per-year days may date no run this year:
                // then there is nothing to force on, nothing is written, and the force standing (a force off
                // included) stays exactly as it was.
                boolean forced = condition ? service.forceConditionOn(id)
                        : service.forceOn(id, CalendarRuntime.now()) != null;
                if (!forced) {
                    CalendarAdminMessages.refused(ctx, "force.on.nodates", id);
                    return CompletableFuture.completedFuture(null);
                }
            }
            case OFF -> CalendarForces.getInstance().forceOff(id);
            case CLEAR -> CalendarForces.getInstance().clear(id);
        }
        CalendarRuntime.ticker().requestEvaluation();
        String done = "force." + move.verb() + ".done";
        if (condition && move == Move.ON) {
            done = CONDITION_ON_DONE;
        } else if (condition && move == Move.CLEAR) {
            done = CONDITION_CLEAR_DONE;
        }
        CalendarAdminMessages.done(ctx, done, id);
        return CompletableFuture.completedFuture(null);
    }
}
