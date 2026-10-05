package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.world.WhereValidator.LoadedWorld;

/**
 * Which boards exist and which contracts may be posted on them.
 *
 * <p>The board engine takes a board and a pool per call rather than a catalog of its own, so this is
 * where a surface gets both. It is LIVE on both sides - the boards off {@link BoardConfig}'s
 * {@code defaults < pack < owner} fold, the contracts off {@link BoardAssetStore}'s loaded layer - so
 * a reload lands on the next question and there is nothing to invalidate. A skeleton is never in the
 * pool: it exists to be inherited from, never to be posted.
 *
 * <p>Both folds are memoised against the ASSET INSTANCE they came from, because a board page asks for
 * the pool once per open and a rotation walks it per slot. Every layer merge replaces the asset
 * objects wholesale, so identity is what says the memo is stale.
 */
public final class AssetBoardCatalog {

    private static final AssetBoardCatalog INSTANCE = new AssetBoardCatalog();

    /** The one catalog over the authored boards and contracts. */
    @Nonnull
    public static AssetBoardCatalog getInstance() {
        return INSTANCE;
    }

    /** One folded value, remembered against the asset it was folded from. */
    private record Memo<S, T>(@Nonnull S source, @Nonnull T folded) {
    }

    private final Map<String, Memo<BoardAsset, BoardAssetSpec>> boardMemo = new ConcurrentHashMap<>();
    private final Map<String, Memo<BountyAsset, BountyAssetRef>> bountyMemo = new ConcurrentHashMap<>();

    private AssetBoardCatalog() {
    }

    // ==================== Boards ====================

    /** The board with this id, matched case-insensitively, or null when nothing answers to it. */
    @Nullable
    public BoardAssetSpec board(@Nonnull String boardId) {
        BoardAsset asset = BoardConfig.getInstance().resolve(boardId);
        return asset == null ? null : foldedBoard(asset);
    }

    /**
     * Every board on this server, in the order they should be listed, with nobody looking: a
     * {@code Where} is not asked ({@link BoardConfig#listed()}). The admin verbs and a server-wide
     * question read this; a player's list is {@link #boardsIn}.
     */
    @Nonnull
    public List<BoardAssetSpec> boards() {
        return folded(BoardConfig.getInstance().listed());
    }

    /**
     * Every board a player standing in {@code viewer}'s world may open, in the same order: without
     * any board whose {@code Where} leaves that world out ({@link BoardConfig#listedIn}).
     */
    @Nonnull
    public List<BoardAssetSpec> boardsIn(@Nonnull LoadedWorld viewer) {
        return folded(BoardConfig.getInstance().listedIn(viewer));
    }

    // ==================== Contracts ====================

    /**
     * Every contract that could be posted anywhere. The board engine filters it to one board's
     * members itself, which is why this is the whole pool rather than a per-board list.
     */
    @Nonnull
    public Collection<BountyRef> pool() {
        Map<String, BountyAsset> assets = BoardAssetStore.getInstance().assets();
        List<BountyRef> out = new ArrayList<>(assets.size());
        for (BountyAsset asset : assets.values()) {
            BountyAssetRef ref = postable(asset);
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    /** The contract with this id, or null when nothing answers to it or it is a skeleton. */
    @Nullable
    public BountyAssetRef bounty(@Nonnull String bountyId) {
        return postable(BoardAssetStore.getInstance().assets()
                .get(bountyId.trim().toLowerCase(Locale.ROOT)));
    }

    // ==================== Internals ====================

    @Nonnull
    private List<BoardAssetSpec> folded(@Nonnull List<BoardAsset> listed) {
        List<BoardAssetSpec> out = new ArrayList<>(listed.size());
        for (BoardAsset asset : listed) {
            out.add(foldedBoard(asset));
        }
        return out;
    }

    @Nonnull
    private BoardAssetSpec foldedBoard(@Nonnull BoardAsset asset) {
        String id = asset.getId() == null ? "" : asset.getId();
        Memo<BoardAsset, BoardAssetSpec> current = boardMemo.get(id);
        if (current != null && current.source() == asset) {
            return current.folded();
        }
        BoardAssetSpec spec = BoardAssetSpec.of(asset);
        boardMemo.put(id, new Memo<>(asset, spec));
        return spec;
    }

    /** The engine view of one loaded contract, or null when it is absent or a skeleton. */
    @Nullable
    private BountyAssetRef postable(@Nullable BountyAsset asset) {
        if (asset == null || asset.isAbstract()) {
            return null;
        }
        String id = asset.getId() == null ? "" : asset.getId();
        Memo<BountyAsset, BountyAssetRef> current = bountyMemo.get(id);
        if (current != null && current.source() == asset) {
            return current.folded();
        }
        BountyAssetRef ref = BountyAssetRef.of(asset);
        bountyMemo.put(id, new Memo<>(asset, ref));
        return ref;
    }
}
