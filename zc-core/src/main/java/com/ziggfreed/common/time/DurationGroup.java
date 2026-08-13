package com.ziggfreed.common.time;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The ONE duration codec leaf: a nested {@code {Days, Hours, Minutes, Seconds}} group of
 * independently nullable whole numbers that are simply SUMMED. Embed it wherever an asset codec
 * authors a span of time ({@code new KeyedCodec<>("Cooldown", DurationGroup.CODEC, false)}).
 *
 * <p>Authoring a duration as units rather than as one raw number is what makes a file readable
 * without arithmetic: {@code {"Hours": 24}} says a day, {@code {"Days": 7}} says a week, and
 * {@code {"Minutes": 90}} says an hour and a half without anybody multiplying by sixty. The units
 * compose, so {@code {"Hours": 1, "Minutes": 30}} is the same ninety minutes written the other way
 * round.
 *
 * <p>Every leaf is independently nullable (unauthored = contributes nothing) so partial authoring,
 * native {@code Parent} reuse, and any per-leaf overlay convention all keep single-unit
 * granularity. A NEGATIVE value contributes nothing rather than winding the clock backwards: a
 * negative span is not a span, and silently subtracting one unit from another is never what the
 * author meant.
 *
 * <p><b>An empty group is a real, distinct answer</b>, not an error this type takes a view on.
 * {@link #isEmpty()} reports "no unit was authored at all" and {@link #totalMs()} reports zero for
 * both an empty group and one authored as all zeroes; which of those two a given site treats as a
 * mistake belongs to that site's own validator, because a duration is required in some places and
 * meaningfully absent in others.
 */
public final class DurationGroup {

    /** Milliseconds in one second. */
    private static final long SECOND_MS = 1_000L;

    /** Milliseconds in one minute. */
    private static final long MINUTE_MS = 60L * SECOND_MS;

    /** Milliseconds in one hour. */
    private static final long HOUR_MS = 60L * MINUTE_MS;

    /** Milliseconds in one day. */
    private static final long DAY_MS = 24L * HOUR_MS;

    @Nullable protected Integer days;
    @Nullable protected Integer hours;
    @Nullable protected Integer minutes;
    @Nullable protected Integer seconds;

    public static final BuilderCodec<DurationGroup> CODEC =
            BuilderCodec.builder(DurationGroup.class, DurationGroup::new)
                    .appendInherited(new KeyedCodec<>("Days", Codec.INTEGER, false),
                            (o, v) -> o.days = v, o -> o.days, (o, p) -> o.days = p.days)
                    .documentation("Whole days. Unauthored means none; every unit here is optional and they "
                            + "add up, so a day and a half is either 36 hours or Days 1 plus Hours 12.").add()
                    .appendInherited(new KeyedCodec<>("Hours", Codec.INTEGER, false),
                            (o, v) -> o.hours = v, o -> o.hours, (o, p) -> o.hours = p.hours)
                    .documentation("Whole hours, added to whatever the other units carry. Unauthored means "
                            + "none.").add()
                    .appendInherited(new KeyedCodec<>("Minutes", Codec.INTEGER, false),
                            (o, v) -> o.minutes = v, o -> o.minutes, (o, p) -> o.minutes = p.minutes)
                    .documentation("Whole minutes, added to whatever the other units carry. Unauthored means "
                            + "none.").add()
                    .appendInherited(new KeyedCodec<>("Seconds", Codec.INTEGER, false),
                            (o, v) -> o.seconds = v, o -> o.seconds, (o, p) -> o.seconds = p.seconds)
                    .documentation("Whole seconds, added to whatever the other units carry. Unauthored means "
                            + "none.").add()
                    .build();

    public DurationGroup() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static DurationGroup of(@Nullable Integer days, @Nullable Integer hours,
                                   @Nullable Integer minutes, @Nullable Integer seconds) {
        DurationGroup d = new DurationGroup();
        d.days = days;
        d.hours = hours;
        d.minutes = minutes;
        d.seconds = seconds;
        return d;
    }

    /** A group carrying just this many whole seconds. */
    @Nonnull
    public static DurationGroup ofSeconds(int seconds) {
        return of(null, null, null, Integer.valueOf(seconds));
    }

    @Nullable
    public Integer getDays() {
        return days;
    }

    @Nullable
    public Integer getHours() {
        return hours;
    }

    @Nullable
    public Integer getMinutes() {
        return minutes;
    }

    @Nullable
    public Integer getSeconds() {
        return seconds;
    }

    /** True when not one unit was authored, which is a different fact from "it adds up to zero". */
    public boolean isEmpty() {
        return days == null && hours == null && minutes == null && seconds == null;
    }

    /** True when at least one unit was authored as a negative number, which contributes nothing. */
    public boolean hasNegativeUnit() {
        return isNegative(days) || isNegative(hours) || isNegative(minutes) || isNegative(seconds);
    }

    /** The whole span in milliseconds, every authored unit summed. Never negative. */
    public long totalMs() {
        return unit(days) * DAY_MS
                + unit(hours) * HOUR_MS
                + unit(minutes) * MINUTE_MS
                + unit(seconds) * SECOND_MS;
    }

    /** The whole span in whole seconds, rounded down. Never negative. */
    public long totalSeconds() {
        return totalMs() / SECOND_MS;
    }

    private static long unit(@Nullable Integer value) {
        return value == null || value.intValue() < 0 ? 0L : value.longValue();
    }

    private static boolean isNegative(@Nullable Integer value) {
        return value != null && value.intValue() < 0;
    }

    @Override
    public String toString() {
        return "DurationGroup[" + totalMs() + "ms]";
    }
}
