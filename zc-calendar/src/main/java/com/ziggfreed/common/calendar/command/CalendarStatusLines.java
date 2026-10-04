package com.ziggfreed.common.calendar.command;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.calendar.AnnualWindow;
import com.ziggfreed.common.calendar.CalendarService;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.occurrence.Occurrence;

/**
 * What /zigcalendar says about an event, as lines: a key under the admin prefix and its raw arguments (an
 * id, a year, a {@code yyyy-MM-dd} day, a problem code: data that reads the same in every language). Pure.
 */
public final class CalendarStatusLines {

    /** One line: its key under the admin prefix, and its raw arguments. */
    public record Line(@Nonnull String key, @Nonnull List<String> args) {

        public Line {
            args = List.copyOf(args);
        }

        @Nonnull
        static Line of(@Nonnull String key, @Nonnull String... args) {
            return new Line(key, List.of(args));
        }
    }

    private CalendarStatusLines() {
    }

    /** The one-line answer the list shows for an event. */
    @Nonnull
    public static Line row(@Nonnull CalendarService service, @Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = service.event(eventId);
        if (event == null) {
            return Line.of("row.unknown", eventId);
        }
        String id = event.getId();
        if (!service.isGloballyEnabled() || !event.isEnabled()) {
            return Line.of("row.off", id);
        }
        if (!event.canRun()) {
            return Line.of("row.broken", id, String.join(", ", event.problems()));
        }
        Occurrence run = service.live(id, nowMs);
        Boolean forced = service.forced(id);
        if (run != null) {
            return Line.of(Boolean.TRUE.equals(forced) ? "row.live.forced" : "row.live", id,
                    Integer.toString(run.year()), day(run.endMs() - 1, event.zone()));
        }
        if (Boolean.FALSE.equals(forced)) {
            return Line.of("row.stopped", id);
        }
        Long next = service.nextStartMs(id, nowMs);
        return next == null ? Line.of("row.off", id) : Line.of("row.waiting", id, day(next, event.zone()));
    }

    /** Everything status says: the row, the dates and zone, the first year, the runs so far, any problem. */
    @Nonnull
    public static List<Line> detail(@Nonnull CalendarService service, @Nonnull String eventId, long nowMs) {
        CalendarEventAsset event = service.event(eventId);
        if (event == null) {
            return List.of(Line.of("row.unknown", eventId));
        }
        List<Line> out = new ArrayList<>();
        out.add(row(service, eventId, nowMs));
        AnnualWindow window = event.annualWindow();
        ZoneId zone = event.zone();
        out.add(Line.of("status.window", window == null ? "?" : window.toString(),
                ZoneOffset.UTC.equals(zone) ? "UTC" : zone.getId()));
        Integer firstYear = event.firstYear();
        out.add(firstYear == null ? Line.of("status.first.none") : Line.of("status.first", firstYear.toString()));
        List<String> years = service.history(event.getId(), nowMs).stream()
                .map(run -> Integer.toString(run.year())).toList();
        out.add(years.isEmpty() ? Line.of("status.history.none") : Line.of("status.history", String.join(", ", years)));
        for (String problem : event.problems()) {
            out.add(Line.of("status.problem", problem));
        }
        return out;
    }

    /** {@code ms} as its {@code yyyy-MM-dd} day in {@code zone}. */
    @Nonnull
    static String day(long ms, @Nonnull ZoneId zone) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), zone).toString();
    }
}
