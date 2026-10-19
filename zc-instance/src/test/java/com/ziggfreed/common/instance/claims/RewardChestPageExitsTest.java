package com.ziggfreed.common.instance.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

/**
 * A reward chest's page answers every event exactly once, whatever happens on the way: a line's click and Take All
 * each take and then repaint, a stale line repaints on the truth, a press that throws still repaints, the close
 * button closes, anything else and an event nobody can be read for get an empty update, and a repaint that throws
 * falls back to one. A client whose event goes unanswered stays locked, so this is the page's one hard rule.
 */
class RewardChestPageExitsTest {

    /** Every way the page can answer, counted, and every press it made. */
    static final class Recorder implements RewardChestActions.Host<Object> {

        @Nullable Object player = new Object();
        boolean lookupThrows;
        boolean pressThrows;
        boolean reopenThrows;
        int reopens;
        int closes;
        int answers;
        int takeAlls;
        final List<String> taken = new ArrayList<>();

        int exits() {
            return reopens + closes + answers;
        }

        @Nullable
        @Override
        public Object player() {
            if (lookupThrows) {
                throw new IllegalStateException("store gone");
            }
            return player;
        }

        @Override
        public void take(@Nonnull Object who, @Nonnull String claim, @Nonnull String reward) {
            if (pressThrows) {
                throw new IllegalStateException("grant boom");
            }
            taken.add(claim + "/" + reward);
        }

        @Override
        public void takeAll(@Nonnull Object who) {
            if (pressThrows) {
                throw new IllegalStateException("grant boom");
            }
            takeAlls++;
        }

        @Override
        public void reopen(@Nonnull Object who) {
            if (reopenThrows) {
                throw new IllegalStateException("reopen boom");
            }
            reopens++;
        }

        @Override
        public void close(@Nonnull Object who) {
            closes++;
        }

        @Override
        public void answer() {
            answers++;
        }
    }

    @Nonnull
    private static Recorder run(@Nonnull Recorder page, @Nullable String action, @Nullable String claim,
            @Nullable String reward) {
        RewardChestActions.event(page, action, claim, reward);
        return page;
    }

    @Test
    void aLineTakesThatOneRewardAndRepaintsOnce() {
        Recorder page = run(new Recorder(), RewardChestActions.TAKE, " boss:claim ", " item-1a2b ");
        assertEquals(List.of("boss:claim/item-1a2b"), page.taken);
        assertEquals(1, page.reopens);
        assertEquals(1, page.exits());
    }

    @Test
    void takeAllTakesEverythingAndRepaintsOnce() {
        Recorder page = run(new Recorder(), RewardChestActions.TAKE_ALL, null, null);
        assertEquals(1, page.takeAlls);
        assertEquals(List.of(), page.taken);
        assertEquals(1, page.reopens);
        assertEquals(1, page.exits());
    }

    @Test
    void closeClosesOnceAndTakesNothing() {
        Recorder page = run(new Recorder(), RewardChestActions.CLOSE, "boss:claim", "item-1a2b");
        assertEquals(1, page.closes);
        assertEquals(1, page.exits());
        assertEquals(0, page.takeAlls + page.taken.size(), "a close takes nothing");
    }

    @Test
    void aStaleLineRepaintsOnTheTruthOnce() {
        // A line whose reward the press no longer finds takes nothing in the press itself and repaints the same way;
        // a line missing its Claim or its reward never reaches the press at all.
        for (String[] line : new String[][] {{"boss:claim", null}, {"boss:claim", " "}, {null, "item-1a2b"},
                {"", "item-1a2b"}}) {
            Recorder page = run(new Recorder(), RewardChestActions.TAKE, line[0], line[1]);
            assertEquals(List.of(), page.taken, "a line with no Claim or no reward takes nothing");
            assertEquals(1, page.reopens, "it repaints, so the page shows what really waits");
            assertEquals(1, page.exits());
        }
    }

    @Test
    void aPressThatThrowsStillRepaintsOnce() {
        for (String action : List.of(RewardChestActions.TAKE, RewardChestActions.TAKE_ALL)) {
            Recorder page = new Recorder();
            page.pressThrows = true;
            run(page, action, "boss:claim", "item-1a2b");
            assertEquals(1, page.reopens, action);
            assertEquals(1, page.exits(), action);
        }
    }

    @Test
    void aRepaintThatThrowsFallsBackToOneEmptyUpdate() {
        Recorder page = new Recorder();
        page.reopenThrows = true;
        run(page, RewardChestActions.TAKE_ALL, null, null);
        assertEquals(1, page.takeAlls);
        assertEquals(1, page.answers);
        assertEquals(1, page.exits());
    }

    @Test
    void anythingElseGetsOneEmptyUpdate() {
        for (String action : new String[] {null, "", " ", "no_such_thing", "claim_all"}) {
            Recorder page = run(new Recorder(), action, "boss:claim", "item-1a2b");
            assertEquals(1, page.answers, String.valueOf(action));
            assertEquals(1, page.exits(), String.valueOf(action));
            assertEquals(0, page.takeAlls + page.taken.size(), String.valueOf(action));
        }
    }

    @Test
    void anEventNobodyCanBeReadForGetsOneEmptyUpdateAndTakesNothing() {
        Recorder none = new Recorder();
        none.player = null;
        run(none, RewardChestActions.TAKE_ALL, null, null);
        assertEquals(1, none.answers);
        assertEquals(1, none.exits());
        assertEquals(0, none.takeAlls);

        Recorder throwing = new Recorder();
        throwing.lookupThrows = true;
        run(throwing, RewardChestActions.TAKE, "boss:claim", "item-1a2b");
        assertEquals(1, throwing.answers);
        assertEquals(1, throwing.exits());
        assertEquals(List.of(), throwing.taken);
    }

    @Test
    void thePageAnswersThroughTheTestedDoor() throws IOException {
        String page = Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "instance",
                "claims", "RewardChestPage.java"), StandardCharsets.UTF_8);
        int handler = page.indexOf("public void handleDataEvent(");
        assertTrue(handler > 0 && page.indexOf("RewardChestActions.event(", handler) > handler,
                "handleDataEvent answers through RewardChestActions.event, the path these tests drive");
    }
}
