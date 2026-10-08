package com.ziggfreed.common.calendar.asset;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.lookup.CodecMapCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.calendar.AnnualWindow;
import com.ziggfreed.common.calendar.YearRule;

/**
 * A Window's {@code Rule}: how each year's runs are worked out, chosen by {@code Type}.
 *
 * <pre>{@code
 * "Rule": { "Type": "Easter", "Before": 10, "After": 7 }
 * "Rule": { "Type": "Weekday", "Month": 11, "Weekday": "Thursday", "Nth": 4, "Before": 6, "After": 5 }
 * "Rule": { "Type": "Fixed", "Start": "11-20", "End": "12-01" }
 * "Rule": { "Type": "Monthly", "Weekday": "Sunday", "Nth": 1, "Days": 7 }
 * "Rule": { "Type": "Monthly", "Day": 1, "UntilNext": true }
 * "Rule": { "Type": "Monthly", "Day": 15, "Days": 2, "Months": [3, 6, 9, 12] }
 * "Rule": { "Type": "Monthly", "Day": 1, "Days": 7, "Every": 2, "Anchor": "2026-02-01" }
 * "Rule": { "Type": "Weekly", "Weekday": "Sunday", "At": "14:00", "Length": "PT2H" }
 * "Rule": { "Type": "Weekly", "Weekday": "Saturday", "Length": "PT24H" }
 * "Rule": { "Type": "Weekly", "Weekday": "Monday", "Days": 7, "Every": 7, "Anchor": "2026-01-05" }
 * }</pre>
 *
 * <p>Every leaf is {@code appendInherited}: a {@code Parent} child or a server owner's entry that names the
 * same {@code Type}, or none, changes only the leaves it writes; one naming another {@code Type} starts that
 * shape afresh, which is how an owner pins an event whose days move ({@code Fixed}). A Rule with no
 * {@code Type} and nothing to inherit one from cannot be read.
 *
 * <p>The union lives on this holder, outside the shapes' own class hierarchy, so whichever class loads
 * first the union finds every shape's codec already built. A shape's codec reads only its own class and its
 * bases ({@link Rule#WEEKDAYS}), never this holder.
 */
public final class WindowRules {

    /** The discriminator key. */
    public static final String TYPE_KEY = "Type";

    /** The same month-days every year. */
    public static final String FIXED = "Fixed";

    /** Days around Easter Sunday. */
    public static final String EASTER = "Easter";

    /** Days around the Nth weekday of a month. */
    public static final String WEEKDAY = "Weekday";

    /** A run each month. */
    public static final String MONTHLY = "Monthly";

    /** A run each week. */
    public static final String WEEKLY = "Weekly";

    private WindowRules() {
    }

    /** One shape of Rule. */
    public abstract static class Rule {

        /** The weekday names a {@code Weekday} leaf offers, Monday first. */
        static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

        Rule() {
        }

        /** The yearly rule this shape authors, or null when a leaf it needs is missing or unreadable. */
        @Nullable
        public abstract YearRule toYearRule();

        /** A weekday name as a day of the week, any casing; null for a blank or unknown one. */
        @Nullable
        static DayOfWeek dayOfWeek(@Nullable String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                return DayOfWeek.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }

