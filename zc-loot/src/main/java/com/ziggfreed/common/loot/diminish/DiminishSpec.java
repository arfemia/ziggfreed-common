package com.ziggfreed.common.loot.diminish;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.util.PeriodMath;

/**
 * How a repeated reward pays less the more often it is earned in one window: the diminishing returns a
 * boss's daily Claim, a dungeon's completion Claim, a track source's moments or any other repeatable payout
 * carries.
 *
 * <pre>{@code
 * "Diminish": { "Steps": [1, 0.5, 0], "Window": "Daily" }
 * "Diminish": { "Steps": [1, 1, 0.5, 0], "Window": "10m", "Key": "Cell16" }
 * }</pre>
 *
 * <p>{@code Steps} is the multiplier each clear in the window pays at, in order: the first clear of the
 * window pays the first step, the second the second, and every clear past the end pays the last step
 * again. {@code [1, 0]} pays the first clear in full and nothing after it; {@code [1, 0.5]} pays half
 * for every clear after the first. A quantity scaled to a fraction pays whole through the loot layer's
 * one rounding rule ({@code StochasticCount}), and a reward scaled to nothing is dropped from the Claim.
 * {@code Window} is when the count starts over: {@code Daily} at midnight UTC, {@code Weekly} at
 * midnight UTC each Monday, or a rolling span ({@code 90s}, {@code 10m}, {@code 2h}, {@code 1d}): only the
 * clears inside the last span count, each falling out of it as its own time passes.
 *
 * <p>{@code Key} names a diminishing-returns key file (zc-track's {@code DiminishKeys} store: the target, a
 * cell of the world, the station, the exact spot, the victim, or any mix of those a pack writes), so a track
 * source counts its moments per thing they happened to rather than all together. A payout that keys its own
 * count (a Claim, a reward chest, a dungeon's clear) does not read it.
 *
 * <p>A nested group with nullable leaves, so a file that says nothing pays every clear in full on a
 * daily count; what counts the clears is {@link DiminishLedger}.
 */
public final class DiminishSpec {

    /** The calendar window a count starts over in. A closed vocabulary: the two words the codec reads. */
    public enum Window {
        /** Midnight UTC, every day. */
        DAILY("Daily", PeriodMath.DAY_MS, 0L),
        /** Midnight UTC, every Monday. */
        WEEKLY("Weekly", PeriodMath.WEEK_MS, PeriodMath.weekdayAnchorMs(DayOfWeek.MONDAY));

        private final String word;
        private final long lengthMs;
        private final long anchorMs;

        Window(@Nonnull String word, long lengthMs, long anchorMs) {
            this.word = word;
            this.lengthMs = lengthMs;
            this.anchorMs = anchorMs;
        }

        /** The word a file writes for this window. */
        @Nonnull
        public String word() {
            return word;
        }

        /** Which window {@code nowMs} falls in, on the library's one repeating-window authority. */
        public long periodIndex(long nowMs) {
            return PeriodMath.periodIndex(lengthMs, anchorMs, nowMs);
        }

        /** The window {@code word} names, any case, or null for a word that names none. */
        @Nullable
        public static Window byWord(@Nullable String word) {
            if (word == null) {
                return null;
            }
            String trimmed = word.trim();
            for (Window window : values()) {
                if (window.word.equalsIgnoreCase(trimmed)) {
                    return window;
                }
            }
            return null;
        }
    }

    /**
     * The editor's suggestions for {@code Window} (a text box with a pick list, since a rolling span is any
     * length): the two calendar words and a few spans. Served by zc-track's bootstrap.
     */
    public static final String WINDOW_DATA_SET = "ziggfreedcommon:diminish_windows";

    /** The words {@link #WINDOW_DATA_SET} offers. */
    public static final String[] SUGGESTED_WINDOWS = {"Daily", "Weekly", "10m", "1h", "24h"};

    /** The editor's type name of the key files ({@code Key}): zc-track's store, named without an edge to it. */
    public static final String KEY_ASSET_TYPE = "DiminishKeyAsset";

    /** The shortest rolling span a file may write. */
    public static final long MIN_ROLLING_MS = PeriodMath.SECOND_MS;

