package com.ziggfreed.common.calendar;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.calendar.asset.CalendarEventConfig;
import com.ziggfreed.common.calendar.asset.CalendarOwnerLayers;
import com.ziggfreed.common.calendar.asset.CalendarSpawnAsset;
import com.ziggfreed.common.calendar.asset.CalendarSpawnConfig;

/**
 * The calendar's reload entry points: the asset registrar's load listeners and {@code /zigcalendar reload}
 * call these, never the stores directly. Each folds under the calendar's lock, so the tick never reads a
 * layer half folded or looks between the pack layer and the owner layer, then asks the calendar to look
 * again; every entry point shares that one frame ({@code foldThenLook}).
 */
public final class CalendarContent {

    private CalendarContent() {
    }

    /** A pack (re)load of the calendar events: the pack layer, then the owner file, then a fresh look. */
    public static void reloadEvents(@Nonnull Map<String, CalendarEventAsset> packLayer) {
        foldThenLook(() -> {
            CalendarEventConfig.getInstance().mergePackLayer(packLayer);
            CalendarOwnerLayers.reload();
        });
    }

    /** The owner file again, on its own. */
    public static void reloadOwnerFile() {
        foldThenLook(CalendarOwnerLayers::reload);
    }

    /** A pack (re)load of the calendar spawns, then a fresh look. */
    public static void reloadSpawns(@Nonnull Map<String, CalendarSpawnAsset> packLayer) {
        foldThenLook(() -> {
            CalendarSpawnConfig.getInstance().mergePackLayer(packLayer);
            CalendarSpawnConfig.getInstance().reportProblems();
        });
    }

    /**
     * Run {@code fold} under {@link CalendarService#lock()}, the lock the tick reads under: a fold clears a
     * layer before it fills it again, and a look landing in between would read every event or spawn file as
     * gone, or an event the owner switched off as on. Then take the change in and ask for a look, outside
     * the lock.
     */
    private static void foldThenLook(@Nonnull Runnable fold) {
        synchronized (CalendarRuntime.service().lock()) {
            fold.run();
        }
        CalendarRuntime.onContentChanged();
    }
}
