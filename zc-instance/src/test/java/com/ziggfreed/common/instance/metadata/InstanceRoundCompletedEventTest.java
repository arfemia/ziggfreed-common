package com.ziggfreed.common.instance.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.result.ResultKind;

/**
 * The shape a cross-mod listener is handed: an outcome it can read off the winners list alone, and
 * two lists it cannot be surprised by later.
 *
 * <p>Immutability is pinned rather than assumed because this event crosses a mod boundary: a
 * listener that could mutate {@code participants} would be editing the producer's own round state,
 * and a producer that kept mutating its list after the fire would change what an earlier listener
 * already acted on.
 */
class InstanceRoundCompletedEventTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static RoundMetadata round(ResultKind kind) {
        return RoundMetadata.builder("kweebec")
                .modeId("chase")
                .presetId("moonlit")
                .playerCount(2)
                .durationSeconds(420L)
                .resultKind(kind)
                .build();
    }

    @Test
    void theEnumBuilderOverloadWritesTheConventionalTag() {
        assertEquals("WIN", round(ResultKind.WIN).resultKind());
        assertEquals("ABORT", round(ResultKind.ABORT).resultKind());
    }

    @Test
    void aCoOpWinNamesEverybodyAsAWinner() {
        InstanceRoundCompletedEvent event = new InstanceRoundCompletedEvent(
                round(ResultKind.WIN), List.of(ALICE, BOB), List.of(ALICE, BOB));

        assertTrue(event.isWin());
        assertEquals(List.of(ALICE, BOB), event.winners());
        assertEquals(event.participants(), event.winners());
    }

    @Test
    void aPvpWinNamesOnlyTheWinningSide() {
        InstanceRoundCompletedEvent event = new InstanceRoundCompletedEvent(
                round(ResultKind.WIN), List.of(ALICE, BOB), List.of(ALICE));

        assertTrue(event.isWin());
        assertEquals(List.of(ALICE), event.winners());
        assertEquals(List.of(ALICE, BOB), event.participants());
    }

    @Test
    void aLossOrAnAbortNamesNoWinnerAtAll() {
        InstanceRoundCompletedEvent lost = new InstanceRoundCompletedEvent(
                round(ResultKind.LOSS), List.of(ALICE, BOB), List.of());
        InstanceRoundCompletedEvent aborted = new InstanceRoundCompletedEvent(
                round(ResultKind.ABORT), List.of(ALICE), List.of());

        assertFalse(lost.isWin());
        assertFalse(aborted.isWin());
        assertTrue(lost.winners().isEmpty());
    }

    @Test
    void bothListsAreCopiedAtConstructionSoALaterEditCannotReachTheListener() {
        List<UUID> participants = new ArrayList<>(List.of(ALICE));
        List<UUID> winners = new ArrayList<>(List.of(ALICE));

        InstanceRoundCompletedEvent event =
                new InstanceRoundCompletedEvent(round(ResultKind.WIN), participants, winners);

        participants.add(BOB);
        winners.clear();

        assertEquals(List.of(ALICE), event.participants());
        assertEquals(List.of(ALICE), event.winners());
        assertTrue(event.isWin(), "clearing the producer's own list must not un-win the round");
    }

    @Test
    void neitherListCanBeEditedByAListener() {
        InstanceRoundCompletedEvent event = new InstanceRoundCompletedEvent(
                round(ResultKind.WIN), List.of(ALICE), List.of(ALICE));

        assertThrows(UnsupportedOperationException.class, () -> event.participants().add(BOB));
        assertThrows(UnsupportedOperationException.class, () -> event.winners().clear());
    }

    @Test
    void theMetadataRidesThroughUntouched() {
        RoundMetadata metadata = round(ResultKind.WIN);
        InstanceRoundCompletedEvent event =
                new InstanceRoundCompletedEvent(metadata, List.of(ALICE), List.of(ALICE));

        assertEquals(metadata, event.metadata());
        assertEquals("kweebec", event.metadata().modId());
        assertEquals("chase", event.metadata().modeId());
        assertEquals("moonlit", event.metadata().presetId());
    }
}
