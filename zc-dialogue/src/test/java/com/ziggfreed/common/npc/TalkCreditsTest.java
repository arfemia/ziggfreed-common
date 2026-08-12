package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two decisions the talk-credit engine makes before anything is counted: does this conversation
 * count at all, and does one broken mod cost another its quest step.
 *
 * <p>The re-trigger window is the load-bearing one. It sits in front of every sink rather than inside
 * any of them, because two sinks disagreeing about whether a conversation happened would tick a quest
 * while the statistic counting the same conversations stayed put - and nothing would ever report
 * that. The engine-touching half (assembling a credit from a live player and a store) is smoke
 * territory; everything decided before that is here.
 */
class TalkCreditsTest {

    /** A credit with no live engine handles: enough for the fan-out, which never looks at them. */
    private static TalkCredit credit(String npcId, String... answersTo) {
        List<String> answers = new ArrayList<>();
        answers.add(npcId);
        answers.addAll(List.of(answersTo));
        return new TalkCredit(null, null, null, null, npcId, answers, null);
    }

    @BeforeEach
    @AfterEach
    void reset() {
        TalkCredits.clearForTests();
    }

    @Nested
    class TheRetriggerWindow {

        @Test
        void aSecondCreditForTheSameCharacterIsSwallowed() {
            UUID player = UUID.randomUUID();
            assertTrue(TalkCredits.claim(player, "guide"));
            assertFalse(TalkCredits.claim(player, "guide"),
                    "a page re-render or a double click must not count twice");
        }

        @Test
        void theWindowIgnoresCase() {
            UUID player = UUID.randomUUID();
            assertTrue(TalkCredits.claim(player, "Guide"));
            assertFalse(TalkCredits.claim(player, "guide"));
        }

        @Test
        void itIsClaimedPerIdSoAnAliasIsNotSwallowedByItsPrimary() {
            UUID player = UUID.randomUUID();
            assertTrue(TalkCredits.claim(player, "guide_wilds"));
            assertTrue(TalkCredits.claim(player, "adventurers_guide"),
                    "an alias fired beside its primary must be de-duped on its own terms");
        }

        @Test
        void itIsPerPlayer() {
            assertTrue(TalkCredits.claim(UUID.randomUUID(), "guide"));
            assertTrue(TalkCredits.claim(UUID.randomUUID(), "guide"));
        }

        @Test
        void leavingTheServerClearsIt() {
            UUID player = UUID.randomUUID();
            assertTrue(TalkCredits.claim(player, "guide"));
            TalkCredits.clearPlayer(player);
            assertTrue(TalkCredits.claim(player, "guide"),
                    "a player who reconnects must not find their next conversation swallowed");
        }

        @Test
        void clearingOnePlayerLeavesAnotherAlone() {
            UUID left = UUID.randomUUID();
            UUID stayed = UUID.randomUUID();
            TalkCredits.claim(left, "guide");
            TalkCredits.claim(stayed, "guide");
            TalkCredits.clearPlayer(left);
            assertFalse(TalkCredits.claim(stayed, "guide"));
        }

        @Test
        void thereIsNothingToClaimForABlankId() {
            assertFalse(TalkCredits.claim(UUID.randomUUID(), "  "));
        }
    }

    @Nested
    class TheSinks {

        @Test
        void nothingIsListeningUntilSomethingRegisters() {
            assertFalse(TalkCredits.hasAny());
            TalkCredits.register("mymod", "MyMod", c -> { });
            assertTrue(TalkCredits.hasAny());
        }

        @Test
        void everyRegisteredSinkIsToldOnce() {
            List<String> told = new ArrayList<>();
            TalkCredits.register("first", "A", c -> told.add("first:" + c.npcId()));
            TalkCredits.register("second", "B", c -> told.add("second:" + c.npcId()));

            TalkCredits.dispatch(UUID.randomUUID(), credit("guide"));

            assertEquals(List.of("first:guide", "second:guide"), told);
        }

        @Test
        void aSinkThatThrowsCostsOnlyItsOwnCredit() {
            List<String> told = new ArrayList<>();
            TalkCredits.register("broken", "A", c -> {
                throw new IllegalStateException("this mod is having a day");
            });
            TalkCredits.register("working", "B", c -> told.add(c.npcId()));

            TalkCredits.dispatch(UUID.randomUUID(), credit("guide"));

            assertEquals(List.of("guide"), told, "one mod's failure must not cost another its quest step");
            assertEquals(1L, TalkCredits.info().get("broken").failures());
        }

        @Test
        void registeringTwiceReplacesRatherThanDoublingTheCredit() {
            List<String> told = new ArrayList<>();
            TalkCredits.register("mymod", "A", c -> told.add("old"));
            TalkCredits.register("mymod", "A", c -> told.add("new"));

            TalkCredits.dispatch(UUID.randomUUID(), credit("guide"));

            assertEquals(List.of("new"), told, "a mod reloading itself must not credit twice");
        }

        @Test
        void aSinkSeesThePrimaryAndTheAliasesSeparately() {
            List<List<String>> seen = new ArrayList<>();
            TalkCredits.register("mymod", "A", c -> seen.add(c.aliases()));

            TalkCredits.dispatch(UUID.randomUUID(), credit("guide_wilds", "adventurers_guide", "Guide_Wilds"));

            assertEquals(List.of(List.of("adventurers_guide")), seen,
                    "the primary must not appear in the alias pass, however it is spelled, or one "
                            + "conversation counts twice");
        }
    }
}
