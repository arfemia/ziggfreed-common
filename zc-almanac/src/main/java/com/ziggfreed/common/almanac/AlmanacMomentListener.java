package com.ziggfreed.common.almanac;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.almanac.asset.AlmanacEntryConfig;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;

/**
 * The Almanac's reaction to every produced moment: one cheap check (switched on, and a kind some page
 * or the calendar names), then the counter over the player's own record. Registered once at the
 * library-default rank; it never refuses a moment and never writes progression. World thread, inside
 * the producing dispatch.
 */
public final class AlmanacMomentListener implements MomentListener {

    private final AlmanacCalendar calendar;

    public AlmanacMomentListener(@Nonnull AlmanacCalendar calendar) {
        this.calendar = calendar;
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
        AlmanacCounter.count(record.tallies, index, calendar, moment.kindId(), moment.target(),
                moment.qualifier(), moment.amount(), moment.zone());
    }

    /** Is there anything to count for {@code kindId} at all? */
    static boolean shouldCount(boolean switchedOn, @Nonnull String kindId, @Nonnull AlmanacIndex index) {
        return switchedOn && (AlmanacCounter.ATTENDED_KIND.equalsIgnoreCase(kindId)
                || !index.forKind(kindId).isEmpty());
    }
}
