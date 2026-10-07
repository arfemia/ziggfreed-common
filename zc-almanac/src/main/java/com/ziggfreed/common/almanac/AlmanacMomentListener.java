package com.ziggfreed.common.almanac;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;

/**
 * The Almanac's reaction to every produced moment: one cheap check (switched on, and a kind some page
 * or the calendar names), then the counter over the player's own record, and whatever that record
 * gained added to the server's own totals ({@link ServerTallies}). Registered once at the
 * library-default rank; it never refuses a moment and never writes progression. World thread, inside
 * the producing dispatch.
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
        AlmanacIndex index = AlmanacEntryConfig.getInstance().index();
        if (!shouldCount(AlmanacSwitch.isOn(), moment.kindId(), index)) {
            return;
        }
        ComponentType<EntityStore, AlmanacComponent> type = AlmanacComponent.TYPE;
        AlmanacComponent record = type == null ? null : moment.store().getComponent(moment.ref(), type);
        if (record == null) {
            return;
        }
        count(record.tallies, server, index, calendar, moment.kindId(), moment.target(), moment.qualifier(),
                moment.amount(), moment.zone());
    }

    /** Is there anything to count for {@code kindId} at all? */
    static boolean shouldCount(boolean switchedOn, @Nonnull String kindId, @Nonnull AlmanacIndex index) {
        return switchedOn && (AlmanacCounter.ATTENDED_KIND.equalsIgnoreCase(kindId)
                || !index.forKind(kindId).isEmpty());
    }

    /**
     * Count one moment into one player's {@code tallies}, then add exactly what the record gained to the
     * server's totals: a stat line's amount, and attendance once per player per season (the record marks
     * it once, so the server sees it once).
     */
    static void count(@Nonnull CounterMap tallies, @Nonnull ServerTallies server, @Nonnull AlmanacIndex index,
            @Nonnull AlmanacCalendar calendar, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount, @Nullable ZoneRef zone) {
        Map<String, Long> before = tallies.all();
        AlmanacCounter.count(tallies, index, calendar, kindId, target, qualifier, amount, zone);
        server.addGrowth(before, tallies.all());
    }
}
