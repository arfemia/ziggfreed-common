package com.ziggfreed.common.calendar;

import java.util.function.LongSupplier;

import javax.annotation.Nonnull;

import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.factor.FeatureFlags;

/**
 * The calendar's switches as {@code ziggfreedcommon:feature} features, so content gated on them VANISHES
 * when one reads off (a shared fold lifts a top-level plain feature condition onto the hide axis) rather
 * than sitting there locked:
 * <ul>
 *   <li>{@code Calendar}: the owner's global switch;</li>
 *   <li>{@code <EventId>}: the event is switched on (by the owner and by its own file) and can run;</li>
 *   <li>{@code <EventId>_Live}: that, AND a run is going on now (a force included).</li>
 * </ul>
 * Every supplier re-reads the calendar per gate, so a reload or a force lands on the next look. The
 * namespace is declared at library setup ({@link #declare}), before anything folds content; each event's
 * features are declared as its file arrives ({@link #declareEvents}), and until then they read 0. The
 * namespace is shared ({@code Almanac} is the Almanac's own switch), so an event under a reserved id
 * ({@link CalendarEventAsset#isReservedId}) declares nothing: it would replace another switch, or (an id
 * carrying {@code |} or {@code @}) it never runs because no player's attendance record could hold it.
 */
public final class CalendarFeatures {

    /** The feature namespace, read as {@code ziggfreedcommon:feature}. */
    public static final String NAMESPACE = "ziggfreedcommon";

    /** Who the features are attributed to in the factor ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /** The global switch's feature id. */
    public static final String CALENDAR = "Calendar";

    /** Appended to an event id for its running feature. */
    public static final String LIVE_SUFFIX = "_Live";

    private CalendarFeatures() {
    }

    /** Declare the namespace and the global switch. Once, at library setup. */
    public static void declare(@Nonnull CalendarService service) {
        FeatureFlags.register(NAMESPACE, CALENDAR, OWNER, service::isGloballyEnabled);
    }

    /**
     * Declare both features of every loaded event; re-declaring replaces the supplier, so this is
     * idempotent. An event under a reserved id is skipped: its features would replace the {@code Calendar}
     * or {@code Almanac} switch, or another event's running switch.
     */
    public static void declareEvents(@Nonnull CalendarService service, @Nonnull LongSupplier clock) {
        for (String id : service.eventIds()) {
            if (CalendarEventAsset.isReservedId(id)) {
                continue;
            }
            FeatureFlags.register(NAMESPACE, id, OWNER, () -> service.isEnabled(id));
            FeatureFlags.register(NAMESPACE, id + LIVE_SUFFIX, OWNER,
                    () -> service.live(id, clock.getAsLong()) != null);
        }
    }
}
