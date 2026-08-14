package com.ziggfreed.common.currency;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * How an ITEM-backed currency's balance is read and moved: the player's own inventory, behind three
 * methods.
 *
 * <p>It is a seam for one reason. An item-backed balance is the one part of the currency engine
 * that cannot be answered without a live entity, so a seam is what lets every pure part of the
 * engine - the caps, the ALL-or-nothing drain, the receipts, the refund - be exercised by handing
 * it two numbers. {@link NativeItemWallet} is the real one and is what a server installs.
 *
 * <p>Counts are {@code long} because a currency balance is, even though a single stack cannot hold
 * that much; an implementation clamps as its own storage requires.
 */
public interface ItemWallet {

    /** A wallet with no inventory behind it: every balance reads 0 and nothing can be moved. */
    ItemWallet NONE = new ItemWallet() {
        @Override
        public long count(@Nonnull Subject subject, @Nonnull String itemId) {
            return 0L;
        }

        @Override
        public boolean take(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            return false;
        }

        @Override
        public long give(@Nonnull Subject subject, @Nonnull String itemId, long amount) {
            return 0L;
        }
    };

    /** How many of {@code itemId} this subject holds. Zero when it cannot be told. */
    long count(@Nonnull Subject subject, @Nonnull String itemId);

    /**
     * Remove exactly {@code amount} of {@code itemId}, or nothing at all. Answering false must
     * leave the inventory untouched: a half-taken price is the one outcome no caller can undo.
     */
    boolean take(@Nonnull Subject subject, @Nonnull String itemId, long amount);

    /**
     * Hand over up to {@code amount} of {@code itemId} and answer how much genuinely landed. A
     * short answer is not an error: a full inventory is a real state, and the caller decides what
     * to do about the remainder.
     */
    long give(@Nonnull Subject subject, @Nonnull String itemId, long amount);
}
