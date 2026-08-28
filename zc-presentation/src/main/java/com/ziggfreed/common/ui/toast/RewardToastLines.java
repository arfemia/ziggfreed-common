package com.ziggfreed.common.ui.toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The ONE bridge from the reward-chip reading to toast body rows, so a payout's toast and the
 * panel that previewed it can never disagree about what was handed over.
 *
 * <p>Every rung of naming a reward already lives in {@link RewardChips} (a reward's own words, the
 * kind file's {@code Presentation}, the item form, a kind owner's contributed reading); this class
 * adds only what a TOAST needs on top: the {@link ToastRenderer#MAX_LINES} row cap, and the option
 * to spend the last row on an "and N more" line instead of silently cutting the list.
 *
 * <p><b>Icons carry quantity one.</b> A chip's own label already says how many ("+500 Mining XP",
 * "x3 Iron Bar"), so a quantity on the icon would say it twice - the same reason the book surfaces
 * paint every chip's icon as a single item.
 *
 * <p>The overflow line is the CALLER's because its words belong to the caller's own lang file; a
 * null supplier simply drops the rows past the cap. Each entry point also takes a {@code maxRows}
 * form for a caller with a budget NARROWER than the panel's own (an authored per-moment cap): at
 * most that many reward rows show, and a longer list folds the rest into the overflow line as one
 * more row, still inside the panel.
 */
public final class RewardToastLines {

    private RewardToastLines() {
    }

    /**
     * One toast row per readable reward, in authored order, capped. {@code source} is the caller's
     * own per-consumer reading, asked first for each reward exactly as on every chip surface; null
     * takes the generic reading alone.
     */
    @Nonnull
    public static List<ToastLine> lines(@Nonnull List<RewardSpec> rewards,
            @Nullable RewardChips.Source source, @Nullable IntFunction<Message> overflow) {
        return lines(rewards, source, ToastRenderer.MAX_LINES, overflow);
    }

    /** {@link #lines(List, RewardChips.Source, IntFunction)} under a narrower row budget. */
    @Nonnull
    public static List<ToastLine> lines(@Nonnull List<RewardSpec> rewards,
            @Nullable RewardChips.Source source, int maxRows,
            @Nullable IntFunction<Message> overflow) {
        return fromChips(RewardChips.chipsFor(rewards, source), maxRows, overflow);
    }

    /** Rows for chips a surface already read, capped the same way. */
    @Nonnull
    public static List<ToastLine> fromChips(@Nonnull List<RewardChip> chips,
            @Nullable IntFunction<Message> overflow) {
        return fromChips(chips, ToastRenderer.MAX_LINES, overflow);
    }

    /** {@link #fromChips(List, IntFunction)} under a narrower row budget. */
    @Nonnull
    public static List<ToastLine> fromChips(@Nonnull List<RewardChip> chips, int maxRows,
            @Nullable IntFunction<Message> overflow) {
        List<ToastLine> lines = new ArrayList<>(chips.size());
        for (RewardChip chip : chips) {
            if (chip == null) {
                continue;
            }
            lines.add(chip.hasIcon()
                    ? ToastLine.item(chip.iconItemId(), 1, chip.label())
                    : ToastLine.text(chip.label()));
        }
        return cap(lines, maxRows, overflow);
    }

    /**
     * {@code lines} capped to {@link ToastRenderer#MAX_LINES}: a list that fits is returned as is,
     * a longer one keeps its first {@code MAX_LINES - 1} rows and spends the last on
     * {@code overflow.apply(dropped)} - or simply truncates when {@code overflow} is null.
     */
    @Nonnull
    public static List<ToastLine> cap(@Nonnull List<ToastLine> lines,
            @Nullable IntFunction<Message> overflow) {
        return cap(lines, ToastRenderer.MAX_LINES, overflow);
    }

    /**
     * {@link #cap(List, IntFunction)} under a caller's own budget: at most {@code maxRows} reward
     * rows show (never more than the panel's {@link ToastRenderer#MAX_LINES}, and never fewer than
     * one). A list that fits is returned as is; a longer one keeps its first rows and adds
     * {@code overflow.apply(dropped)} as ONE more row - still inside the panel - or simply
     * truncates to the budget when {@code overflow} is null.
     */
    @Nonnull
    public static List<ToastLine> cap(@Nonnull List<ToastLine> lines, int maxRows,
            @Nullable IntFunction<Message> overflow) {
        int allowed = Math.max(1, Math.min(maxRows, ToastRenderer.MAX_LINES));
        if (lines.size() <= allowed) {
            return lines;
        }
        if (overflow == null) {
            return new ArrayList<>(lines.subList(0, allowed));
        }
        int shown = Math.min(allowed, ToastRenderer.MAX_LINES - 1);
        List<ToastLine> capped = new ArrayList<>(lines.subList(0, shown));
        capped.add(ToastLine.text(overflow.apply(lines.size() - shown)));
        return capped;
    }
}
