package com.ziggfreed.common.board.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link BoardAsset}: which contract boards this
 * server has.
 *
 * <p>Process-wide because the defining ASSETS are: one folder, one set of files, however many mods
 * post to them. A pack ships its boards and a server owner retunes one through
 * {@code mods/ziggfreedcommon/boards.json} - taking a board down, slowing its rotation, raising a
 * band's bar - without editing anybody's pack.
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
     * Every board that can be opened, in the order they should be listed: by {@code Order}, then by
     * id so two boards sharing a number never swap places between restarts.
     */
    @Nonnull
    public List<BoardAsset> listed() {
        List<BoardAsset> out = new ArrayList<>();
        for (String id : ids()) {
            BoardAsset board = resolve(id);
            if (board != null && board.isEnabled()) {
                out.add(board);
            }
        }
        out.sort(Comparator.comparingInt(BoardAsset::order)
                .thenComparing(board -> board.getId() == null ? "" : board.getId()));
        return out;
    }
}