    /** What an unauthored {@code Steps} pays: every clear in full. */
    private static final double[] FULL = {1.0};

    @Nullable protected double[] steps;
    @Nullable protected Window window;
    /** A rolling span in milliseconds, or 0 for a calendar window. */
    protected long rollingMs;
    @Nullable protected String key;

    public static final BuilderCodec<DiminishSpec> CODEC =
            BuilderCodec.builder(DiminishSpec.class, DiminishSpec::new)
                    .append(new KeyedCodec<>("Steps", Codec.DOUBLE_ARRAY, false),
                            (o, v) -> o.steps = v == null ? null : v.clone(),
                            o -> o.steps == null ? null : o.steps.clone())
                    .documentation("The multiplier each clear in the window pays at, in order; every clear past "
                            + "the end pays the last one again. [1, 0] pays the first clear in full and nothing "
                            + "after it; [1, 0.5] pays half for every clear after the first. A negative step reads "
                            + "as 0. Unauthored, or empty, pays every clear in full.")
                    .add()
                    .append(new KeyedCodec<>("Window", Codec.STRING, false),
                            (o, v) -> o.readWindow(v),
                            DiminishSpec::writtenWindow)
                    .metadata(EditorSchema.suggest(WINDOW_DATA_SET))
                    .metadata(EditorSchema.defaultValue(Window.DAILY.word()))
                    .documentation("When the count starts over: Daily (midnight UTC), Weekly (midnight UTC each "
                            + "Monday), or a rolling span written as a number and s, m, h or d (90s, 10m, 2h, 1d), "
                            + "where only the clears inside the last span count. Unauthored reads Daily. Any other "
                            + "word fails the read.")
                    .add()
                    .append(new KeyedCodec<>("Key", Codec.STRING, false),
                            (o, v) -> o.key = v == null || v.isBlank() ? null : v.trim(),
                            o -> o.key)
                    .metadata(EditorSchema.assetRef(KEY_ASSET_TYPE))
                    .documentation("What a track source counts its moments per, by the id of a diminishing-returns "
                            + "key file (Cell16, Position, Target, Station, Victim, or one a pack ships): each value "
                            + "of the key keeps its own count, so a Position key pays each spot apart. Unauthored, "
                            + "every moment of the source counts together. A Claim, a chest or a dungeon clear keys "
                            + "its own count and does not read it.")
                    .add()
                    .build();

    public DiminishSpec() {
    }

    /** Java-side construction: {@code steps} in clear order, over {@code window} (null reads Daily). */
    @Nonnull
    public static DiminishSpec of(@Nullable Window window, @Nonnull double... steps) {
        DiminishSpec spec = new DiminishSpec();
        spec.window = window;
        spec.steps = steps.clone();
        return spec;
    }

    /** Java-side construction: {@code steps} in clear order, over a rolling span of {@code spanMs} (held at 1 s). */
    @Nonnull
    public static DiminishSpec rolling(long spanMs, @Nonnull double... steps) {
        DiminishSpec spec = new DiminishSpec();
        spec.rollingMs = Math.max(MIN_ROLLING_MS, spanMs);
        spec.steps = steps.clone();
        return spec;
    }

    /** This spec counted per the key file {@code keyId} (null or blank for none); the rest as it is. */
    @Nonnull
    public DiminishSpec withKey(@Nullable String keyId) {
        DiminishSpec copy = new DiminishSpec();
        copy.steps = steps == null ? null : steps.clone();
        copy.window = window;
        copy.rollingMs = rollingMs;
        copy.key = keyId == null || keyId.isBlank() ? null : keyId.trim();
        return copy;
    }

    /**
     * The calendar window the count starts over in; Daily when none was written. A rolling spec
     * ({@link #isRolling}) counts over its span instead and answers Daily here.
     */
    @Nonnull
    public Window window() {
        return window == null ? Window.DAILY : window;
    }

    /** Does the count run over a rolling span ({@link #rollingMs}) rather than a calendar window? */
    public boolean isRolling() {
        return rollingMs > 0L;
    }

    /** The rolling span in milliseconds, or 0 for a calendar window. */
    public long rollingMs() {
        return Math.max(0L, rollingMs);
    }

