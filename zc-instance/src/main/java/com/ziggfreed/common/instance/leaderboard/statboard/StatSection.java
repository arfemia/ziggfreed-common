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
 * @param lines   the stat ids and {@code Stat/Thing} lines it names, as written
 */
public record StatSection(@Nonnull String id, @Nullable String nameKey, int order, @Nonnull List<String> lines) {

    public StatSection {
        lines = List.copyOf(lines);
    }
}
