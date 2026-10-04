package com.ziggfreed.common.calendar;

import java.util.function.LongSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.tick.CalendarTicker;
import com.ziggfreed.common.calendar.tick.ExecutorTickScheduler;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one calendar a server runs: the service over the shared stores, the clock it reads, and the minute
 * tick. The tick only starts on boot (the bootstrap's {@code BootEvent} hook), so a unit JVM never ticks.
 */
public final class CalendarRuntime {

    private static final CalendarService SERVICE =
            new CalendarService(CalendarEventConfig.getInstance(), CalendarForces.getInstance());

    private static volatile LongSupplier clock = System::currentTimeMillis;

    private static final CalendarTicker TICKER =
            new CalendarTicker(SERVICE, ExecutorTickScheduler.INSTANCE, CalendarRuntime::now);

    private CalendarRuntime() {
    }

    @Nonnull
    public static CalendarService service() {
        return SERVICE;
    }

    @Nonnull
    public static CalendarTicker ticker() {
        return TICKER;
    }

    /** The calendar's clock, in epoch milliseconds. */
    public static long now() {
        return clock.getAsLong();
    }

    /** The calendar's content or a switch changed: declare any arriving event's features, then look again. */
    public static void onContentChanged() {
        try {
            CalendarFeatures.declareEvents(SERVICE, CalendarRuntime::now);
            TICKER.requestEvaluation();
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not take in a calendar change", t);
        }
    }

    /** Stop the minute tick. */
    public static void shutdown() {
        TICKER.stop();
    }

    /** Read time from {@code testClock}; null puts the system clock back. */
    public static void useClockForTests(@Nullable LongSupplier testClock) {
        clock = testClock == null ? System::currentTimeMillis : testClock;
    }
}
