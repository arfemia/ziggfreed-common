package com.ziggfreed.common.board;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What the board engine needs to know about one bounty. The seam between the ENGINE and whatever
 * authored it, so the engine never learns about an asset type or a store.
 *
 * <p>Deliberately four questions, and three of them are about ONE board. A bounty IS a quest, so
 * everything about its objectives, its rewards, its gates and its lifecycle already belongs to the
 * quest engine and is reached through it; the only thing a BOARD adds is whether this contract
 * appears there, how it is graded, and how strongly it is drawn. Asking anything else here would be
 * the board engine growing a second opinion about what a quest is.
 *
 * <p><b>The membership LIST stays with the authoring layer.</b> A bounty may hang on several boards
 * at different grades, and that list is one authored fact; answering per board rather than handing
 * the list over is what keeps a second membership type out of the engine.
 */
public interface BountyRef {

    /** The quest id this bounty is, which is what the quest engine is asked about. */
    @Nonnull
    String bountyId();

    /** Does this contract appear on {@code boardId} at all? Matched case-insensitively. */
    boolean isOn(@Nonnull String boardId);

    /**
     * How it is graded on {@code boardId} - a difficulty band, a tier, whatever word its authors
     * use - or null when it is ungraded there and only an open slot can take it.
     */
    @Nullable
    String difficultyOn(@Nonnull String boardId);

    /** How strongly that board's draw favours it. Zero or less reads as one. */
    default double weightOn(@Nonnull String boardId) {
        return 1.0;
    }

    /** False takes it out of every draw without deleting the file. */
    default boolean enabled() {
        return true;
    }
}
