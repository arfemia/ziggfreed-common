package com.ziggfreed.common.calendar.asset;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.Locale;

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
 * A Window's {@code Rule}: how each year's days are worked out, chosen by {@code Type}.
 *
 * <pre>{@code
 * "Rule": { "Type": "Easter", "Before": 10, "After": 7 }
 * "Rule": { "Type": "Weekday", "Month": 11, "Weekday": "Thursday", "Nth": 4, "Before": 6, "After": 5 }
 * "Rule": { "Type": "Fixed", "Start": "11-20", "End": "12-01" }
 * }</pre>
 *
 * <p>Every leaf is {@code appendInherited}: a {@code Parent} child or a server owner's entry that names the
 * same {@code Type}, or none, changes only the leaves it writes; one naming another {@code Type} starts that
 * shape afresh, which is how an owner pins an event whose days move ({@code Fixed}). A Rule with no
 * {@code Type} and nothing to inherit one from cannot be read.
 *
 * <p>The union lives on this holder, outside the shapes' own class hierarchy, so whichever class loads
 * first the union finds every shape's codec already built.
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

    private WindowRules() {
    }

    /** One shape of Rule. */
    public abstract static class Rule {

        Rule() {
        }

        /** The yearly rule this shape authors, or null when a leaf it needs is missing or unreadable. */
        @Nullable
        public abstract YearRule toYearRule();
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
                .metadata(EditorSchema.oneOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
                        "Sunday"))
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
            DayOfWeek day = parsedWeekday();
            return day == null ? null : new YearRule.Weekday(Month.of(month), day, nth, beforeOrZero(), afterOrZero());
        }

        @Nullable
        private DayOfWeek parsedWeekday() {
            if (weekday == null || weekday.isBlank()) {
                return null;
            }
            try {
                return DayOfWeek.valueOf(weekday.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    /** The {@code Type}-chosen union of the three shapes; its schema advertises all three. */
    public static final CodecMapCodec<Rule> CODEC = new CodecMapCodec<Rule>(TYPE_KEY)
            .register(FIXED, Fixed.class, Fixed.CODEC)
            .register(EASTER, Easter.class, Easter.CODEC)
            .register(WEEKDAY, Weekday.class, Weekday.CODEC);
}
