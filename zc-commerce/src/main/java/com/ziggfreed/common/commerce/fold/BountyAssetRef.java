package com.ziggfreed.common.commerce.fold;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.board.asset.BountyAsset;

/**
 * One authored contract, as the board engine sees it.
 *
 * <p>Three of the four questions are about ONE board, and each is answered by walking the contract's
 * own authored membership list. Nothing is precomputed per board: the list is short, a draw asks
 * each candidate once, and keeping the list where the author wrote it is what stops a second
 * membership type existing on the engine side.
 *
 * <p>What the contract IS - its steps, its rewards, its gate, its lifecycle - is a quest, reached
 * through the quest engine after {@link BountyAsset#toDefinition} has folded it. This view answers
 * only what being POSTED adds.
 */
public final class BountyAssetRef implements BountyRef {

    private final BountyAsset asset;

    private BountyAssetRef(@Nonnull BountyAsset asset) {
        this.asset = asset;
    }

    /** The engine view of {@code asset}. */
    @Nonnull
    public static BountyAssetRef of(@Nonnull BountyAsset asset) {
        return new BountyAssetRef(asset);
    }

    /** What the author wrote, for everything a draw does not ask about. */
    @Nonnull
    public BountyAsset asset() {
        return asset;
    }

    @Override
    @Nonnull
    public String bountyId() {
        return asset.getId() == null ? "" : asset.getId();
    }

    @Override
    public boolean isOn(@Nonnull String boardId) {
        return asset.membershipOn(boardId) != null;
    }

    @Override
    @Nullable
    public String difficultyOn(@Nonnull String boardId) {
        BountyAsset.BoardMembership membership = asset.membershipOn(boardId);
        return membership == null ? null : membership.getDifficulty();
    }

    @Override
    public double weightOn(@Nonnull String boardId) {
        BountyAsset.BoardMembership membership = asset.membershipOn(boardId);
        return membership == null ? 1.0 : membership.weightOrOne();
    }

    @Override
    public boolean enabled() {
        return asset.isEnabled();
    }

    @Override
    public String toString() {
        return "BountyAssetRef[" + bountyId() + "]";
    }
}
