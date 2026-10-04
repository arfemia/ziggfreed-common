package com.ziggfreed.common.almanac;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorProvider;

/**
 * The calendar, read through the two factors it contributes process-wide, so the Almanac needs no
 * edge to the module that runs it: {@value #LIVE_FACTOR} (1 while a season is on, 0 between seasons)
 * and {@value #YEAR_FACTOR} (the live season's opening year), both with the event id as Param.
 *
 * <p>Silence is absence: no contributed factor, a null answer or a throwing provider all read as "the
 * calendar has no such event switched on", so a server without a calendar shows no seasons rather than
 * an error. A season that is on with no year reads as between seasons: nothing is filed under a
 * season the calendar cannot name.
 */
public final class FactorAlmanacCalendar implements AlmanacCalendar {

    /** Whether a season is on. */
    public static final String LIVE_FACTOR = "ziggfreedcommon:calendar_live";

    /** The year the live season opened in. */
    public static final String YEAR_FACTOR = "ziggfreedcommon:calendar_year";

    /** The production calendar: whatever the server's calendar contributed, read fresh on every ask. */
    public static final FactorAlmanacCalendar INSTANCE = new FactorAlmanacCalendar(FactorContributions::provider);

    private final Function<String, FactorProvider> providers;

    public FactorAlmanacCalendar(@Nonnull Function<String, FactorProvider> providers) {
        this.providers = providers;
    }

    @Override
    @Nullable
    public SeasonState state(@Nonnull String eventId) {
        Double live = read(LIVE_FACTOR, eventId);
        if (live == null) {
            return null;
        }
        if (live < 0.5) {
            return SeasonState.BETWEEN;
        }
        Double year = read(YEAR_FACTOR, eventId);
        return year == null || year < 1.0 ? SeasonState.BETWEEN : SeasonState.liveIn((int) Math.round(year));
    }

    @Nullable
    private Double read(@Nonnull String factorId, @Nonnull String eventId) {
        FactorProvider provider = providers.apply(factorId);
        if (provider == null) {
            return null;
        }
        try {
            return provider.resolve(FactorContext.builder().param(eventId).build());
        } catch (Throwable t) {
            return null;
        }
    }
}