        /** {@code HH:MM} as a time of day; null for an unreadable one. */
        @Nullable
        static LocalTime timeOfDay(@Nonnull String text) {
            try {
                return LocalTime.parse(text.trim());
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }

        /** An ISO-8601 duration ({@code PT2H}); null for an unreadable one. */
        @Nullable
        static Duration duration(@Nonnull String text) {
            try {
                return Duration.parse(text.trim().toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }

        /** {@code YYYY-MM-DD} as a date; null for an unreadable one. */
        @Nullable
        static LocalDate date(@Nonnull String text) {
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
    }

    /** {@code Start} and {@code End} as {@code MM-DD}, the same every year; also the shape of one {@code Years} entry. */
    public static final class Fixed extends Rule {

        @Nullable private String start;
        @Nullable private String end;

        public static final BuilderCodec<Fixed> CODEC = BuilderCodec.builder(Fixed.class, Fixed::new)
                .appendInherited(new KeyedCodec<>("Start", Codec.STRING, false),
                        (o, v) -> o.start = v, o -> o.start, (o, p) -> o.start = p.start)
                .documentation("The first day, as MM-DD (11-20 is November 20th).").add()
                .appendInherited(new KeyedCodec<>("End", Codec.STRING, false),
                        (o, v) -> o.end = v, o -> o.end, (o, p) -> o.end = p.end)
                .documentation("The last day, as MM-DD; the run goes through the whole of it. An End before its "
                        + "Start runs into the next year.").add()
                .build();

        public Fixed() {
        }

        /** The two days, or null when either is not a real MM-DD day. */
        @Nullable
        public YearRule.Fixed toFixed() {
            return AnnualWindow.fixed(start, end);
        }

        @Override
        @Nullable
        public YearRule toYearRule() {
            return toFixed();
        }
    }

    /** {@code Before} and {@code After}: the two leaves both moving shapes share. */
    abstract static class Shifted extends Rule {

        @Nullable Integer before;
        @Nullable Integer after;

        int beforeOrZero() {
            return before == null ? 0 : before;
        }

        int afterOrZero() {
            return after == null ? 0 : after;
        }

        /** {@code builder} with the two shift leaves appended, each documented against {@code anchor}. */
        @Nonnull
        static <S extends Shifted> BuilderCodec.Builder<S> withShifts(@Nonnull BuilderCodec.Builder<S> builder,
                @Nonnull String anchor) {
            return builder
                    .appendInherited(new KeyedCodec<>("Before", Codec.INTEGER, false),
                            (o, v) -> o.before = v, o -> o.before, (o, p) -> o.before = p.before)
                    .metadata(EditorSchema.defaultValue(0L))
                    .documentation("How many days before " + anchor + " the event starts. Unauthored means 0, so "
                            + "it starts on that day.").add()
                    .appendInherited(new KeyedCodec<>("After", Codec.INTEGER, false),
                            (o, v) -> o.after = v, o -> o.after, (o, p) -> o.after = p.after)
                    .metadata(EditorSchema.defaultValue(0L))
                    .documentation("How many days after " + anchor + " the event's last day falls. Unauthored "
                            + "means 0, so that day is its last.").add();
        }
    }

    /** Days around Easter Sunday, worked out for every year. */
    public static final class Easter extends Shifted {

        public static final BuilderCodec<Easter> CODEC =
                withShifts(BuilderCodec.builder(Easter.class, Easter::new), "Easter Sunday").build();

        public Easter() {
        }

        @Override
        @Nonnull
        public YearRule toYearRule() {
            return new YearRule.Easter(beforeOrZero(), afterOrZero());
        }
    }

    /** Days around the Nth weekday of a month. */
    public static final class Weekday extends Shifted {

        @Nullable private Integer month;
        @Nullable private String weekday;
        @Nullable private Integer nth;

        public static final BuilderCodec<Weekday> CODEC = withShifts(BuilderCodec.builder(Weekday.class, Weekday::new)
                .appendInherited(new KeyedCodec<>("Month", Codec.INTEGER, false),
                        (o, v) -> o.month = v, o -> o.month, (o, p) -> o.month = p.month)
                .documentation("The month the weekday is counted in, 1 to 12 (11 is November).").add()
                .appendInherited(new KeyedCodec<>("Weekday", Codec.STRING, false),
                        (o, v) -> o.weekday = v, o -> o.weekday, (o, p) -> o.weekday = p.weekday)
                .metadata(EditorSchema.oneOf(WEEKDAYS))
                .documentation("Which day of the week is counted (Monday, Tuesday, ...).").add()
                .appendInherited(new KeyedCodec<>("Nth", Codec.INTEGER, false),
                        (o, v) -> o.nth = v, o -> o.nth, (o, p) -> o.nth = p.nth)
                .documentation("Which one of them in the month: 1 to 5, or -1 for the last. A fifth the month does "
                        + "not have falls back to the fourth.").add(), "that day").build();

        public Weekday() {
        }

        @Override
        @Nullable
        public YearRule toYearRule() {
            if (month == null || month < 1 || month > 12 || nth == null) {
                return null;
            }
            DayOfWeek day = dayOfWeek(weekday);
            return day == null ? null : new YearRule.Weekday(Month.of(month), day, nth, beforeOrZero(), afterOrZero());
        }
    }

    /** The leaves both repeating shapes share: how long a run lasts, how often, and in which months. */
    abstract static class Repeating extends Rule {

        @Nullable Integer days;
        @Nullable String at;
        @Nullable String length;
        @Nullable Boolean untilNext;
        @Nullable Integer every;
        @Nullable String anchor;
        @Nullable int[] months;

        /** The run length authored, or null when its At or Length cannot be read. */
        @Nullable
        YearRule.RunLength toLength() {
            LocalTime start = at == null ? null : timeOfDay(at);
            Duration hours = length == null ? null : duration(length);
            if ((at != null && start == null) || (length != null && hours == null)) {
                return null;
            }
            return new YearRule.RunLength(days == null ? 1 : days, start, hours, untilNext != null && untilNext);
        }

        /** The cadence authored, or null when a month is not 1 to 12, the Anchor cannot be read, or Every skips with no Anchor. */
        @Nullable
        YearRule.Cadence toCadence() {
            Set<Month> in = EnumSet.noneOf(Month.class);
            if (months == null) {
                in.addAll(YearRule.EVERY_MONTH);
            } else {
                for (int month : months) {
                    if (month < 1 || month > 12) {
                        return null;
                    }
                    in.add(Month.of(month));
                }
            }
            LocalDate from = anchor == null ? null : date(anchor);
            int n = every == null ? 1 : every;
            if ((anchor != null && from == null) || (n > 1 && from == null)) {
                return null;
            }
            return new YearRule.Cadence(n, from, in);
        }

        /** {@code builder} with the repeat leaves appended; a run lasts at most {@code maxDays} days per Every. */
        @Nonnull
        static <S extends Repeating> BuilderCodec.Builder<S> withRepeat(@Nonnull BuilderCodec.Builder<S> builder,
                int maxDays, @Nonnull String unit) {
            return builder
                    .appendInherited(new KeyedCodec<>("Days", Codec.INTEGER, false),
                            (o, v) -> o.days = v, o -> o.days, (o, p) -> o.days = p.days)
                    .metadata(EditorSchema.defaultValue(1L))
                    .documentation("How many whole days each run lasts, its first day counted: 1 to " + maxDays
                            + " for each " + unit + " Every spans. With At, the run starts and ends at that time. "
                            + "Unauthored means 1.").add()
                    .appendInherited(new KeyedCodec<>("At", Codec.STRING, false),
                            (o, v) -> o.at = v, o -> o.at, (o, p) -> o.at = p.at)
                    .documentation("The time of day each run starts, as HH:MM on the event's Clock (14:00). "
                            + "Unauthored means midnight.").add()
                    .appendInherited(new KeyedCodec<>("Length", Codec.STRING, false),
                            (o, v) -> o.length = v, o -> o.length, (o, p) -> o.length = p.length)
                    .documentation("How long each run lasts from its At time, as an ISO-8601 duration (PT2H is two "
                            + "hours, PT24H a day); wins over Days. A run belongs to the year it starts in.").add()
                    .appendInherited(new KeyedCodec<>("UntilNext", Codec.BOOLEAN, false),
                            (o, v) -> o.untilNext = v, o -> o.untilNext, (o, p) -> o.untilNext = p.untilNext)
                    .metadata(EditorSchema.defaultValue(false))
                    .documentation("Each run lasts until the next one starts (a run on the 1st that fills its "
                            + "month); wins over Days and Length. Only with no Months, and Every at most a year.")
                    .add()
                    .appendInherited(new KeyedCodec<>("Every", Codec.INTEGER, false),
                            (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                    .metadata(EditorSchema.defaultValue(1L))
                    .documentation("Run every Nth " + unit + " instead of every one (2 is every other), counted from "
                            + "the Anchor's " + unit + ", which it needs past 1. Unauthored means 1.").add()
                    .appendInherited(new KeyedCodec<>("Anchor", Codec.STRING, false),
                            (o, v) -> o.anchor = v, o -> o.anchor, (o, p) -> o.anchor = p.anchor)
                    .documentation("A date as YYYY-MM-DD inside a " + unit + " the event runs in, which Every counts "
                            + "from. Two events with the same Every and anchors one " + unit + " apart take turns.")
                    .add()
                    .appendInherited(new KeyedCodec<>("Months", Codec.INT_ARRAY, false),
                            (o, v) -> o.months = v, o -> o.months, (o, p) -> o.months = p.months)
                    .documentation("The months it runs in, as numbers 1 to 12 ([6, 7, 8] is summer), by the month a "
                            + "run starts in. Unauthored means every month.").add();
        }
    }

    /**
     * A run each month, from a day of the month or from the Nth weekday of it. Each run is numbered by its month
     * (December's is 12), so adding or dropping Months, or another day or weekday of the month, never renumbers
     * one.
     */
    public static final class Monthly extends Repeating {

        @Nullable private Integer day;
        @Nullable private String weekday;
        @Nullable private Integer nth;

        public static final BuilderCodec<Monthly> CODEC = withRepeat(BuilderCodec.builder(Monthly.class, Monthly::new)
                .appendInherited(new KeyedCodec<>("Day", Codec.INTEGER, false),
                        (o, v) -> o.day = v, o -> o.day, (o, p) -> o.day = p.day)
                .documentation("The day of the month each run starts on, 1 to 31; a day the month does not have "
                        + "falls back to its last (31 is every month's last day). Not used when a Weekday is named.")
                .add()
                .appendInherited(new KeyedCodec<>("Weekday", Codec.STRING, false),
                        (o, v) -> o.weekday = v, o -> o.weekday, (o, p) -> o.weekday = p.weekday)
                .metadata(EditorSchema.oneOf(WEEKDAYS))
                .documentation("Start each run on the Nth of this weekday in the month instead of on a Day "
                        + "(Sunday with Nth 1 is the first Sunday of every month).").add()
                .appendInherited(new KeyedCodec<>("Nth", Codec.INTEGER, false),
                        (o, v) -> o.nth = v, o -> o.nth, (o, p) -> o.nth = p.nth)
                .documentation("Which of that weekday in the month: 1 to 5, or -1 for the last. A fifth the month "
                        + "does not have falls back to the fourth.").add(),
                YearRule.MONTHLY_MAX_DAYS, "month").build();

        public Monthly() {
        }

        @Override
        @Nullable
        public YearRule toYearRule() {
            YearRule.Cadence cadence = toCadence();
            YearRule.RunLength runLength = toLength();
            if (cadence == null || runLength == null) {
                return null;
            }
            if (weekday != null) {
                DayOfWeek named = dayOfWeek(weekday);
                return named == null || nth == null ? null
                        : new YearRule.Monthly(0, named, nth, cadence, runLength);
            }
            return day == null ? null : new YearRule.Monthly(day, null, 0, cadence, runLength);
        }
    }

    /**
     * A run each week, on one weekday. Each run is numbered by its calendar week (Monday to Sunday, week 1 the one
     * holding January 1st), so the weeks Every or Months skip leave gaps and adding or dropping weeks never
     * renumbers the others; another Weekday can move a run into a neighbouring week, and so change its number.
     */
    public static final class Weekly extends Repeating {

        @Nullable private String weekday;

        public static final BuilderCodec<Weekly> CODEC = withRepeat(BuilderCodec.builder(Weekly.class, Weekly::new)
                .appendInherited(new KeyedCodec<>("Weekday", Codec.STRING, false),
                        (o, v) -> o.weekday = v, o -> o.weekday, (o, p) -> o.weekday = p.weekday)
                .metadata(EditorSchema.oneOf(WEEKDAYS))
                .documentation("The day of the week each run starts on.").add(),
                YearRule.WEEKLY_MAX_DAYS, "week").build();

        public Weekly() {
        }

        @Override
        @Nullable
        public YearRule toYearRule() {
            YearRule.Cadence cadence = toCadence();
            YearRule.RunLength runLength = toLength();
            DayOfWeek named = dayOfWeek(weekday);
            return cadence == null || runLength == null || named == null ? null
                    : new YearRule.Weekly(named, cadence, runLength);
        }
    }

    /** The {@code Type}-chosen union of the five shapes; its schema advertises all five. */
    public static final CodecMapCodec<Rule> CODEC = new CodecMapCodec<Rule>(TYPE_KEY)
            .register(FIXED, Fixed.class, Fixed.CODEC)
            .register(EASTER, Easter.class, Easter.CODEC)
            .register(WEEKDAY, Weekday.class, Weekday.CODEC)
            .register(MONTHLY, Monthly.class, Monthly.CODEC)
            .register(WEEKLY, Weekly.class, Weekly.CODEC);
}