    /**
     * The window as one word, the way a count records it: a calendar window's word, or the rolling span in its
     * largest whole unit ({@code 10m}, {@code 2h}).
     */
    @Nonnull
    public String windowWord() {
        return isRolling() ? spanWord(rollingMs) : window().word();
    }

    /** The key file's id as written, trimmed, or null when the source counts every moment together. */
    @Nullable
    public String key() {
        return key;
    }

    /** The steps as written (a copy), or every clear in full ({@code [1]}) when none were. */
    @Nonnull
    public double[] steps() {
        return steps == null || steps.length == 0 ? FULL.clone() : steps.clone();
    }

    /**
     * The multiplier the next clear pays at, after {@code priorClears} clears already counted in this window:
     * that step, or the last one past the end. A negative or unreadable step pays nothing.
     */
    public double multiplier(int priorClears) {
        double[] read = steps();
        double step = read[Math.min(Math.max(0, priorClears), read.length - 1)];
        return Double.isFinite(step) && step > 0.0 ? step : 0.0;
    }

    /** Read the written word: a calendar word, a rolling span, or a refusal; null stays unauthored. */
    private void readWindow(@Nullable String word) {
        window = null;
        rollingMs = 0L;
        if (word == null) {
            return;
        }
        Window calendar = Window.byWord(word);
        if (calendar != null) {
            window = calendar;
            return;
        }
        long span = spanMs(word);
        if (span <= 0L) {
            throw new IllegalArgumentException("Window must be " + Window.DAILY.word() + ", "
                    + Window.WEEKLY.word() + " or a span such as 10m, not '" + word + "'");
        }
        rollingMs = span;
    }

    /** The word written back: the rolling span's, the calendar window's, or null when none was authored. */
    @Nullable
    private String writtenWindow() {
        if (isRolling()) {
            return spanWord(rollingMs);
        }
        return window == null ? null : window.word();
    }

    /**
     * A rolling span's length: a whole number and one unit ({@code s}, {@code m}, {@code h}, {@code d}, any case,
     * spaces allowed between), at least {@link #MIN_ROLLING_MS}; 0 for anything else. Pure.
     */
    public static long spanMs(@Nullable String word) {
        if (word == null) {
            return 0L;
        }
        String text = word.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.length() < 2) {
            return 0L;
        }
        long unit = switch (text.charAt(text.length() - 1)) {
            case 's' -> PeriodMath.SECOND_MS;
            case 'm' -> PeriodMath.MINUTE_MS;
            case 'h' -> PeriodMath.HOUR_MS;
            case 'd' -> PeriodMath.DAY_MS;
            default -> 0L;
        };
        if (unit == 0L) {
            return 0L;
        }
        try {
            long count = Long.parseLong(text.substring(0, text.length() - 1));
            if (count <= 0L || count > Long.MAX_VALUE / unit) {
                return 0L;
            }
            long span = count * unit;
            return span >= MIN_ROLLING_MS ? span : 0L;
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /** A span written in its largest whole unit: {@code 600000} is {@code 10m}. Pure. */
    @Nonnull
    public static String spanWord(long spanMs) {
        long span = Math.max(MIN_ROLLING_MS, spanMs);
        if (span % PeriodMath.DAY_MS == 0L) {
            return (span / PeriodMath.DAY_MS) + "d";
        }
        if (span % PeriodMath.HOUR_MS == 0L) {
            return (span / PeriodMath.HOUR_MS) + "h";
        }
        if (span % PeriodMath.MINUTE_MS == 0L) {
            return (span / PeriodMath.MINUTE_MS) + "m";
        }
        return Math.max(1L, span / PeriodMath.SECOND_MS) + "s";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DiminishSpec that && window() == that.window() && rollingMs() == that.rollingMs()
                && Arrays.equals(steps(), that.steps())
                && Objects.equals(foldedKey(), that.foldedKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(window(), rollingMs(), Arrays.hashCode(steps()), foldedKey());
    }

    @Nullable
    private String foldedKey() {
        return key == null ? null : key.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "DiminishSpec{steps=" + Arrays.toString(steps()) + ", window=" + windowWord()
                + (key == null ? "" : ", key=" + key) + "}";
    }
}
