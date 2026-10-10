package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One section of the stat board as it is shown: a tab, and the lines its columns rank, in the order its
 * file names them.
 *
 * @param id      the section's file id, lower-cased
 * @param nameKey the tab's name, a translation key; null shows the id
 * @param order   where the tab sits, lowest first
 * @param lines   the stat ids, {@code Stat/Thing} lines and {@code Stat/*} lines it names, as written
 */
public record StatSection(@Nonnull String id, @Nullable String nameKey, int order, @Nonnull List<String> lines) {

    /** The thing a {@code Stat/*} line names: every line the stat's source can name, one column each. */
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
