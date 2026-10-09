package com.ziggfreed.common.calendar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.util.SafeLog;

/**
 * The calendar's banners, through the library's one event-title seam ({@link EventTitles}). A run's START
 * line goes to each player once, on their first attendance of that run (so a player online at the start and
 * one joining days later see it alike, and nobody twice); when one credit names several runs, their start
 * banners queue a gap apart so none overwrites the one before. Its END line goes to everyone online when a run
 * ends by its dates or a command, never when the owner switches the event off (off means absent); when one tick
 * ends several runs, their end banners queue the same gap apart. A tick is one queue: its end banners first, then
 * the start banners its fresh runs owe; a run followed at once by its event's next run shows no end banner; and an
 * event may show its banners on every run, only on its first run of each year, or never
 * ({@code Herald.FirstRunOfYear}, {@code Herald.Enabled}).
 */
public final class CalendarHerald {

    /** A banner's whole time on screen (fade in, hold, fade out): the gap between two queued banners, start or end. */
    public static final long START_GAP_MS = Math.round(
            (EventTitles.DEFAULT_FADE_IN + EventTitles.DEFAULT_DURATION + EventTitles.DEFAULT_FADE_OUT) * 1000.0);

    private CalendarHerald() {
    }

    /** One banner a moment owes a player: its event, its line, and how long after the moment it shows. */
    public record QueuedBanner(@Nonnull String eventId, @Nonnull CalendarEventAsset.HeraldLine line, long delayMs) {
    }

    /** The end banners {@code tick} owes, in tick order: the lines of {@link #endQueue}. */
    @Nonnull
    public static List<CalendarEventAsset.HeraldLine> endLines(@Nonnull CalendarTick tick,
            @Nonnull Function<String, CalendarEventAsset> events) {
        List<CalendarEventAsset.HeraldLine> out = new ArrayList<>();
        for (QueuedBanner banner : endQueue(tick, events)) {
            out.add(banner.line());
        }
        return out;
    }

    /**
     * The end banners {@code tick} owes, queued as a credit's start banners are: the first at once, each later
     * one {@link #START_GAP_MS} after the one before. A switch-off (off means absent) and an event authoring
     * no End line take no slot. A run whose event's next run starts on the same tick, or whose event shows no
     * banner for it, takes no slot either.
     */
    @Nonnull
    public static List<QueuedBanner> endQueue(@Nonnull CalendarTick tick,
            @Nonnull Function<String, CalendarEventAsset> events) {
        Set<String> startingAgain = new HashSet<>();
        for (CalendarTick.Started start : tick.started()) {
            startingAgain.add(start.occurrence().eventId());
        }
        List<String> ended = new ArrayList<>();
        for (CalendarTick.Ended end : tick.ended()) {
            Occurrence run = end.occurrence();
            // A run followed at once by its event's next run shows no end banner: the next run's start banner speaks.
            if (!end.switchedOff() && !startingAgain.contains(run.eventId())
                    && shows(events.apply(run.eventId()), run)) {
                ended.add(run.eventId());
            }
        }
        return queue(ended, id -> endLine(events.apply(id)), 0L);
    }

    /** Does {@code event} show its banners for {@code run}: its Herald on, and every run or this the year's first? */
    public static boolean shows(@Nullable CalendarEventAsset event, @Nonnull Occurrence run) {
        return event != null && event.heraldShows(run.year(), run.number());
    }

    /** The start banner of {@code event}, or null when it authors none with a title. */
    @Nullable
    public static CalendarEventAsset.HeraldLine startLine(@Nullable CalendarEventAsset event) {
        CalendarEventAsset.HeraldLine line = event == null ? null : event.heraldStart();
        return line == null || line.titleKey() == null ? null : line;
    }

    /** The end banner of {@code event}, or null when it authors none with a title. */
    @Nullable
    public static CalendarEventAsset.HeraldLine endLine(@Nullable CalendarEventAsset event) {
        CalendarEventAsset.HeraldLine line = event == null ? null : event.heraldEnd();
        return line == null || line.titleKey() == null ? null : line;
    }

    /**
     * The start banners one credit owes, in credit order: the first at once, each later one {@link #START_GAP_MS}
     * after the one before. A run whose event authors no start line (or is no longer loaded) takes no slot.
     */
    @Nonnull
    public static List<QueuedBanner> startQueue(@Nonnull List<String> eventIds,
            @Nonnull Function<String, CalendarEventAsset> events) {
        return queue(eventIds, id -> startLine(events.apply(id)), 0L);
    }

