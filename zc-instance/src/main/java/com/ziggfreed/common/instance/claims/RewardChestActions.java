package com.ziggfreed.common.instance.claims;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * How a reward chest's page ({@link RewardChestPage}) answers each event, apart from the page so a unit JVM drives it:
 * every event gets exactly one answer, since a client whose event goes unanswered stays locked. A line's click takes
 * that one reward and a Take All takes everything, each then repainting the page on what now waits, so a press that
 * throws, or a stale line whose reward is gone, still repaints and shows the truth; a close closes it; anything else
 * (an empty action, one this page never binds) gets an empty update. A player who cannot be read is answered with an
 * empty update too, and an exit that throws falls back to one.
 */
final class RewardChestActions {

    /** A line's click: take that one reward ({@code Claim} and {@code Reward} on the event). */
    static final String TAKE = "take";

    /** The gold Take All: everything of the player's in the chest. */
    static final String TAKE_ALL = "take_all";

    /** The frame's close button. */
    static final String CLOSE = "close";

    /** What the page does for an event: the two presses, and its three ways to answer. */
    interface Host<P> {

        /** The player the event came from, or null when there is none to answer for. */
        @Nullable
        P player();

        /** Take the one reward {@code reward} of the Claim {@code claim} and raise the press's toast. */
        void take(@Nonnull P player, @Nonnull String claim, @Nonnull String reward);

        /** Take everything of the player's in the chest and raise the press's toast. */
        void takeAll(@Nonnull P player);

        /** Repaint the page on what now waits: an answer. */
        void reopen(@Nonnull P player);

        /** Close the page: an answer. */
        void close(@Nonnull P player);

        /** An empty update: an answer that changes nothing. */
        void answer();
    }

    private RewardChestActions() {
    }

    /** Answer one event on {@code page}, exactly once. Never throws. */
    static <P> void event(@Nonnull Host<P> page, @Nullable String action, @Nullable String claim,
            @Nullable String reward) {
        P player;
        try {
            player = page.player();
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the chest's page could not read who clicked: " + t.getMessage());
            answer(page);
            return;
        }
        if (player == null) {
            answer(page);
            return;
        }
        String act = action == null ? "" : action.trim();
        try {
            if (CLOSE.equals(act)) {
                page.close(player);
            } else if (TAKE.equals(act) || TAKE_ALL.equals(act)) {
                press(page, player, act, claim, reward);
                page.reopen(player);
            } else {
                page.answer();
            }
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the chest's page could not answer '" + act + "': " + t.getMessage());
            answer(page);
        }
    }

    /** One press; a line with no Claim or no reward on it takes nothing. A press that throws is logged, never raised. */
    private static <P> void press(@Nonnull Host<P> page, @Nonnull P player, @Nonnull String act,
            @Nullable String claim, @Nullable String reward) {
        try {
            if (TAKE_ALL.equals(act)) {
                page.takeAll(player);
            } else if (claim != null && !claim.isBlank() && reward != null && !reward.isBlank()) {
                page.take(player, claim.trim(), reward.trim());
            }
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "a press on the chest's page failed; what it did not hand over waits", t);
        }
    }

    /** The empty update, its own throw only logged: nothing is left to answer with. */
    private static void answer(@Nonnull Host<?> page) {
        try {
            page.answer();
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the chest's page could not answer an event: " + t.getMessage());
        }
    }
}
