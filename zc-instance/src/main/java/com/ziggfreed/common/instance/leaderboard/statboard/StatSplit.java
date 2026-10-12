package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * How a section's tab splits on Records, registered in code by whoever owns the lines
 * ({@link StatSectionConfig#split}): a row of choices under the tab, the first its own columns and then one per
 * group, and a picker of single things. A split names stats with a line per thing ({@code Kills/WOLF});
 * a group's columns are each stat's lines summed over the group's things as the page builds, never stored, and a
 * thing's columns are its own lines. Every line of a split stat is ranked, as a {@code Stat/*} line's are. With
 * nothing registered a tab has no split, which is every tab on a server running the library alone.
 *
 * @param columns      the split stats, each one column on a group or a thing, in order: the first ranks, the rest
 *                     break its ties
 * @param allLabel     the first choice's name: the tab's own columns
 * @param pickAllLabel the picker's first entry, the chosen choice's own columns: a translation, since a dropdown
 *                     entry resolves only a key
 * @param groups       the groups and their things, asked each time Records builds, so a thing added or taken away
 *                     since shows at the next open
 * @param shows        whether a viewer sees a thing (one they may rank in); a group whose things they see none of is
 *                     hidden from them
 */
public record StatSplit(@Nonnull List<Column> columns, @Nonnull Message allLabel, @Nonnull Message pickAllLabel,
                        @Nonnull Supplier<List<Group>> groups, @Nonnull BiPredicate<PlayerRef, String> shows) {

    public StatSplit {
        columns = List.copyOf(columns);
    }

    /**
     * One split stat's column: its stat id, the column's name on a group and on a thing (a bare translation, as
     * every column header is), and the value a line must pass to count toward a group's sum (1 for a value every
     * player starts at, 0 to count every line).
     */
    public record Column(@Nonnull String stat, @Nonnull Message label, long partsOver) {
    }

    /** One group: its id (the choice's key), its name and its things, in order. */
    public record Group(@Nonnull String id, @Nonnull Message label, @Nonnull List<Thing> things) {

        public Group {
            things = List.copyOf(things);
        }
    }

    /** One thing: its id, the {@code Thing} of its lines ({@code WOLF}), and its name. */
    public record Thing(@Nonnull String id, @Nonnull Message label) {
    }
}
