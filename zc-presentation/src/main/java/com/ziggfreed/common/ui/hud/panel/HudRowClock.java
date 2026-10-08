package com.ziggfreed.common.ui.hud.panel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * When one row on a bar panel goes away, as a pure value the panel swaps under the row's lock.
 *
 * <p>A row runs its linger from its last move and goes once that has passed. On a panel that holds
 * while a page is open ({@link HudPanelAsset#holdsWhilePageOpen}) the clock can also stand still:
 * the client draws no HUD over a page, so a row moved under one, or still up when one opens, would
 * otherwise come and go unseen. Such a row WAITS, with no expiry at all, and when the player has no
 * page open again it runs its whole linger from that moment and counts as just moved, so it pulses
 * and keeps its slot as the newest. A row whose time had already run when a page opened is not
 * brought back. What waits is held to a cap ({@link #beyondCap}), the oldest going first, so a long
 * stay in a page never piles rows up.
 *
 * @param lastMovedMs when the row last moved, or came out from under a page
 * @param expiresAtMs when it goes, {@link #NEVER} while it waits or for a row held on purpose
 *                    ({@link HudRowLook#LINGER_HELD})
 * @param waiting     whether its clock is stopped under a page
 */
record HudRowClock(long lastMovedMs, long expiresAtMs, boolean waiting) {

    /** No expiry: a waiting row, or one held until something sends it away. */
    static final long NEVER = Long.MAX_VALUE;

    /** A move at {@code now}: waiting when {@code underPage}, else running {@code lingerMs} from now. */
    @Nonnull
    static HudRowClock moved(long now, long lingerMs, boolean underPage) {
        return underPage ? new HudRowClock(now, NEVER, true) : new HudRowClock(now, expiryFrom(now, lingerMs), false);
    }

    /**
     * When a row running {@code lingerMs} from {@code now} goes: never for a held row, and never
     * rather than a time already past when the sum would run off the end of a long.
     */
    static long expiryFrom(long now, long lingerMs) {
        if (lingerMs == HudRowLook.LINGER_HELD) {
            return NEVER;
        }
        long linger = Math.max(0L, lingerMs);
        return now > NEVER - linger ? NEVER : now + linger;
    }

    /** Whether the row has gone by {@code now}: never while it waits. */
    boolean expired(long now) {
        return !waiting && expiresAtMs <= now;
    }

    /** A page opened at {@code now}: a row still up stops its clock; one whose time ran out stays gone. */
    @Nonnull
    HudRowClock pageOpened(long now) {
        if (waiting || expired(now)) {
            return this;
        }
        return new HudRowClock(lastMovedMs, NEVER, true);
    }

    /**
     * No page is open at {@code now}: a waiting row runs its whole {@code lingerMs} from now and
     * counts as moved now; a running row is left as it is.
     */
    @Nonnull
    HudRowClock pageClosed(long now, long lingerMs) {
        return waiting ? moved(now, lingerMs, false) : this;
    }

    /**
     * Every row sent away by {@code deadline} ({@link HudPanels#fadeAll}): a running row's expiry is
     * brought forward to it, one going sooner keeps its own time, and a waiting row, whose clock has
     * not started, is left waiting.
     */
    @Nonnull
    HudRowClock fadeBy(long deadline) {
        if (waiting || expiresAtMs <= deadline) {
            return this;
        }
        return new HudRowClock(lastMovedMs, deadline, false);
    }

    /**
     * Which of the waiting rows ({@code waiting}: each row's id and when it last moved) go so that at
     * most {@code cap} wait, never fewer than one: the most recently moved stay, a tie going to the
     * lower id, and every older one is returned.
     */
    @Nonnull
    static List<String> beyondCap(@Nonnull Map<String, Long> waiting, int cap) {
        int keep = Math.max(1, cap);
        if (waiting.size() <= keep) {
            return List.of();
        }
        List<Map.Entry<String, Long>> newest = new ArrayList<>(waiting.entrySet());
        newest.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<String> out = new ArrayList<>(newest.size() - keep);
        for (Map.Entry<String, Long> entry : newest.subList(keep, newest.size())) {
            out.add(entry.getKey());
        }
        return out;
    }
}
