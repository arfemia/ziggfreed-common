package com.ziggfreed.common.playerstats;

import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.playerstats.asset.StatConfig;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;

/**
 * The stat store's reaction to every produced moment: one cheap check (switched on, and a kind some
 * stat's {@code From} names), then each matching stat moved its own way through {@link PlayerStats}.
 * Registered once at the library-default rank; it never refuses a moment and never writes progression.
 * World thread, inside the producing dispatch.
 */
public final class StatsMomentListener implements MomentListener {

    @Override
    public void react(@Nonnull Moment moment) {
        if (!StatsSwitch.isOn()) {
            return;
        }
        List<StatCounter.Write> writes = StatCounter.writes(StatConfig.getInstance().index(), moment.kindId(),
                moment.target(), moment.qualifier(), moment.amount(), moment.zone());
        for (StatCounter.Write write : writes) {
            PlayerStats.count(moment.store(), moment.ref(), write);
        }
    }
}
