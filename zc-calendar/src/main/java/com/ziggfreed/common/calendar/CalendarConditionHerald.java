package com.ziggfreed.common.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.calendar.CalendarHerald.QueuedBanner;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.util.SafeLog;

/**
 * The banners of an event live by a condition (a Moon or a Live). It has no runs, so no attendance credits it and
 * {@link CalendarHerald}'s run rules never see it. Its START line goes to everyone online as it rises, and to each
 * player who enters a world while it is live, once per rise; its END line goes to everyone online as it lapses by
 * its condition or a command, never when it is switched off (off means absent). With {@code Herald.OncePerDay} a
 * player sees each line at most once a day, counted in the event's Clock. {@code Herald.Enabled} false keeps it
 * quiet. A tick's condition banners queue after that tick's run banners, a gap apart, and an entry's after the
 * entry's run banners ({@code CalendarAttendance}), so one moment stays one queue.
 *
 * <p>Which rise each player has seen lives in memory, like a force: a restart may show a rise's banner again.
 */
public final class CalendarConditionHerald {

    /** When each live condition's current rise began, by event id; gone once it lapses. */
    private static final Map<String, Long> RISEN = new ConcurrentHashMap<>();

    /** What each player last saw of each condition's banners. */
    private static final Map<UUID, Map<String, Seen>> SEEN = new ConcurrentHashMap<>();

    private CalendarConditionHerald() {
    }

    /**
     * What one player last saw of one condition: the rise its start banner was for, and the day of each line.
     *
     * @param riseMs   when the rise whose start banner they saw began
     * @param startDay the day they last saw its start banner
     * @param endDay   the day they last saw its end banner
     */
    record Seen(long riseMs, @Nullable LocalDate startDay, @Nullable LocalDate endDay) {

        static final Seen NONE = new Seen(Long.MIN_VALUE, null, null);
    }

    /**
     * Does a player who last saw {@code seen} get the start banner of the rise that began at {@code riseMs},
     * {@code today}? Once per rise, and at most once a day when {@code oncePerDay}.
     */
    static boolean showsStart(@Nonnull Seen seen, long riseMs, boolean oncePerDay, @Nonnull LocalDate today) {
        return seen.riseMs() != riseMs && (!oncePerDay || !today.equals(seen.startDay()));
    }

    /** Does a player who last saw {@code seen} get the end banner {@code today}? Every lapse, or once a day. */
    static boolean showsEnd(@Nonnull Seen seen, boolean oncePerDay, @Nonnull LocalDate today) {
        return !oncePerDay || !today.equals(seen.endDay());
    }

    /** Is any condition live, by the last look? A player entering a world then has a banner to be asked about. */
    public static boolean anyLive() {
        return !RISEN.isEmpty();
    }

