package com.ziggfreed.common.calendar.asset;

import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;

/** The {@code defaults < pack} fold of every {@link CalendarSpawnAsset}. No owner file: switch the event instead. */
public final class CalendarSpawnConfig extends AbstractKeyedAssetConfig<CalendarSpawnAsset> {

    private static final CalendarSpawnConfig INSTANCE = new CalendarSpawnConfig();

    private CalendarSpawnConfig() {
    }

    @Nonnull
    public static CalendarSpawnConfig getInstance() {
        return INSTANCE;
    }

    /** One warning per file that can never write its rule, naming what it lacks. */
    public void reportProblems() {
        for (Map.Entry<String, CalendarSpawnAsset> entry : all().entrySet()) {
            if (entry.getValue().eventId() == null) {
                SafeLog.warn("[calendar] the calendar spawn '" + entry.getKey() + "' names no Event, so it never"
                        + " writes its rule");
            }
            if (entry.getValue().spawnJson() == null) {
                SafeLog.warn("[calendar] the calendar spawn '" + entry.getKey() + "' has no Spawn rule body, so it"
                        + " never writes its rule");
            }
        }
    }
}
