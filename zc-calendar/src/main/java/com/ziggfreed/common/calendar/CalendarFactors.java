package com.ziggfreed.common.calendar;

import java.util.function.LongSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.occurrence.Occurrence;

/**
 * The calendar's two readings, contributed to every vocabulary on the server. {@code Param} names the
 * event (its file name, any case); neither needs a live subject, so a placement sweep, a shop row and a
 * loot roll read them alike.
 * <ul>
 *   <li>{@value #LIVE}: 1 while a run is going on, 0 while the event is stopped by a command or between
 *       its dates, nothing at all for a blank Param or an event this server lacks, has switched off or
 *       cannot run. Off means ABSENT: every gate on it stays shut (the standing fail-closed rule), and a
 *       reader lists nothing rather than an event "between seasons";</li>
 *   <li>{@value #YEAR}: the year of the run going on (the year it began in), nothing otherwise.</li>
 * </ul>
 * A condition on {@value #LIVE} is a LOCK (a requirement a player reads); gate on the
 * {@code ziggfreedcommon:feature} switches ({@link CalendarFeatures}) to make content vanish instead.
 */
public final class CalendarFactors {

    public static final String OWNER = "ziggfreedcommon";

    public static final String LIVE = "ziggfreedcommon:calendar_live";

    public static final String YEAR = "ziggfreedcommon:calendar_year";

    private CalendarFactors() {
    }

    /** Claim both ids process-wide. Once, at library setup. */
    public static void contribute(@Nonnull CalendarService service, @Nonnull LongSupplier clock) {
        FactorContributions.register(LIVE, OWNER, ctx -> live(service, ctx.param(), clock.getAsLong()));
        FactorContributions.register(YEAR, OWNER, ctx -> year(service, ctx.param(), clock.getAsLong()));
    }

    @Nullable
    static Double live(@Nonnull CalendarService service, @Nullable String param, long nowMs) {
        if (param == null || param.isBlank()) {
            return null;
        }
        String id = param.trim();
        if (!service.isEnabled(id)) {
            return null;
        }
        return service.live(id, nowMs) != null ? 1.0 : 0.0;
    }

    @Nullable
    static Double year(@Nonnull CalendarService service, @Nullable String param, long nowMs) {
        if (param == null || param.isBlank()) {
            return null;
        }
        Occurrence running = service.live(param.trim(), nowMs);
        return running == null ? null : Double.valueOf(running.year());
    }
}