    /**
     * A tick listener: note each rise and lapse, then show everyone online the banners they owe, after the tick's
     * run banners. The boot's look shows nothing (nobody is online yet); a player entering after it is asked.
     */
    public static void onTick(@Nonnull CalendarTick tick) {
        for (CalendarTick.Lapsed lapsed : tick.lapsed()) {
            RISEN.remove(lapsed.eventId());
        }
        for (String rose : tick.rose()) {
            RISEN.put(rose, tick.nowMs());
        }
        if (tick.booting() || (tick.rose().isEmpty() && tick.lapsed().isEmpty())) {
            return;
        }
        try {
            Function<String, CalendarEventAsset> events = CalendarRuntime.service()::event;
            long afterMs = CalendarHerald.conditionsAfterMs(tick, events);
            for (PlayerRef player : Universe.get().getPlayers()) {
                if (player != null) {
                    CalendarHerald.showQueued(player, owed(player.getUuid(), tick, events, afterMs));
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[calendar] a condition's banner failed", t);
        }
    }

    /**
     * One player's banners on entering a world, on their world thread: the start banner of each condition live now
     * whose rise they have not seen, queued from {@code afterMs} (after the entry's run banners).
     */
    public static void showOnEntry(@Nonnull PlayerRef player, long nowMs, long afterMs) {
        try {
            Function<String, CalendarEventAsset> events = CalendarRuntime.service()::event;
            List<QueuedBanner> out = new ArrayList<>();
            for (Map.Entry<String, Long> rise : new TreeMap<>(RISEN).entrySet()) {
                CalendarEventAsset event = events.apply(rise.getKey());
                CalendarEventAsset.HeraldLine line = shown(event) ? CalendarHerald.startLine(event) : null;
                if (line != null && markStart(player.getUuid(), event, rise.getValue(), nowMs)) {
                    out.add(new QueuedBanner(rise.getKey(), line, afterMs + out.size() * CalendarHerald.START_GAP_MS));
                }
            }
            CalendarHerald.showQueued(player, out);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] a condition's banner on entering a world failed", t);
        }
    }

    /**
     * The banners {@code tick} owes {@code player}, marked as seen: each lapse's end banner (not a switch-off), then
     * each rise's start banner, from {@code afterMs}, each a gap after the one before.
     */
    @Nonnull
    static List<QueuedBanner> owed(@Nonnull UUID player, @Nonnull CalendarTick tick,
            @Nonnull Function<String, CalendarEventAsset> events, long afterMs) {
        List<QueuedBanner> out = new ArrayList<>();
        for (CalendarTick.Lapsed lapsed : tick.lapsed()) {
            CalendarEventAsset event = lapsed.switchedOff() ? null : events.apply(lapsed.eventId());
            CalendarEventAsset.HeraldLine line = shown(event) ? CalendarHerald.endLine(event) : null;
            if (line != null && markEnd(player, event, tick.nowMs())) {
                out.add(new QueuedBanner(lapsed.eventId(), line, afterMs + out.size() * CalendarHerald.START_GAP_MS));
            }
        }
        for (String rose : tick.rose()) {
            CalendarEventAsset event = events.apply(rose);
            CalendarEventAsset.HeraldLine line = shown(event) ? CalendarHerald.startLine(event) : null;
            if (line != null && markStart(player, event, tick.nowMs(), tick.nowMs())) {
                out.add(new QueuedBanner(rose, line, afterMs + out.size() * CalendarHerald.START_GAP_MS));
            }
        }
        return out;
    }

    /** Is {@code event} loaded, live by a condition and heralded? */
    private static boolean shown(@Nullable CalendarEventAsset event) {
        return event != null && event.isCondition() && event.heraldOn();
    }

    /** Note that {@code player} sees {@code event}'s start banner for the rise of {@code riseMs}, if they are owed it. */
    private static boolean markStart(@Nonnull UUID player, @Nonnull CalendarEventAsset event, long riseMs, long nowMs) {
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), event.zone());
        boolean[] shows = {false};
        seenBy(player).compute(event.getId(), (id, seen) -> {
            Seen was = seen == null ? Seen.NONE : seen;
            if (!showsStart(was, riseMs, event.heraldOncePerDay(), today)) {
                return seen;
            }
            shows[0] = true;
            return new Seen(riseMs, today, was.endDay());
        });
        return shows[0];
    }

    /** Note that {@code player} sees {@code event}'s end banner {@code nowMs}, if they are owed it. */
    private static boolean markEnd(@Nonnull UUID player, @Nonnull CalendarEventAsset event, long nowMs) {
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), event.zone());
        boolean[] shows = {false};
        seenBy(player).compute(event.getId(), (id, seen) -> {
            Seen was = seen == null ? Seen.NONE : seen;
            if (!showsEnd(was, event.heraldOncePerDay(), today)) {
                return seen;
            }
            shows[0] = true;
            return new Seen(was.riseMs(), was.startDay(), today);
        });
        return shows[0];
    }

    @Nonnull
    private static Map<String, Seen> seenBy(@Nonnull UUID player) {
        return SEEN.computeIfAbsent(player, id -> new ConcurrentHashMap<>());
    }
}
