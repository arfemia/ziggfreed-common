package com.ziggfreed.common.almanac;

import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.playerdata.PlayerRecords;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;

/**
 * The Almanac's reaction to every produced moment: one cheap check (switched on, and the calendar's
 * attendance), then the counter over the player's own record, and whatever that record gained added to
 * the server's own totals ({@link ServerTallies}). A season page's stat lines count in the player stat
 * store ({@link AlmanacStatLines}), never here. Registered once at the library-default rank; it never
 * refuses a moment and never writes progression. World thread, inside the producing dispatch.
 */
public final class AlmanacMomentListener implements MomentListener {

    private final AlmanacCalendar calendar;
    private final ServerTallies server;

    public AlmanacMomentListener(@Nonnull AlmanacCalendar calendar, @Nonnull ServerTallies server) {
        this.calendar = calendar;
        this.server = server;
    }

    @Override
    public void react(@Nonnull Moment moment) {
        if (!shouldCount(AlmanacSwitch.isOn(), moment.kindId())) {
            return;
        }
        ComponentType<EntityStore, AlmanacComponent> type = AlmanacComponent.TYPE;
        AlmanacComponent record = type == null ? null : moment.store().getComponent(moment.ref(), type);
        if (record == null) {
            return;
        }
        if (count(record.tallies, server, calendar, moment.kindId(), moment.target())) {
            PlayerRecords.changed(moment.store(), moment.ref(), AlmanacComponent.REGISTRY_ID);
        }
    }

    /** Is there anything to count for {@code kindId} at all? Only the calendar's attendance, while switched on. */
    static boolean shouldCount(boolean switchedOn, @Nonnull String kindId) {
        return switchedOn && AlmanacCounter.ATTENDED_KIND.equalsIgnoreCase(kindId);
    }

    /**
     * Count one moment into one player's {@code tallies}, then add exactly what the record gained to the
     * server's totals: attendance once per player per season (the record marks it once, so the server sees it
     * once), and each run attended. True when the record gained anything.
     */
    static boolean count(@Nonnull CounterMap tallies, @Nonnull ServerTallies server, @Nonnull AlmanacCalendar calendar,
            @Nonnull String kindId, @Nonnull String target) {
        Map<String, Long> before = tallies.all();
        AlmanacCounter.count(tallies, calendar, kindId, target);
        Map<String, Long> after = tallies.all();
        server.addGrowth(before, after);
        return !before.equals(after);
    }
}
