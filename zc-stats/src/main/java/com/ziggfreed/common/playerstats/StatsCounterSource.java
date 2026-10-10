package com.ziggfreed.common.playerstats;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.CounterSource;
import com.ziggfreed.common.factor.FactorContext;

/**
 * The library's own answer to {@code ziggfreedcommon:counter}: the tally under the asked key in the
 * player's stat record, so a {@code Requires} block, a dialogue condition or a shop offer reads the same
 * numbers the board ranks, on any server. A consumer that keeps tallies of its own installs its own
 * source after this one.
 *
 * <p>No answer (null) with the store switched off, or for a player with no record; a thing never done
 * reads zero, and a key is matched without regard to case.
 */
public final class StatsCounterSource implements CounterSource {

    @Override
    @Nullable
    public Long count(@Nonnull FactorContext ctx, @Nonnull String key) {
        if (!StatsSwitch.isOn() || !ctx.hasLiveSubject()) {
            return null;
        }
        PlayerStatsComponent record = PlayerStatsComponent.peek(ctx.store(), ctx.subject());
        return record == null ? null : record.get(key.trim());
    }
}
