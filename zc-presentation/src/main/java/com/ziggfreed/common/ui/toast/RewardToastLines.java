package com.ziggfreed.common.ui.toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
 * adds only what a TOAST needs on top: the lootable sections, the {@link ToastRenderer#MAX_LINES}
 * row cap, and the option to spend the last row on an "and N more" line instead of silently
 * cutting the list.
 *
 * <p><b>Lootable sections.</b> A receipt row resolved from an authored reward carries it as its
 * {@link RewardSpec#origin()} (a rolled table's items point at the {@code Lootable} that rolled
 * them). A receipt that is one lootable's rows alone, or that carries no such rows, reads flat, as
 * it always did. Anything else lists every row not from a lootable first, in receipt order, then
 * each lootable as a section: one heading row (the lootable reward's own reading: its
 * {@code NameKey} and {@code Icon}, else the {@code Lootable} kind file's) and its rows indented
 * beneath. A lootable that rolled nothing adds no section, and one whose reward nothing can name
 * shows its rows as plain rows, among those first ones in receipt order. Sections group CONTIGUOUS
 * rows of the same origin, by identity. This is the one sectioning step: a caller with a reading of
 * its own passes it to {@link #linesBy(List, Function, int, IntFunction)}, never a grouping of its
 * own.
 *
 * <p><b>Icons carry quantity one.</b> A chip's own label already says how many ("+500 Mining XP",
 * "x3 Iron Bar"), so a quantity on the icon would say it twice - the same reason the book surfaces
 * paint every chip's icon as a single item.
 *
 * <p>The overflow line is the CALLER's because its words belong to the caller's own lang file; a
 * null supplier simply drops the rows past the cap. Each entry point also takes a {@code maxRows}
 * form for a caller with a budget NARROWER than the panel's own (an authored per-moment cap): at
 * most that many rows show, except as below, and a longer list folds the rest into the overflow
 * line as one more row, still inside the panel. A heading counts against that budget but never ends
 * the list (a budget that would keep only the opening heading keeps its first row too, one row past
 * the budget), and the overflow line counts reward rows only.
 */
public final class RewardToastLines {

    // A section's least showing: its heading and that heading's first row.
    private static final int SECTION_FLOOR = 2;

    private RewardToastLines() {
    }

    /**
     * One toast row per readable reward, sectioned and capped. {@code source} is the caller's own
     * per-consumer reading, asked first for each reward exactly as on every chip surface; null takes
     * the generic reading alone.
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
        return linesBy(rewards, spec -> rowOf(RewardChips.chipFor(spec, source)), maxRows, overflow);
    }

    /**
     * The core form: one row per reward through the caller's own {@code row} reading (a null answer
     * drops that row; the same reading names a lootable section's heading), sectioned by origin and
     * capped to the panel's {@link ToastRenderer#MAX_LINES}. A consumer whose rows read differently
     * from a chip (a panel model of its own) passes its reading here, so the sections stay this
     * class's.
     */
    @Nonnull
    public static List<ToastLine> linesBy(@Nonnull List<RewardSpec> rewards,
            @Nonnull Function<RewardSpec, ToastLine> row, @Nullable IntFunction<Message> overflow) {
        return linesBy(rewards, row, ToastRenderer.MAX_LINES, overflow);
    }

    /** {@link #linesBy(List, Function, IntFunction)} under a narrower row budget. */
    @Nonnull
    public static List<ToastLine> linesBy(@Nonnull List<RewardSpec> rewards,
            @Nonnull Function<RewardSpec, ToastLine> row, int maxRows,
            @Nullable IntFunction<Message> overflow) {
        return cap(sectioned(rewards, row), maxRows, overflow);
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
            ToastLine line = rowOf(chip);
            if (line != null) {
                lines.add(line);
            }
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
     * {@link #cap(List, IntFunction)} under a caller's own budget: at most {@code maxRows} rows show
     * (never more than the panel's {@link ToastRenderer#MAX_LINES}, and never fewer than one), but for
     * the one exception below. A list
     * that fits is returned as is; a longer one keeps its first rows and adds
     * {@code overflow.apply(dropped)} as ONE more row - still inside the panel - or simply truncates
     * to the budget when {@code overflow} is null.
     *
     * <p>A {@link ToastLine#heading() heading} counts against the budget like any row, but the kept
     * rows never END on one: a heading cut from all its rows goes with them. The one exception is a
     * cut that would keep nothing at all (a budget of one falling on the list's opening heading):
     * that heading and its first row stay, so the toast still shows a reward rather than the
     * overflow line alone (the panel always holds the two of them and that line). {@code dropped}
     * counts the reward rows left out, never a heading.
     */
    @Nonnull
    public static List<ToastLine> cap(@Nonnull List<ToastLine> lines, int maxRows,
            @Nullable IntFunction<Message> overflow) {
        int allowed = Math.max(1, Math.min(maxRows, ToastRenderer.MAX_LINES));
        if (lines.size() <= allowed) {
            return lines;
        }
        int shown = overflow == null ? allowed : Math.min(allowed, ToastRenderer.MAX_LINES - 1);
        while (shown > 0 && lines.get(shown - 1).heading()) {
            shown--;
        }
        if (shown == 0 && !lines.get(1).heading()) {
            // Only the list's opening heading fit: keep it with its first row instead of nothing.
            shown = SECTION_FLOOR;
        }
        List<ToastLine> capped = new ArrayList<>(lines.subList(0, shown));
        if (overflow != null) {
            int dropped = rewardRows(lines.subList(shown, lines.size()));
            if (dropped > 0) {
                capped.add(ToastLine.text(overflow.apply(dropped)));
            }
        }
        return capped;
    }

    /** How many of {@code lines} are reward rows rather than section headings. */
    private static int rewardRows(@Nonnull List<ToastLine> lines) {
        int count = 0;
        for (ToastLine line : lines) {
            if (!line.heading()) {
                count++;
            }
        }
        return count;
    }

    /** A chip as a toast row: its icon at quantity one when it has one, else text only. Null for none. */
    @Nullable
    private static ToastLine rowOf(@Nullable RewardChip chip) {
        if (chip == null) {
            return null;
        }
        return chip.hasIcon()
                ? ToastLine.item(chip.iconItemId(), 1, chip.label())
                : ToastLine.text(chip.label());
    }

    /**
     * Every readable row of {@code rewards}, laid out by origin (see the class note), uncapped.
     * Exposed to this package so the layout is pinned with no server behind it.
     */
    @Nonnull
    static List<ToastLine> sectioned(@Nonnull List<RewardSpec> rewards,
            @Nonnull Function<RewardSpec, ToastLine> row) {
        List<Run> runs = runsOf(rewards, row);
        int fromLootables = 0;
        for (Run run : runs) {
            if (run.origin() != null) {
                fromLootables++;
            }
        }
        if (fromLootables == 0 || runs.size() == 1) {
            // No lootable rows, or one lootable's rows and nothing else: flat, as always.
            List<ToastLine> flat = new ArrayList<>();
            for (Run run : runs) {
                flat.addAll(run.lines());
            }
            return flat;
        }
        List<ToastLine> out = new ArrayList<>();
        List<ToastLine> sections = new ArrayList<>();
        for (Run run : runs) {
            ToastLine heading = run.origin() == null ? null : row.apply(run.origin());
            if (heading == null) {
                // Rows not from a lootable, and a bundle nothing can name (never under a blank
                // heading): plain rows, together at the top in receipt order.
                out.addAll(run.lines());
                continue;
            }
            sections.add(heading.withHeading(true).withIndented(false));
            for (ToastLine line : run.lines()) {
                sections.add(line.withIndented(true));
            }
        }
        out.addAll(sections);
        return out;
    }

    /**
     * {@code rewards} read through {@code row} and grouped into contiguous runs of the same origin
     * (by identity), in receipt order. A row that reads as nothing is left out, so a lootable that
     * rolled nothing readable adds no run, and the readable rows on either side of an unreadable one
     * stay one run.
     */
    @Nonnull
    private static List<Run> runsOf(@Nonnull List<RewardSpec> rewards,
            @Nonnull Function<RewardSpec, ToastLine> row) {
        List<Run> runs = new ArrayList<>();
        for (RewardSpec spec : rewards) {
            if (spec == null) {
                continue;
            }
            ToastLine line = row.apply(spec);
            if (line == null) {
                continue;
            }
            Run last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last == null || last.origin() != spec.origin()) {
                last = new Run(spec.origin(), new ArrayList<>());
                runs.add(last);
            }
            last.lines().add(line);
        }
        return runs;
    }

    /** One contiguous run of receipt rows sharing an origin (null for rows not from a lootable). */
    private record Run(@Nullable RewardSpec origin, @Nonnull List<ToastLine> lines) {
    }
}
