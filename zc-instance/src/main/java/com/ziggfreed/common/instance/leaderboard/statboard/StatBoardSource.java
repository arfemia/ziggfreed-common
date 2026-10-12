package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.leaderboard.StatColumnDef;

/**
 * What the stat board asks of the store that keeps the stats it ranks: whether it is on, what each
 * section line's column is (and which lines a {@code Stat/*} line stands for), and, as the board opens for
 * a player, to set what it reads live. This module cannot see that store (it lives above, over the progression
 * moments), so the store fills this seam at setup through {@link StatBoard#setSource}.
 *
 * <p>The default, {@link #NONE}, knows no stat: every section is left without a column, so the board
 * shows no tab and Records shows none. That is the right answer on a server where nothing counts stats,
 * and it never shows a column nobody counts.
 */
public interface StatBoardSource {

    /** Nothing counts stats here: the board is off and knows no column. */
    StatBoardSource NONE = new StatBoardSource() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        @Nullable
        public StatColumnDef column(@Nonnull String line) {
            return null;
        }
    };

    /** Whether the stat board runs at all: false hides its tab, and its writes are the store's to skip. */
    boolean enabled();

    /**
     * The column for one section line (a stat id, or {@code Stat/Thing}: one written, one a {@code Stat/*}
     * line stands for, or one fight's line the Boss Fights page asks for), or null when nothing on this
     * server counts it, which leaves it out. Its {@link StatColumnDef#statKey()} is the line as the board
     * stores it; its label is a bare translation, since a column header is painted on a {@code .Text} sink
     * that never substitutes a parameter; {@link StatColumnDef#ascending()} ranks a best that shrinks
     * (a Min stat) lowest first.
     */
    @Nullable
    StatColumnDef column(@Nonnull String line);

    /**
     * Every line a section's {@code Stat/*} line stands for, each written {@code Stat/Thing}, in the order
     * their columns show: the things the stat's source can name now (the server's reputations, say). None,
     * the default, leaves the line without a column on the sections row; that is the answer for a stat whose
     * things are tabs instead (the Boss Fights stats, a fight per tab, {@link StatBoardBossFights}).
     */
    @Nonnull
    default List<String> everyLine(@Nonnull String statId) {
        return List.of();
    }

    /**
     * Whether stat {@code statId} only ever holds all-time numbers (a gauge set as it stands, a native reading),
     * so a week, a month or a season reads none of it. A split over such stats reads All Time alone
     * ({@link StatSplit}). False, the default.
     */
    default boolean allTimeOnly(@Nonnull String statId) {
        return false;
    }

    /**
     * Before the board opens for the player at {@code ref}: set on the board what the store reads live
     * rather than counts (its native readings), so the player sees their own numbers as they stand. Nothing,
     * the default. World thread.
     */
    default void refresh(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
    }
}
