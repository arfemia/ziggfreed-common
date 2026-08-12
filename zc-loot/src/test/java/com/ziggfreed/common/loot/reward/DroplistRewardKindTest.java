package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The droplist kind's PARAMETER FOLD: which id it rolls, how many times, and where the stacks land.
 * The roll and the spawn themselves are engine calls covered by the in-game smoke pass, but every
 * decision the kind makes BEFORE touching the engine is decided here, and each of these cases is a
 * way a payout could silently go somewhere nobody meant it to.
 */
class DroplistRewardKindTest {

    @AfterEach
    void reset() {
        RewardKinds.clear();
    }

    @Nested
    class TheDroplistId {

        @Test
        void readsEitherSpelling() {
            assertEquals("Drop_Trork_Warrior",
                    DroplistRewardKind.droplistIdOf(RewardSpec.of("droplist", "Droplist", "Drop_Trork_Warrior")));
            assertEquals("Drop_Trork_Warrior",
                    DroplistRewardKind.droplistIdOf(RewardSpec.of("droplist", "Id", "Drop_Trork_Warrior")));
        }

        @Test
        void aNamedListWinsOverTheAliasWhenBothAreWritten() {
            RewardSpec spec = RewardSpec.of("droplist",
                    Map.of("Droplist", "Drop_Named", "Id", "Drop_Alias"));
            assertEquals("Drop_Named", DroplistRewardKind.droplistIdOf(spec));
        }

        @Test
        void surroundingSpaceIsNotPartOfTheId() {
            assertEquals("Drop_Kweebec",
                    DroplistRewardKind.droplistIdOf(RewardSpec.of("droplist", "Droplist", "  Drop_Kweebec  ")));
        }

        @Test
        void anUnnamedOrBlankListIsNothingToRoll() {
            assertNull(DroplistRewardKind.droplistIdOf(RewardSpec.of("droplist")));
            assertNull(DroplistRewardKind.droplistIdOf(RewardSpec.of("droplist", "Droplist", "   ")));
        }
    }

    @Nested
    class TheRollCount {

        @Test
        void oneRollUnlessAskedForMore() {
            assertEquals(1, DroplistRewardKind.rollsOf(RewardSpec.of("droplist", "Droplist", "Drop_X")));
        }

        @Test
        void anAuthoredCountIsHonored() {
            assertEquals(5, DroplistRewardKind.rollsOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Rolls", "5"))));
        }

        @Test
        void zeroOrNegativeStillRollsOnceRatherThanPayingNothing() {
            assertEquals(1, DroplistRewardKind.rollsOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Rolls", "0"))));
            assertEquals(1, DroplistRewardKind.rollsOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Rolls", "-4"))));
        }

        @Test
        void aRunawayCountIsCappedRatherThanSpawningAWorldOfEntities() {
            assertEquals(DroplistRewardKind.MAX_ROLLS, DroplistRewardKind.rollsOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Rolls", "1000000"))));
        }

        @Test
        void anUnreadableCountFallsBackToOne() {
            assertEquals(1, DroplistRewardKind.rollsOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Rolls", "lots"))));
        }
    }

    @Nested
    class ThePosition {

        @Test
        void anAuthoredPositionIsReadWholeAndInOrder() {
            double[] at = DroplistRewardKind.authoredPositionOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Position", "12.5,64,-30.25")));
            assertNotNull(at);
            assertArrayEquals(new double[] {12.5, 64.0, -30.25}, at);
        }

        @Test
        void spacingAroundTheNumbersDoesNotMatter() {
            double[] at = DroplistRewardKind.authoredPositionOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Position", " 1 , 2 , 3 ")));
            assertNotNull(at);
            assertArrayEquals(new double[] {1.0, 2.0, 3.0}, at);
        }

        @Test
        void aCallerWritesThePositionInAtTheMomentItKnowsIt() {
            RewardSpec authored = RewardSpec.of("droplist", "Droplist", "Drop_X");
            assertNull(DroplistRewardKind.authoredPositionOf(authored));

            RewardSpec placed = authored.with("Position", 10.0 + "," + 70.0 + "," + 10.0);

            assertArrayEquals(new double[] {10.0, 70.0, 10.0}, DroplistRewardKind.authoredPositionOf(placed));
            assertNull(DroplistRewardKind.authoredPositionOf(authored),
                    "writing a position in must not mutate the spec the caller was handed");
        }

        @Test
        void noPositionMeansTheReceivingPlayerDecidesIt() {
            assertNull(DroplistRewardKind.authoredPositionOf(RewardSpec.of("droplist", "Droplist", "Drop_X")));
            assertNull(DroplistRewardKind.authoredPositionOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Position", "  "))));
        }

        @Test
        void aMalformedPositionReadsAsAbsentRatherThanAsWorldZero() {
            assertNull(DroplistRewardKind.authoredPositionOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Position", "12,64"))));
            assertNull(DroplistRewardKind.authoredPositionOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Position", "12,64,10,3"))));
            assertNull(DroplistRewardKind.authoredPositionOf(
                    RewardSpec.of("droplist", Map.of("Droplist", "Drop_X", "Position", "12,over there,10"))));
        }
    }

    @Nested
    class TheRegistration {

        @Test
        void registersUnderTheFrameworkOwnerAndDoesNotJoinTheItemKinds() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            DroplistRewardKind.registerInto(kinds);

            assertTrue(kinds.isRegistered(DroplistRewardKind.KIND));
            assertEquals(DroplistRewardKind.OWNER, kinds.info().get(DroplistRewardKind.KIND).owner());
            assertEquals(3, LootRewardKinds.parameterKeys().size(),
                    "a ground drop is not an inventory grant; it stays out of the item-kind table");
        }

        @Test
        void documentsItsOwnParameterKeys() {
            Map<String, List<String>> keys = DroplistRewardKind.parameterKeys();
            assertEquals(List.of("droplist", "rolls", "position"), keys.get(DroplistRewardKind.KIND));
        }
    }
}
