package com.ziggfreed.common.commerce.asset;

import java.time.DayOfWeek;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.time.DurationGroup;

/**
 * HOW OFTEN a rotating set changes: on the calendar, or on a plain repeating span.
 *
 * <pre>{@code
 * "Rotation": { "Period": "Daily" }                      // midnight UTC, every day
 * "Rotation": { "Period": "Weekly", "Weekday": "Monday" } // Monday morning
 * "Rotation": { "Every": { "Hours": 2 } }                 // every two hours
 * "Rotation": { "Period": "Daily", "OffsetMinutes": 240 } // 04:00 rather than midnight
 * }</pre>
 *
 * <p><b>ONE schedule group, shared by every rotating thing here</b> - a board of contracts and a
 * storefront's featured shelf - so the two can never drift into disagreeing about what "daily"
 * means, and a new cadence reaches both at once.
 *
 * <p><b>{@code Period} and {@code Every} are two leaves, not a mode</b>: a calendar cadence rolls
 * over on a fixed boundary everybody shares (so every player's daily flips at the same instant),
 * while a span cadence simply repeats. Author ONE of them. Authoring both is a validator ERROR
 * rather than a silent precedence rule, because whichever one lost would be a number an author
 * believes is doing something.
 *
 * <p>Every leaf is {@code appendInherited}, so a file with a {@code Parent} can move the rollover
 * time and keep the cadence it did not mention.
 */
public final class RotationAsset {

    /** {@code Period} authored as a day. */
    public static final String PERIOD_DAILY = "Daily";

    /** {@code Period} authored as a week. */
    public static final String PERIOD_WEEKLY = "Weekly";

    @Nullable protected String period;
    @Nullable protected DurationGroup every;
    @Nullable protected Integer offsetMinutes;
    @Nullable protected String weekday;

    public static final BuilderCodec<RotationAsset> CODEC =
            BuilderCodec.builder(RotationAsset.class, RotationAsset::new)
                    .appendInherited(new KeyedCodec<>("Period", Codec.STRING, false),
                            (o, v) -> o.period = v, o -> o.period, (o, p) -> o.period = p.period)
                    .documentation("A calendar cadence: Daily or Weekly, counted from a fixed boundary on the "
                            + "server clock in UTC rather than from when anybody last looked, so everybody's "
                            + "rotation turns over at the same instant. Author this OR Every, never both.").add()
                    .appendInherited(new KeyedCodec<>("Every", DurationGroup.CODEC, false),
                            (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                    .documentation("A plain repeating span instead of a calendar cadence, in whole units that add "
                            + "up: {\"Hours\": 2} turns the set over every two hours. Author this OR Period, never "
                            + "both.").add()
                    .appendInherited(new KeyedCodec<>("OffsetMinutes", Codec.INTEGER, false),
                            (o, v) -> o.offsetMinutes = v, o -> o.offsetMinutes,
                            (o, p) -> o.offsetMinutes = p.offsetMinutes)
                    .documentation("How many minutes past the boundary the set turns over, on the server clock in "
                            + "UTC. Unauthored means on the boundary; 240 moves a daily to 04:00, which is how a "
                            + "server whose players share one part of the world stops it flipping mid-evening.").add()
                    .appendInherited(new KeyedCodec<>("Weekday", Codec.STRING, false),
                            (o, v) -> o.weekday = v, o -> o.weekday, (o, p) -> o.weekday = p.weekday)
                    .documentation("Which day a Weekly cadence starts on (Monday, Tuesday, ...). Unauthored means "
                            + "Monday. It does nothing on a Daily or an Every cadence.").add()
                    .build();

    public RotationAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static RotationAsset of(@Nullable String period, @Nullable DurationGroup every,
            @Nullable Integer offsetMinutes, @Nullable String weekday) {
        RotationAsset r = new RotationAsset();
        r.period = period;
        r.every = every;
        r.offsetMinutes = offsetMinutes;
        r.weekday = weekday;
        return r;
    }

    /** The authored calendar cadence exactly as written, unparsed; null when unauthored. */
    @Nullable
    public String getPeriod() {
        return period == null || period.isBlank() ? null : period.trim();
    }

    /** The authored span cadence, or null when the file names a calendar one (or neither). */
    @Nullable
    public DurationGroup getEvery() {
        return every == null || every.isEmpty() ? null : every;
    }

    /** The rollover offset in minutes; 0 when unauthored or authored negative. */
    public int offsetMinutes() {
        return offsetMinutes == null || offsetMinutes < 0 ? 0 : offsetMinutes;
    }

    /** The authored rollover offset exactly as written, for an audit that must see a bad one. */
    @Nullable
    public Integer getOffsetMinutes() {
        return offsetMinutes;
    }

    /** The authored weekday exactly as written, unparsed; null when unauthored. */
    @Nullable
    public String getWeekday() {
        return weekday == null || weekday.isBlank() ? null : weekday.trim();
    }

    /** The parsed weekday, Monday when unauthored, or null when the word is not a day name. */
    @Nullable
    public DayOfWeek parsedWeekday() {
        String authored = getWeekday();
        if (authored == null) {
            return DayOfWeek.MONDAY;
        }
        try {
            return DayOfWeek.valueOf(authored.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** True when a calendar cadence is authored as one of the two words this group knows. */
    public boolean isCalendar() {
        String authored = getPeriod();
        return authored != null
                && (PERIOD_DAILY.equalsIgnoreCase(authored) || PERIOD_WEEKLY.equalsIgnoreCase(authored));
    }

    /** True when a calendar cadence is authored as a week. */
    public boolean isWeekly() {
        String authored = getPeriod();
        return authored != null && PERIOD_WEEKLY.equalsIgnoreCase(authored);
    }

    /** True when a value was authored under {@code Period} that is neither word. */
    public boolean hasUnknownPeriod() {
        String authored = getPeriod();
        return authored != null && !isCalendar();
    }

    /** True when BOTH cadences are authored, which the audit refuses rather than resolving. */
    public boolean hasBothCadences() {
        return getPeriod() != null && getEvery() != null;
    }

    /** True when neither cadence is authored, so nothing ever turns the set over. */
    public boolean isEmpty() {
        return getPeriod() == null && getEvery() == null;
    }
}
