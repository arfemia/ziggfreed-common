package com.ziggfreed.common.calendar;

import java.util.ArrayList;
import java.util.List;
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
import com.ziggfreed.common.util.SafeLog;

/**
 * The calendar's banners, through the library's one event-title seam ({@link EventTitles}). A run's START
 * line goes to each player once, on their first attendance of that run (so a player online at the start and
 * one joining days later see it alike, and nobody twice); when one credit names several runs, their start
 * banners queue a gap apart so none overwrites the one before. Its END line goes to everyone online when a run
 * ends by its dates or a command, never when the owner switches the event off (off means absent).
 */
public final class CalendarHerald {

    /** A banner's whole time on screen (fade in, hold, fade out), so the one before is read before the next. */
    public static final long START_GAP_MS = Math.round(
            (EventTitles.DEFAULT_FADE_IN + EventTitles.DEFAULT_DURATION + EventTitles.DEFAULT_FADE_OUT) * 1000.0);

    private CalendarHerald() {
    }

    /** The end banners {@code tick} owes: real ends whose event authors an End line with a title. */
    @Nonnull
    public static List<CalendarEventAsset.HeraldLine> endLines(@Nonnull CalendarTick tick,
            @Nonnull Function<String, CalendarEventAsset> events) {
        List<CalendarEventAsset.HeraldLine> out = new ArrayList<>();
        for (CalendarTick.Ended ended : tick.ended()) {
            if (ended.switchedOff()) {
                continue;
            }
            CalendarEventAsset event = events.apply(ended.occurrence().eventId());
            CalendarEventAsset.HeraldLine line = event == null ? null : event.heraldEnd();
            if (line != null && line.titleKey() != null) {
                out.add(line);
            }
        }
        return out;
    }

    /** The start banner of {@code event}, or null when it authors none with a title. */
    @Nullable
    public static CalendarEventAsset.HeraldLine startLine(@Nullable CalendarEventAsset event) {
        CalendarEventAsset.HeraldLine line = event == null ? null : event.heraldStart();
        return line == null || line.titleKey() == null ? null : line;
    }

    /** One start banner a credit owes: its event, its line, and how long after the credit it shows. */
    public record QueuedStart(@Nonnull String eventId, @Nonnull CalendarEventAsset.HeraldLine line, long delayMs) {
    }

    /**
     * The start banners one credit owes, in credit order: the first at once, each later one {@link #START_GAP_MS}
     * after the one before. A run whose event authors no start line (or is no longer loaded) takes no slot.
     */
    @Nonnull
    public static List<QueuedStart> startQueue(@Nonnull List<String> eventIds,
            @Nonnull Function<String, CalendarEventAsset> events) {
        List<QueuedStart> out = new ArrayList<>();
        for (String eventId : eventIds) {
            CalendarEventAsset.HeraldLine line = startLine(events.apply(eventId));
            if (line != null) {
                out.add(new QueuedStart(eventId, line, out.size() * START_GAP_MS));
            }
        }
        return out;
    }

    /** A tick listener: everyone online sees each end banner the tick owes. */
    public static void onTick(@Nonnull CalendarTick tick) {
        List<CalendarEventAsset.HeraldLine> lines = endLines(tick, CalendarRuntime.service()::event);
        if (lines.isEmpty()) {
            return;
        }
        try {
            for (PlayerRef player : Universe.get().getPlayers()) {
                if (player == null) {
                    continue;
                }
                for (CalendarEventAsset.HeraldLine line : lines) {
                    show(player, line);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[calendar] the end herald failed", t);
        }
    }

    /**
     * One player's start banners for the runs one credit named, called on their world thread: the first shows at
     * once, each later one waits its turn in {@link #startQueue}.
     */
    public static void showStarts(@Nonnull PlayerRef player, @Nonnull List<String> eventIds) {
        for (QueuedStart start : startQueue(eventIds, CalendarRuntime.service()::event)) {
            if (start.delayMs() <= 0L) {
                show(player, start.line());
            } else {
                showLater(player.getUuid(), start.line(), start.delayMs());
            }
        }
    }

    /** Show {@code line} to the player {@code delayMs} from now, if they are still connected and in a world. */
    private static void showLater(@Nonnull UUID playerId, @Nonnull CalendarEventAsset.HeraldLine line, long delayMs) {
        try {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> showIfStillHere(playerId, line), delayMs,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SafeLog.warn("[calendar] could not queue a start banner", t);
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
                    SafeLog.warn("[calendar] a queued start banner failed", t);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[calendar] a queued start banner failed", t);
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
