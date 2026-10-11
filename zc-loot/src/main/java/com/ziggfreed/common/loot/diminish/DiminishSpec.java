package com.ziggfreed.common.loot.diminish;

import java.time.DayOfWeek;
import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.util.PeriodMath;

/**
 * How a repeated reward pays less the more often it is earned in one window: the diminishing returns a
 * boss's daily Claim, a dungeon's completion Claim or any other repeatable payout carries.
 *
 * <pre>{@code
 * "Diminish": { "Steps": [1, 0.5, 0], "Window": "Daily" }
 * }</pre>
 *
 * <p>{@code Steps} is the multiplier each clear in the window pays at, in order: the first clear of the
 * window pays the first step, the second the second, and every clear past the end pays the last step
 * again. {@code [1, 0]} pays the first clear in full and nothing after it; {@code [1, 0.5]} pays half
 * for every clear after the first. A quantity scaled to a fraction pays whole through the loot layer's
 * one rounding rule ({@code StochasticCount}), and a reward scaled to nothing is dropped from the Claim.
 * {@code Window} is when the count starts over: {@code Daily} at midnight UTC, {@code Weekly} at
 * midnight UTC each Monday.
 *
 * <p>A nested group with nullable leaves, so a file that says nothing pays every clear in full on a
 * daily count; what counts the clears is {@link DiminishLedger}, read only through the claim store.
 */
public final class DiminishSpec {

    /** The window a count starts over in. A closed vocabulary: the two words the codec reads. */
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

    /** What an unauthored {@code Steps} pays: every clear in full. */
    private static final double[] FULL = {1.0};

    @Nullable protected double[] steps;
    @Nullable protected Window window;

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
                            (o, v) -> o.window = window(v),
                            o -> o.window == null ? null : o.window.word())
                    .metadata(EditorSchema.oneOfDocumented(
                            Window.DAILY.word(), "The count starts over at midnight UTC every day.",
                            Window.WEEKLY.word(), "The count starts over at midnight UTC every Monday."))
                    .metadata(EditorSchema.defaultValue(Window.DAILY.word()))
                    .documentation("When the count starts over: Daily or Weekly. Unauthored reads Daily. Any other "
                            + "word fails the read.")
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

    /** The window the count starts over in; Daily when none was written. */
    @Nonnull
    public Window window() {
        return window == null ? Window.DAILY : window;
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

    /** The word as one of {@link Window}; null stays null (unauthored), any other word fails the read. */
    @Nullable
    static Window window(@Nullable String word) {
        if (word == null) {
            return null;
        }
        Window window = Window.byWord(word);
        if (window == null) {
            throw new IllegalArgumentException("Window must be one of " + Window.DAILY.word() + " / "
                    + Window.WEEKLY.word() + ", not '" + word + "'");
        }
        return window;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DiminishSpec that && window() == that.window()
                && Arrays.equals(steps(), that.steps());
    }

    @Override
    public int hashCode() {
        return 31 * window().hashCode() + Arrays.hashCode(steps());
    }

    @Override
    public String toString() {
        return "DiminishSpec{steps=" + Arrays.toString(steps()) + ", window=" + window().word() + "}";
    }
}
