package com.ziggfreed.common.ui.toast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;

/**
 * One player's in-page toast slot and the notices waiting behind it: a notice a higher rank keeps off waits
 * its turn instead of being lost, the best one waiting shows when the toast on screen ends, and nothing
 * waits for ever. Each case runs on its own player, since the store is shared.
 */
class ToastStoreTest {

    private static final long T = 1_000_000L;

    private final UUID player = UUID.randomUUID();

    private static ToastSpec toast(String words, int rank) {
        return ToastSpec.of(ToastKind.INFO, Msg.raw(words)).withPriority(rank);
    }

    /** End each toast in turn, as its hide timer would, collecting what showed next. */
    private List<ToastSpec> drain(long gen, long nowMs) {
        List<ToastSpec> shown = new ArrayList<>();
        ToastStore.Next next = ToastStore.clearIfCurrent(player, gen, true, nowMs);
        while (next.spec() != null) {
            shown.add(next.spec());
            next = ToastStore.clearIfCurrent(player, next.gen(), true, nowMs);
        }
        assertTrue(next.cleared(), "the last toast ends into idle");
        assertNull(ToastStore.currentFor(player));
        return shown;
    }

    @Test
    void aNoticeKeptOffByAHigherRankShowsWhenThatOneEnds() {
        ToastSpec claim = toast("claim", ToastSpec.PRIORITY_CLAIM);
        ToastSpec step = toast("step", ToastSpec.PRIORITY_PROGRESS);

        long gen = ToastStore.show(player, claim, T);
        assertEquals(ToastStore.HELD_BACK, ToastStore.show(player, step, T), "the claim keeps the step off");
        assertSame(claim, ToastStore.currentFor(player), "the claim stays for its full time");

        assertEquals(List.of(step), drain(gen, T + 4_000));
    }

    @Test
    void waitingNoticesTakeTheirTurnByRankThenArrival() {
        long gen = ToastStore.show(player, toast("claim", ToastSpec.PRIORITY_CLAIM), T);
        ToastSpec firstStep = toast("first step", ToastSpec.PRIORITY_PROGRESS);
        ToastSpec earned = toast("earned", ToastSpec.PRIORITY_EARNED);
        ToastSpec secondStep = toast("second step", ToastSpec.PRIORITY_PROGRESS);
        ToastStore.show(player, firstStep, T);
        ToastStore.show(player, earned, T);
        ToastStore.show(player, secondStep, T);

        assertEquals(List.of(earned, firstStep, secondStep), drain(gen, T + 1_000));
    }

    @Test
    void aFullQueueDropsItsLowestRankOldestFirst() {
        long gen = ToastStore.show(player, toast("claim", ToastSpec.PRIORITY_CLAIM), T);
        ToastSpec firstStep = toast("first step", ToastSpec.PRIORITY_PROGRESS);
        ToastSpec secondStep = toast("second step", ToastSpec.PRIORITY_PROGRESS);
        ToastStore.show(player, firstStep, T);
        ToastStore.show(player, secondStep, T);
        List<ToastSpec> expected = new ArrayList<>();
        for (int i = 0; i < ToastStore.QUEUE_CAP - 1; i++) {
            ToastSpec earned = toast("earned " + i, ToastSpec.PRIORITY_EARNED);
            ToastStore.show(player, earned, T);
            expected.add(earned);
        }
        expected.add(secondStep);

        assertEquals(expected, drain(gen, T + 1_000),
                "one past the cap, the oldest of the lowest rank is the one dropped");
    }

    @Test
    void aNoticeThatWaitedTooLongIsDroppedUnseen() {
        long gen = ToastStore.show(player, toast("claim", ToastSpec.PRIORITY_CLAIM), T);
        ToastStore.show(player, toast("step", ToastSpec.PRIORITY_PROGRESS), T);

        assertEquals(List.of(), drain(gen, T + ToastStore.STALE_MS + 1));
    }

    @Test
    void anOldTimerNeitherClearsANewerToastNorPutsUpWhatWaits() {
        ToastSpec firstClaim = toast("first claim", ToastSpec.PRIORITY_CLAIM);
        ToastSpec secondClaim = toast("second claim", ToastSpec.PRIORITY_CLAIM);
        ToastSpec step = toast("step", ToastSpec.PRIORITY_PROGRESS);
        long first = ToastStore.show(player, firstClaim, T);
        long second = ToastStore.show(player, secondClaim, T);
        ToastStore.show(player, step, T);

        ToastStore.Next stale = ToastStore.clearIfCurrent(player, first, true, T + 4_000);
        assertFalse(stale.cleared(), "the first claim's timer finds a newer toast in the slot");
        assertSame(secondClaim, ToastStore.currentFor(player));

        assertEquals(List.of(step), drain(second, T + 4_000),
                "an equal rank replaced the first claim for good; only the step waited");
    }

    @Test
    void aClicksAnswerCutsARankedToastShortAndItComesBackAfter() {
        ToastSpec claim = toast("claim", ToastSpec.PRIORITY_CLAIM);
        ToastSpec click = toast("saved", 0);
        long claimGen = ToastStore.show(player, claim, T);
        long clickGen = ToastStore.show(player, click, T + 500);

        assertNotEquals(ToastStore.HELD_BACK, clickGen, "an unranked answer always shows");
        assertSame(click, ToastStore.currentFor(player));
        assertFalse(ToastStore.clearIfCurrent(player, claimGen, true, T + 4_000).cleared());

        assertEquals(List.of(claim), drain(clickGen, T + 4_500));
    }

    @Test
    void withTheScreenGoneTheQueueGoesWithTheToast() {
        long gen = ToastStore.show(player, toast("claim", ToastSpec.PRIORITY_CLAIM), T);
        ToastStore.show(player, toast("step", ToastSpec.PRIORITY_PROGRESS), T);

        ToastStore.Next next = ToastStore.clearIfCurrent(player, gen, false, T + 4_000);
        assertTrue(next.cleared());
        assertNull(next.spec(), "nothing is put up on a page that has closed");
        assertNull(ToastStore.currentFor(player), "and nothing is left to paint on a page opened later");
    }
}
