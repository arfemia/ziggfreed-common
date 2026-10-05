package com.ziggfreed.common.board.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * The {@code defaults < pack < owner} fold of every {@link BoardAsset}: which contract boards this
 * server has.
 *
 * <p>Process-wide because the defining ASSETS are: one folder, one set of files, however many mods
 * post to them. A pack ships its boards and a server owner retunes one through
 * {@code mods/ziggfreedcommon/boards.json} - taking a board down, slowing its rotation, raising a
 * band's bar - without editing anybody's pack.
 *
 * <p>Two views of one list. A player's view is {@link #listedIn} and {@link #firstListedIdIn}, which
 * also leave out a board whose {@code Where} does not name the world that player stands in.
 * {@link #listed()} and {@link #firstListedId()} have nobody looking, so they ignore {@code Where}:
 * they are what the admin verbs and a server-wide question ("does this server post contracts?")
 * read.
 */
public final class BoardConfig extends AbstractKeyedAssetConfig<BoardAsset> {

    private static final BoardConfig INSTANCE = new BoardConfig();

    private BoardConfig() {
    }

    @Nonnull
    public static BoardConfig getInstance() {
        return INSTANCE;
    }

    /**
     * Every board on this server right now ({@link BoardAsset#isAvailable()}: switched on, and not
     * hidden by a feature that reads off), in the order they should be listed: by {@code Order},
     * then by id so two boards sharing a number never swap places between restarts. Nobody is
     * looking, so {@code Where} is not asked; a player's list is {@link #listedIn}.
     */
    @Nonnull
    public List<BoardAsset> listed() {
        return listedWhere(BoardAsset::isAvailable);
    }

    /**
     * What a player standing in {@code viewer}'s world is shown: {@link #listed()} without any board
     * whose {@code Where} leaves that world out ({@link BoardAsset#isAvailableIn}), in the same
     * order.
     */
    @Nonnull
    public List<BoardAsset> listedIn(@Nonnull LoadedWorld viewer) {
        return listedWhere(board -> board.isAvailableIn(viewer));
    }

    /** The first board {@link #listed()} names. Null for none. */
    @Nullable
    public String firstListedId() {
        return firstIdOf(listed());
    }

    /**
     * The first board {@link #listedIn} names for {@code viewer}: what an unnamed destination opens
     * for that player. Null for none.
     */
    @Nullable
    public String firstListedIdIn(@Nonnull LoadedWorld viewer) {
        return firstIdOf(listedIn(viewer));
    }

    @Nonnull
    private List<BoardAsset> listedWhere(@Nonnull Predicate<BoardAsset> keep) {
        List<BoardAsset> out = new ArrayList<>();
        for (String id : ids()) {
            BoardAsset board = resolve(id);
            if (board != null && keep.test(board)) {
                out.add(board);
            }
        }
        out.sort(Comparator.comparingInt(BoardAsset::order)
                .thenComparing(board -> board.getId() == null ? "" : board.getId()));
        return out;
    }

    @Nullable
    private static String firstIdOf(@Nonnull List<BoardAsset> boards) {
        for (BoardAsset board : boards) {
            if (board.getId() != null) {
                return board.getId();
            }
        }
        return null;
    }
}
