package com.ziggfreed.common.calendar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.tick.CalendarTick;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.util.SafeLog;

/**
 * The calendar's banners, through the library's one event-title seam ({@link EventTitles}). A run's START
 * line goes to each player once, on their first attendance of that run (so a player online at the start and
 * one joining days later see it alike, and nobody twice). Its END line goes to everyone online when a run
 * ends by its dates or a command, never when the owner switches the event off (off means absent).
 */
public final class CalendarHerald {

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

    /** One player's start banner for {@code eventId}, if its file authors one. */
    public static void showStart(@Nonnull PlayerRef player, @Nonnull String eventId) {
        CalendarEventAsset.HeraldLine line = startLine(CalendarRuntime.service().event(eventId));
        if (line != null) {
            show(player, line);
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