    /**
     * The start banners one credit owes for {@code runs}, queued from {@code afterMs}: a run whose event shows a
     * start banner for it takes a slot, each a gap after the one before.
     */
    @Nonnull
    public static List<QueuedBanner> startQueue(@Nonnull List<Occurrence> runs,
            @Nonnull Function<String, CalendarEventAsset> events, long afterMs) {
        List<String> shown = new ArrayList<>();
        for (Occurrence run : runs) {
            if (shows(events.apply(run.eventId()), run)) {
                shown.add(run.eventId());
            }
        }
        return queue(shown, id -> startLine(events.apply(id)), afterMs);
    }

    /** When a tick's start banners begin: after its end banners, so one tick is one queue. */
    public static long startsAfterMs(@Nonnull CalendarTick tick, @Nonnull Function<String, CalendarEventAsset> events) {
        return endQueue(tick, events).size() * START_GAP_MS;
    }

    /** One banner per event that has a line, a gap apart, the first at {@code afterMs}. */
    @Nonnull
    private static List<QueuedBanner> queue(@Nonnull List<String> eventIds,
            @Nonnull Function<String, CalendarEventAsset.HeraldLine> lineOf, long afterMs) {
        List<QueuedBanner> out = new ArrayList<>();
        for (String eventId : eventIds) {
            CalendarEventAsset.HeraldLine line = lineOf.apply(eventId);
            if (line != null) {
                out.add(new QueuedBanner(eventId, line, afterMs + out.size() * START_GAP_MS));
            }
        }
        return out;
    }

    /** A tick listener: everyone online sees each end banner the tick owes, a gap apart. */
    public static void onTick(@Nonnull CalendarTick tick) {
        List<QueuedBanner> queue = endQueue(tick, CalendarRuntime.service()::event);
        if (queue.isEmpty()) {
            return;
        }
        try {
            for (PlayerRef player : Universe.get().getPlayers()) {
                if (player != null) {
                    showQueued(player, queue);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[calendar] the end herald failed", t);
        }
    }

    /**
     * One player's start banners for the runs one credit named, called on their world thread, queued from
     * {@code afterMs} (after the tick's end banners for a credit at a run's start, 0 for one on entering a world).
     */
    public static void showStarts(@Nonnull PlayerRef player, @Nonnull List<Occurrence> runs, long afterMs) {
        showQueued(player, startQueue(runs, CalendarRuntime.service()::event, afterMs));
    }

    /** Show a queue to one player: a banner due now at once, each later one when its turn comes. */
    private static void showQueued(@Nonnull PlayerRef player, @Nonnull List<QueuedBanner> queue) {
        for (QueuedBanner banner : queue) {
            if (banner.delayMs() <= 0L) {
                show(player, banner.line());
            } else {
                showLater(player.getUuid(), banner.line(), banner.delayMs());
            }
        }
    }

    /** Show {@code line} to the player {@code delayMs} from now, if they are still connected and in a world. */
    private static void showLater(@Nonnull UUID playerId, @Nonnull CalendarEventAsset.HeraldLine line, long delayMs) {
        try {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> showIfStillHere(playerId, line), delayMs,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not queue a banner", t);
        }
    }

    /** A queued banner's turn, on the scheduler: hop to the player's world thread, or drop it if they left. */
    private static void showIfStillHere(@Nonnull UUID playerId, @Nonnull CalendarEventAsset.HeraldLine line) {
        try {
            PlayerRef player = Universe.get().getPlayer(playerId);
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            if (ref == null) {
                return;
            }
            PlayerWorldThread.queue(ref, () -> {
                try {
                    if (ref.isValid()) {
                        show(player, line);
                    }
                } catch (Throwable t) {
                    SafeLog.warn("[calendar] a queued banner failed", t);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[calendar] a queued banner failed", t);
        }
    }

    static void show(@Nonnull PlayerRef player, @Nonnull CalendarEventAsset.HeraldLine line) {
        String title = line.titleKey();
        if (title == null) {
            return;
        }
        String subtitle = line.subtitleKey();
        EventTitles.show(player, ContentKeys.tr(title), subtitle == null ? Msg.raw("") : ContentKeys.tr(subtitle),
                line.major());
    }
}
