package com.ziggfreed.common.objectives.calendar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.calendar.event.CalendarEventEndedEvent;
import com.ziggfreed.common.calendar.event.CalendarEventStartedEvent;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.npc.placement.runtime.NpcPlacementReconciler;
import com.ziggfreed.common.util.SafeLog;

/**
 * Re-reads every NPC placement when a calendar event starts or ends, or an event live by a condition (a Moon or
 * a Live) rises or lapses, so a character placed only while an event is live (a placement whose {@code Season}
 * names the event, or whose {@code Requires} reads the event's {@code <Id>_Live} feature) appears as it begins
 * and leaves as it ends with no restart.
 *
 * <p>A placement's {@code Season} and {@code Requires} are read only when a sweep runs, and the
 * reconciler sweeps a world once and then latches it until something that changes the answer clears
 * the latch. A calendar transition changes the answer and clears nothing, so this forces a sweep of
 * every live world on both of the calendar's native events, and on every look of the calendar's tick that saw a
 * condition rise or lapse ({@link #onTick}), since a condition has no run and fires neither event. It re-reads
 * every placement, never a chosen few, so a placement gated by its {@code Season} alone is swept like any other,
 * and the gate chain reads the season afresh on that sweep. Every path that changes whether a run is going
 * reaches those two events: its dates, {@code /zigcalendar force}, an owner's switch read by
 * {@code /zigcalendar reload} or a pack reload, and the boot's resumed start. Every path that changes whether a
 * condition holds reaches the tick's look: a world's moon, a member event of a Live, a force, a switch, and the
 * boot's own look, which sweeps for a condition already live as a resumed start does for a run.
 *
 * <p>The events and the look arrive on the calendar's tick thread (the boot thread for a resumed start), never a
 * world thread. {@link NpcPlacementReconciler#forceSweep} only marks the world and queues the sweep onto
 * that world's own thread, so asking from here is safe, and two asks in one tick fold into one chain
 * plus at most one more pass per world.
 */
public final class CalendarPlacementSweep {

    /** What a transition asks for, given why: every live world's forced sweep, unless a test swapped it. */
    private static volatile Consumer<String> sweeper = CalendarPlacementSweep::sweepEveryWorld;

    private CalendarPlacementSweep() {
    }

    /** A run began, at its date, by command, or resumed at boot. */
    static void onStarted(@Nonnull CalendarEventStartedEvent event) {
        sweeper.accept(event.eventId() + (event.resumed() ? " is running (resumed at boot)" : " started"));
    }

    /** A run is over: its dates ran out, a command stopped it, or the owner switched it off. */
    static void onEnded(@Nonnull CalendarEventEndedEvent event) {
        sweeper.accept(event.eventId() + (event.switchedOff() ? " was switched off" : " ended"));
    }

    /** One look of the calendar's tick: a sweep when it saw a condition rise or lapse, the boot's look included. */
    static void onTick(@Nonnull CalendarTick tick) {
        String why = conditionsMoved(tick);
        if (why != null) {
            sweeper.accept(why);
        }
    }

    /**
     * What {@code tick} saw happen to the events live by a condition, in the order it lists them ({@code "x rose"},
     * {@code "x is live (at boot)"}, {@code "x lapsed"}, {@code "x was switched off"}); null when no condition rose
     * or lapsed, so the look asks for nothing. Its runs are the native events' to sweep for. Pure.
     */
    @Nullable
    static String conditionsMoved(@Nonnull CalendarTick tick) {
        List<String> moved = new ArrayList<>();
        for (String rose : tick.rose()) {
            moved.add(rose + (tick.booting() ? " is live (at boot)" : " rose"));
        }
        for (CalendarTick.Lapsed lapsed : tick.lapsed()) {
            moved.add(lapsed.eventId() + (lapsed.switchedOff() ? " was switched off" : " lapsed"));
        }
        return moved.isEmpty() ? null : String.join(", ", moved);
    }

    /**
     * Ask {@code sweep} for every world in {@code worlds} that {@code alive} says is running, in order. A
     * missing world, one whose liveness cannot be read and one whose sweep cannot be asked for are each
     * skipped, never costing the others.
     *
     * @return how many worlds were asked
     */
    static <W> int sweepEach(@Nonnull Iterable<W> worlds, @Nonnull Predicate<W> alive,
            @Nonnull Consumer<W> sweep) {
        int asked = 0;
        for (W world : worlds) {
            if (world == null) {
                continue;
            }
            try {
                if (!alive.test(world)) {
                    continue;
                }
                sweep.accept(world);
                asked++;
            } catch (Throwable t) {
                SafeLog.warn("[placement] a world could not be asked to sweep after a calendar change: "
                        + t.getMessage());
            }
        }
        return asked;
    }

    /** The production answer: a forced sweep of every live world in the universe. Never throws. */
    private static void sweepEveryWorld(@Nonnull String why) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            int asked = sweepEach(new ArrayList<>(universe.getWorlds().values()), World::isAlive,
                    world -> NpcPlacementReconciler.forceSweep(world, world.getEntityStore().getStore()));
            if (asked > 0) {
                SafeLog.info("[placement] calendar event " + why + ": sweeping placements in " + asked
                        + " world(s)");
            }
        } catch (Throwable t) {
            SafeLog.warn("[placement] could not sweep placements after calendar event " + why + ": "
                    + t.getMessage());
        }
    }

    /** Hear every transition in {@code testSweeper} instead; null puts the production sweep back. */
    static void useSweeperForTests(@Nullable Consumer<String> testSweeper) {
        sweeper = testSweeper == null ? CalendarPlacementSweep::sweepEveryWorld : testSweeper;
    }
}
