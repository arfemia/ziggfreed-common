package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.event.EncounterPhaseChangedEvent;
import com.ziggfreed.common.encounter.event.EncounterWipedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;

/**
 * The whole decision this producer makes, with no server anywhere near it: who gets which kind on
 * each beat, what an author's {@code Target} and {@code Qualifier} read as, and what rides in the
 * payload. The engine half (resolving a player, hopping onto their world) is the shared bus
 * dispatch and lands behind in-game smoke.
 */
class ZigEncounterProducerTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    /** One recorded moment, in the values a content author addresses plus the payload it carried. */
    private record Fired(@Nonnull UUID playerId, @Nonnull String kind, @Nonnull String target,
            @Nullable String qualifier, @Nonnull EncounterPayload payload) {
    }

    private static ParticipantShare share(@Nonnull UUID who, double share, boolean credited) {
        return new ParticipantShare(who, "player", share, credited, share * 100.0, 10.0, 60.0, false);
    }

    private static EncounterDefeatedEvent defeat(@Nullable String difficulty, ParticipantShare... shares) {
        List<ParticipantShare> list = List.of(shares);
        List<UUID> ids = list.stream().map(ParticipantShare::playerId).toList();
        return new EncounterDefeatedEvent(RUN, "Kweebec_Warden", null, null, "Warden", list, ids,
                Map.of(), Map.of(), 240.0, 1, difficulty, ALICE);
    }

    private static EncounterWipedEvent wipe(ParticipantShare... shares) {
        List<ParticipantShare> list = List.of(shares);
        List<UUID> ids = list.stream().map(ParticipantShare::playerId).toList();
        return new EncounterWipedEvent(RUN, "Kweebec_Warden", null, list, ids, Map.of(), 90.0, 3, true,
                "Phase_2", 0.4);
    }

    private static List<Fired> record(@Nonnull java.util.function.Consumer<ZigEncounterProducer.EncounterSink> fanOut) {
        List<Fired> fired = new ArrayList<>();
        fanOut.accept((playerId, kind, target, qualifier, payload) ->
                fired.add(new Fired(playerId, kind, target, qualifier, payload)));
        return fired;
    }

    private static List<Fired> of(@Nonnull List<Fired> fired, @Nonnull String kind) {
        return fired.stream().filter(f -> f.kind().equals(kind)).toList();
    }

    // ==================== a defeat ====================

    @Test
    void aDefeatCreditsEveryParticipantWithTheAttemptAndOnlyTheCreditedWithTheWin() {
        EncounterDefeatedEvent event = defeat("hard",
                share(ALICE, 1.0, true), share(BOB, 0.4, true), share(CAROL, 0.02, false));

        List<Fired> fired = record(sink -> ZigEncounterProducer.fanOutDefeated(event, sink));

        List<Fired> attempts = of(fired, ZigEncounterProducer.KIND_ATTEMPT);
        List<Fired> wins = of(fired, ZigEncounterProducer.KIND_DEFEATED);
        assertEquals(List.of(ALICE, BOB, CAROL), attempts.stream().map(Fired::playerId).toList(),
                "everybody the ledger saw fought it");
        assertEquals(List.of(ALICE, BOB), wins.stream().map(Fired::playerId).toList(),
                "a share under the minimum is attempt credit only");
        assertEquals(attempts.size() + wins.size(), fired.size(), "nothing else went out");
    }

    @Test
    void theAttemptArrivesBeforeTheWinForTheSamePlayer() {
        List<Fired> fired = record(sink ->
                ZigEncounterProducer.fanOutDefeated(defeat(null, share(ALICE, 1.0, true)), sink));

        assertEquals(List.of(ZigEncounterProducer.KIND_ATTEMPT, ZigEncounterProducer.KIND_DEFEATED),
                fired.stream().map(Fired::kind).toList(),
                "the fight ended, and you won it: the order a listener would describe them in");
    }

    @Test
    void aDefeatTargetsTheScriptAndQualifiesOnTheDifficulty() {
        List<Fired> fired = record(sink ->
                ZigEncounterProducer.fanOutDefeated(defeat("hard", share(ALICE, 1.0, true)), sink));

        for (Fired f : fired) {
            assertEquals("Kweebec_Warden", f.target(), "the SCRIPT id, never the creature's");
            assertEquals("hard", f.qualifier());
        }
    }

    @Test
    void aRunWithNoDifficultyQualifiesOnNothing() {
        List<Fired> fired = record(sink ->
                ZigEncounterProducer.fanOutDefeated(defeat("  ", share(ALICE, 1.0, true)), sink));

        assertNull(fired.get(0).qualifier(), "a blank label is no label: a qualifier is optional everywhere");
    }

    @Test
    void thePayloadCarriesTheEventAndThatParticipantsOwnShare() {
        EncounterDefeatedEvent event = defeat("hard", share(ALICE, 1.0, true), share(BOB, 0.4, true));

        List<Fired> wins = of(record(sink -> ZigEncounterProducer.fanOutDefeated(event, sink)),
                ZigEncounterProducer.KIND_DEFEATED);

        Fired bob = wins.stream().filter(f -> f.playerId().equals(BOB)).findFirst().orElseThrow();
        assertSame(event, bob.payload().defeated(), "the whole event rides along");
        assertEquals(0.4, bob.payload().share(), "the share is HIS, not the top contributor's");
        assertEquals(RUN.toString(), bob.payload().creditKey(),
                "every fire of one beat shares the run as its credit");
        assertNull(bob.payload().wiped());
    }

    @Test
    void aDefeatNobodyWasCreditedForStillReportsItself() {
        int credited = ZigEncounterProducer.fanOutDefeated(defeat(null), (a, b, c, d, e) -> { });

        assertEquals(0, credited, "a headless fight with no participant dispatches for zero, never throws");
    }

    // ==================== a wipe ====================

    @Test
    void aWipeCreditsEveryParticipantWithTheAttemptAndNobodyWithTheWin() {
        EncounterWipedEvent event = wipe(share(ALICE, 1.0, true), share(BOB, 0.01, false));

        List<Fired> fired = record(sink -> ZigEncounterProducer.fanOutWiped(event, sink));

        assertEquals(2, fired.size());
        assertTrue(fired.stream().allMatch(f -> f.kind().equals(ZigEncounterProducer.KIND_ATTEMPT)));
        assertTrue(of(fired, ZigEncounterProducer.KIND_DEFEATED).isEmpty(), "a wipe is never a win");
        assertSame(event, fired.get(0).payload().wiped());
        assertEquals("Kweebec_Warden", fired.get(0).target());
    }

    // ==================== a phase ====================

    @Test
    void aPhaseBeatReachesEveryLiveMemberWithThePhaseAsTheQualifier() {
        EncounterPhaseChangedEvent event = new EncounterPhaseChangedEvent(RUN, "Kweebec_Warden", "Phase_1",
                "Phase_2", 2, List.of(ALICE, BOB), 30_000L);

        List<Fired> fired = record(sink -> ZigEncounterProducer.fanOutPhase(event, sink));

        assertEquals(List.of(ALICE, BOB), fired.stream().map(Fired::playerId).toList(),
                "a member who has dealt no damage yet is still in the fight");
        for (Fired f : fired) {
            assertEquals(ZigEncounterProducer.KIND_PHASE, f.kind());
            assertEquals("Phase_2", f.qualifier(), "the script's own state name, exactly as signalled");
            assertNotNull(f.payload().phaseChanged());
            assertNull(f.payload().share(), "a phase is not a settlement, so nobody has a share yet");
        }
    }
}
