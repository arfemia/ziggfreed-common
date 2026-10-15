package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One section of the stat board as it is shown: a tab, and the lines its columns rank, in the order its
 * file names them.
 *
 * <p>A {@code Stat/*} line ranks every line of the stat. On the sections row it stands for one column per
 * thing the stat's source names now (a native stat's reader: each reputation); in a section split into things
 * whose split names no columns of its own (the Bosses and Dungeons tabs, {@link StatBoardBossFights},
 * {@link StatBoardDungeons}), each such stat is one column of a picked thing, and each plain stat id one column of
 * the first choice and of a group ({@link StatSplit#fromSection}).
 *
 * @param id      the section's file id, lower-cased
 * @param nameKey the tab's name, a translation key; null names it by {@code event}'s title, else shows the id
 * @param order   where the tab sits, lowest first
 * @param lines   the stat ids, {@code Stat/Thing} lines and {@code Stat/*} lines it names, as written
 * @param event   the calendar event a generated section belongs to (a season page's), whose title names the tab
 *                when {@code nameKey} is null; null for a section file
 */
public record StatSection(@Nonnull String id, @Nullable String nameKey, int order, @Nonnull List<String> lines,
                          @Nullable String event) {

    /** The thing a {@code Stat/*} line names: every line of the stat. */
    public static final String EVERY_THING = "*";

    public StatSection {
        lines = List.copyOf(lines);
        event = event == null || event.isBlank() ? null : event.trim();
    }

    /** A section belonging to no calendar event: every section file. */
    public StatSection(@Nonnull String id, @Nullable String nameKey, int order, @Nonnull List<String> lines) {
        this(id, nameKey, order, lines, null);
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
