package com.ziggfreed.common.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The run sentinels, {@code @run} and {@code @year}: content that belongs to one run of a recurring calendar event
 * writes them where that run's label or year goes, and whatever knows the run answers them. {@code @run} reads as
 * the run's label (zc-core's {@code RunLabels}: the year it starts in, unless its event's kind labels its runs
 * another way) and {@code @year} as the year the run starts in. A quest that comes round once a run answers both
 * for the run it pays for; a yearly achievement copy stands for every run of its year, so both read its year.
 * This class is the one place the sentinels are recognised and written.
 *
 * <p>Only a WHOLE value is a sentinel (ignoring case and edge spaces): a text argument, or a reward parameter,
 * that reads exactly {@code @run} or {@code @year}. A value that merely contains one, and content no run owns,
 * keep it as written, like every unanswered sentinel.
 */
public final class RunSentinels {

    /** The sentinel for the run's label, as authored. */
    public static final String RUN = "@run";

    /** The sentinel for the year the run starts in, as authored. */
    public static final String YEAR = "@year";

    private RunSentinels() {
    }

    /** Text arguments with each whole-value sentinel answered by {@code year}, both reading the year. */
    @Nonnull
    public static List<String> answerArgs(@Nonnull List<String> authored, int year) {
        String yearText = String.valueOf(year);
        List<String> out = new ArrayList<>(authored.size());
        for (String arg : authored) {
            String answer = answer(arg, yearText, yearText);
            out.add(answer != null ? answer : arg);
        }
        return out;
    }

    /** {@link #answerRewards(List, int, String)} for content that stands for a whole year: both read the year. */
    @Nonnull
    public static List<RewardSpec> answerRewards(@Nonnull List<RewardSpec> authored, int year) {
        return answerRewards(authored, year, String.valueOf(year));
    }

    /**
     * Rewards with every parameter whose whole value is a sentinel answered: {@code @run} by {@code runLabel},
     * {@code @year} by {@code year}. A row with no such parameter comes back as the same object, so a list with
     * none reads equal to the authored one.
     */
    @Nonnull
    public static List<RewardSpec> answerRewards(@Nonnull List<RewardSpec> authored, int year,
            @Nonnull String runLabel) {
        String yearText = String.valueOf(year);
        List<RewardSpec> out = new ArrayList<>(authored.size());
        for (RewardSpec spec : authored) {
            RewardSpec answered = spec;
            for (Map.Entry<String, String> param : spec.params().entrySet()) {
                String answer = answer(param.getValue(), yearText, runLabel);
                if (answer != null) {
                    answered = answered.with(param.getKey(), answer);
                }
            }
            out.add(answered);
        }
        return out;
    }

    /** What {@code value} reads as when it is, as a whole, a sentinel; null when it is none. */
    @Nullable
    private static String answer(@Nullable String value, @Nonnull String year, @Nonnull String runLabel) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (RUN.equalsIgnoreCase(trimmed)) {
            return runLabel;
        }
        return YEAR.equalsIgnoreCase(trimmed) ? year : null;
    }
}
