package com.ziggfreed.common.board;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * The narrow slice of the quest lifecycle the board engine drives.
 *
 * <p>A bounty IS a quest, so none of this is the board engine's own state: it is asking the quest
 * engine four questions and telling it two things. The seam exists so the board's OWN decisions -
 * which contracts are on show, whether one is still locked from this period, whether a reroll may
 * happen - are exercisable by handing them numbers, without standing a whole progression runtime up
 * first. {@link QuestEngineBoardQuests} is the real one.
 *
 * <p><b>The accept SITE is the board id, and threading it is the point.</b> A bounty taken off a
 * board is bound to that board, so the quest engine's own completion predicate refuses a hand-in
 * anywhere else. No bounty author writes any of that; the board engine stamps it on every accept.
 */
public interface BoardQuests {

    /** A seam that knows about no quests at all. Every read is empty and every write does nothing. */
    BoardQuests NONE = new BoardQuests() {
        @Override
        public boolean accept(@Nonnull Subject subject, @Nonnull String bountyId, @Nonnull String boardId) {
            return false;
        }

        @Override
        public boolean isCarried(@Nonnull Subject subject, @Nonnull String bountyId) {
            return false;
        }

        @Override
        public long lastCompletionMs(@Nonnull Subject subject, @Nonnull String bountyId) {
            return 0L;
        }
    };

    /**
     * Take {@code bountyId} on, recorded as having been taken at {@code boardId}. False when the
     * quest engine refused it (already carried, gated, unknown).
     */
    boolean accept(@Nonnull Subject subject, @Nonnull String bountyId, @Nonnull String boardId);

    /** Is this subject carrying that bounty right now, whether or not it is finished? */
    boolean isCarried(@Nonnull Subject subject, @Nonnull String bountyId);

    /**
     * When this subject last COMPLETED that bounty, in epoch milliseconds, or 0 for never. The
     * number the period lock is decided on: a bounty completed inside the current rotation period
     * stays spent until the board turns over.
     */
    long lastCompletionMs(@Nonnull Subject subject, @Nonnull String bountyId);

    /**
     * Put a bounty back within reach after its rotation period has passed, undoing a prior-period
     * completion's hold on it. Does nothing by default, for a seam whose quest engine re-arms
     * repeatables by itself.
     */
    default void reArm(@Nonnull Subject subject, @Nonnull String bountyId) {
    }

    /**
     * Where this subject took {@code bountyId}, or null when they are not carrying it or it was
     * taken nowhere in particular. What a surface asks to show "you took this at the Daily board".
     */
    @Nullable
    default String acceptedAt(@Nonnull Subject subject, @Nonnull String bountyId) {
        return null;
    }
}
