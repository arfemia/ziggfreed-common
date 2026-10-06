package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.runtime.Moment;

/**
 * The kill table: a kill pays every row whose groups hold the dead NPC's role, summed per reputation (a
 * loss allowed), a switched-off reputation pays nothing, and the listener moves standing on a LATER world
 * task, never inside the engine's death system.
 */
class ReputationKillsTest {

    private FakeReputationNative engine;
    private ReputationFixtures.RecordingFanOut fanOut;
    private ReputationService service;

    @BeforeEach
    void seed() {
        ReputationFixtures.reset();
        engine = ReputationFixtures.engine().group("Test_Hallowed", 0);
        engine.members.put("Test_Event_Mobs", Set.of("Test_Pumpkin_Knight"));
        engine.members.put("Test_Raiders", Set.of("Test_Pumpkin_Knight", "Test_Bandit"));
        fanOut = new ReputationFixtures.RecordingFanOut();
        service = new ReputationService(engine, fanOut);
        ReputationFixtures.loadCompanions(Map.of(
                "test_hallowed", ReputationFixtures.companion("Test_Hallowed", """
                        { "Kills": [ { "NPCGroups": [ "Test_Event_Mobs" ], "Amount": 2 },
                                     { "NPCGroups": [ "Test_Raiders" ], "Amount": 3 } ] }
                        """),
                "test_old_jack", ReputationFixtures.companion(ReputationFixtures.OLD_JACK, """
                        { "Kills": [ { "NPCGroups": [ "Test_Raiders" ], "Amount": -5 } ] }
                        """)));
    }

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    private Map<String, Integer> amounts(String role) {
        return ReputationKills.amounts(service.all(), role, engine::roleInGroup);
    }

    private static Moment moment(String kind, String target) {
        return new Moment(kind, target, null, 1L, null, null, null, null, null, null, null);
    }

    @Test
    void aKillPaysEveryRowWhoseGroupsHoldTheRoleSummedPerReputation() {
        assertEquals(Map.of("Test_Hallowed", 5, "Test_Old_Jack", -5), amounts("Test_Pumpkin_Knight"));
        assertEquals(Map.of("Test_Hallowed", 3, "Test_Old_Jack", -5), amounts("Test_Bandit"));
        assertTrue(amounts("Test_Rabbit").isEmpty(), "a role no row holds pays nothing");
        assertTrue(amounts(" ").isEmpty());
    }

    @Test
    void aSwitchedOffReputationPaysNoKills() {
        ReputationFixtures.loadCompanions(Map.of(
                "test_hallowed", ReputationFixtures.companion("Test_Hallowed", "{ \"Enabled\": false, "
                        + "\"Kills\": [ { \"NPCGroups\": [ \"Test_Raiders\" ], \"Amount\": 3 } ] }")));
        assertTrue(amounts("Test_Bandit").isEmpty());
    }

    @Test
    void theListenerHearsOnlyKillsAndMovesStandingLaterNeverInsideTheDeath() {
        List<Runnable> queued = new ArrayList<>();
        ReputationKillListener listener = new ReputationKillListener(service, (moment, task) -> queued.add(task));
        listener.react(moment("BREAK_BLOCK", "Test_Pumpkin_Knight"));
        assertTrue(queued.isEmpty(), "only a kill counts");
        listener.react(moment("KILL_ENTITY", "Test_Rabbit"));
        assertTrue(queued.isEmpty(), "a kill no reputation counts schedules nothing");
        listener.react(moment("kill_entity", "Test_Pumpkin_Knight"));
        assertEquals(1, queued.size(), "one task per kill, whatever the kind's casing");
        assertTrue(engine.writes.isEmpty(), "nothing is written inside the death system's dispatch");
    }

    @Test
    void theDeferredTaskMovesEachReputationOnceNamingTheKill() {
        ReputationKillListener listener = new ReputationKillListener(service, (moment, task) -> { });
        listener.apply(null, null, Map.of("Test_Hallowed", 5), "kill:Test_Pumpkin_Knight");
        assertEquals(List.of("Test_Hallowed 5"), engine.writes);
        assertEquals("kill:Test_Pumpkin_Knight", fanOut.changes.get(0).source());
    }
}
