package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRoundStartedEvent;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.instance.result.ResultKind;

/**
 * The whole decision this producer makes, with no server anywhere near it: who gets which kind, and
 * what an author's {@code Target} and {@code Qualifier} actually read as.
 *
 * <p>The engine half (resolving a player, picking their world, hopping onto its thread) needs a live
 * universe and lands behind in-game smoke, exactly like the rest of the library's engine-touching
 * paths. Everything a piece of AUTHORED CONTENT can be wrong about is here.
 */
class ZigInstanceRoundProducerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    /** One recorded moment, in the four values a content author addresses. */
    private record Fired(@Nonnull UUID playerId, @Nonnull String kind, @Nonnull String target,
            @Nullable String qualifier) {
    }

    private static List<Fired> fanOut(@Nonnull InstanceRoundCompletedEvent event) {
        List<Fired> fired = new ArrayList<>();
        ZigInstanceRoundProducer.fanOut(event,
                (playerId, kind, target, qualifier) ->
                        fired.add(new Fired(playerId, kind, target, qualifier)));
        return fired;
    }

    private static InstanceRoundCompletedEvent event(@Nonnull RoundMetadata metadata,
            @Nonnull List<UUID> participants, @Nonnull List<UUID> winners) {
        return new InstanceRoundCompletedEvent(metadata, participants, winners);
    }

    private static RoundMetadata chase(@Nonnull ResultKind kind) {
        return RoundMetadata.builder("kweebec").modeId("chase").presetId("moonlit")
                .resultKind(kind).build();
    }

    // ==================== who gets what ====================

    @Test
    void onlyWinnersAreCreditedWithTheWinAndTheyAlsoGetTheEnding() {
        List<Fired> fired = fanOut(event(chase(ResultKind.WIN), List.of(ALICE, BOB), List.of(ALICE)));

        assertEquals(List.of(
                        ZigInstanceRoundProducer.KIND_ENDED,
                        ZigInstanceRoundProducer.KIND_ENDED,
                        ZigInstanceRoundProducer.KIND_WON),
                fired.stream().map(Fired::kind).toList(),
                "a winner earns both moments, once each, and the ending comes first");
        assertEquals(List.of(ALICE),
                fired.stream().filter(f -> ZigInstanceRoundProducer.KIND_WON.equals(f.kind()))
                        .map(Fired::playerId).toList());
    }

    @Test
    void aRoundNobodyWonFiresNoWinAtAll() {
        List<Fired> fired = fanOut(event(chase(ResultKind.ABORT), List.of(ALICE), List.of()));

        assertTrue(fired.stream().noneMatch(f -> ZigInstanceRoundProducer.KIND_WON.equals(f.kind())));
    }

    @Test
    void aRoundWithNobodyInItFiresNothing() {
        assertTrue(fanOut(event(chase(ResultKind.ABORT), List.of(), List.of())).isEmpty());
    }

    // ==================== a lost round and a started one ====================

    @Test
    void aRoundNobodyWonCreditsEveryParticipantWithTheEndingThenTheLoss() {
        List<Fired> fired = fanOut(event(chase(ResultKind.LOSS), List.of(ALICE, BOB), List.of()));

        assertEquals(List.of(
                        new Fired(ALICE, ZigInstanceRoundProducer.KIND_ENDED, "kweebec:chase", "moonlit"),
                        new Fired(BOB, ZigInstanceRoundProducer.KIND_ENDED, "kweebec:chase", "moonlit"),
                        new Fired(ALICE, ZigInstanceRoundProducer.KIND_LOST, "kweebec:chase", "moonlit"),
                        new Fired(BOB, ZigInstanceRoundProducer.KIND_LOST, "kweebec:chase", "moonlit")),
                fired, "a failed run is played and lost by everyone in it, the ending first");
    }

    @Test
    void aRoundSomebodyWonCreditsNoLossEvenToAParticipantWhoDidNotWin() {
        List<Fired> fired = fanOut(event(chase(ResultKind.WIN), List.of(ALICE, BOB), List.of(ALICE)));

        assertTrue(fired.stream().noneMatch(f -> ZigInstanceRoundProducer.KIND_LOST.equals(f.kind())),
                "lost means nobody won; a participant of a won round has the ending alone");
    }

    @Test
    void everyPlayerAStartNamesIsCreditedWithTheStartUnderTheRoundsTargetAndQualifier() {
        RoundMetadata run = RoundMetadata.builder("dungeon").modeId("Hard").presetId("Crypt").playerCount(2).build();
        List<Fired> fired = new ArrayList<>();
        ZigInstanceRoundProducer.fanOutStarted(new InstanceRoundStartedEvent(run, List.of(ALICE, BOB)),
                (playerId, kind, target, qualifier) -> fired.add(new Fired(playerId, kind, target, qualifier)));

        assertEquals(List.of(
                        new Fired(ALICE, ZigInstanceRoundProducer.KIND_STARTED, "dungeon:Hard", "Crypt"),
                        new Fired(BOB, ZigInstanceRoundProducer.KIND_STARTED, "dungeon:Hard", "Crypt")),
                fired, "a start reads as its end does, so 'dungeon:' or 'dungeon:hard' counts it");
    }

    @Test
    void aStartNamingNobodyFiresNothing() {
        List<Fired> fired = new ArrayList<>();
        ZigInstanceRoundProducer.fanOutStarted(
                new InstanceRoundStartedEvent(RoundMetadata.builder("dungeon").build(), List.of()),
                (playerId, kind, target, qualifier) -> fired.add(new Fired(playerId, kind, target, qualifier)));

        assertTrue(fired.isEmpty());
    }

    // ==================== what an author matches on ====================

    @Test
    void theTargetIsModAndModeSoAModPrefixAddressesEveryMode() {
        assertEquals("kweebec:chase", ZigInstanceRoundProducer.target(chase(ResultKind.WIN)));
        assertTrue(ZigInstanceRoundProducer.target(chase(ResultKind.WIN)).startsWith("kweebec:"));
    }

    @Test
    void aRoundWithNoModeStillCarriesTheColonSoTheModPrefixKeepsMatching() {
        RoundMetadata noMode = RoundMetadata.builder("kweebec").build();

        assertEquals("kweebec:", ZigInstanceRoundProducer.target(noMode));
        assertTrue(ZigInstanceRoundProducer.target(noMode).startsWith("kweebec:"),
                "dropping the colon would make a mod's own prefix stop addressing exactly the "
                        + "rounds that named no mode");
    }

    @Test
    void theQualifierIsThePresetAndAbsentWhenTheRoundNamedNone() {
        assertEquals("moonlit", ZigInstanceRoundProducer.qualifier(chase(ResultKind.WIN)));
        assertNull(ZigInstanceRoundProducer.qualifier(RoundMetadata.builder("kweebec").build()));
        assertNull(ZigInstanceRoundProducer.qualifier(
                RoundMetadata.builder("kweebec").presetId("   ").build()));
    }

    @Test
    void theTargetAndQualifierAreComposedOnceAndCarriedToEveryPlayer() {
        List<Fired> fired = fanOut(event(chase(ResultKind.WIN), List.of(ALICE, BOB), List.of(ALICE, BOB)));

        assertTrue(fired.stream().allMatch(f -> "kweebec:chase".equals(f.target())));
        assertTrue(fired.stream().allMatch(f -> "moonlit".equals(f.qualifier())));
    }
}
