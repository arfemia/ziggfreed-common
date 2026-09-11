package com.ziggfreed.common.entity.overhead;

import javax.annotation.Nonnull;

/**
 * HOW LONG an overhead indicator stays up: until whoever showed it takes it down, or for a span
 * after which it takes itself down.
 *
 * <p>A deadline is resolved against the clock at the moment the indicator is shown, so the value
 * itself is a span rather than an instant and reads the same whichever tick it is applied on. An
 * expired indicator is taken down by the library's own follow pass, so a consumer that showed a
 * timed cue never has to remember to hide it.
 *
 * @param durationMs how long it stays up; {@link Long#MAX_VALUE} means until it is hidden
 */
public record IndicatorLifetime(long durationMs) {

    private static final IndicatorLifetime UNTIL_HIDDEN = new IndicatorLifetime(Long.MAX_VALUE);

    /** Up until {@link OverheadIndicators#hide} or {@link OverheadIndicators#clear} takes it down. */
    @Nonnull
    public static IndicatorLifetime untilHidden() {
        return UNTIL_HIDDEN;
    }

    /** Up for {@code durationMs} from the moment it is shown; a zero or negative span shows nothing. */
    @Nonnull
    public static IndicatorLifetime forMillis(long durationMs) {
        return new IndicatorLifetime(Math.max(0L, durationMs));
    }

    /** True when this never expires on its own. */
    public boolean isUntilHidden() {
        return durationMs == Long.MAX_VALUE;
    }

    /** The instant this runs out when shown at {@code nowMs}, saturating rather than wrapping. */
    public long deadlineFrom(long nowMs) {
        if (isUntilHidden()) {
            return Long.MAX_VALUE;
        }
        long deadline = nowMs + durationMs;
        return deadline < nowMs ? Long.MAX_VALUE : deadline;
    }
}
