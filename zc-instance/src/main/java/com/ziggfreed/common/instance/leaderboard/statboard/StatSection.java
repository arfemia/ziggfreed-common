package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One section of the stat board as it is shown: a tab, and the lines its columns rank, in the order its
 * file names them.
 *
 * <p>A {@code Stat/*} line ranks every line of the stat. On the sections row it stands for one column per
 * thing the stat's source names now (a native stat's reader: each reputation); the {@code boss_fights}
 * section's are Records' Boss Fights, where each such stat is one column and each fight one tab
 * ({@link StatBoardBossFights}).
 *
 * @param id      the section's file id, lower-cased
 * @param nameKey the tab's name, a translation key; null shows the id
 * @param order   where the tab sits, lowest first
 * @param lines   the stat ids, {@code Stat/Thing} lines and {@code Stat/*} lines it names, as written
 */
public record StatSection(@Nonnull String id, @Nullable String nameKey, int order, @Nonnull List<String> lines) {

    /** The thing a {@code Stat/*} line names: every line of the stat. */
    public static final String EVERY_THING = "*";

    public StatSection {
        lines = List.copyOf(lines);
    }

    /**
     * The stat a {@code Stat/*} line stands for ({@code Reputation} for {@code Reputation/*}), or null for any
     * other line.
     */
    @Nullable
    public static String everyLineOf(@Nullable String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        int slash = trimmed.indexOf('/');
        if (slash <= 0 || !trimmed.substring(slash + 1).trim().equals(EVERY_THING)) {
            return null;
        }
        return trimmed.substring(0, slash).trim();
    }
}
