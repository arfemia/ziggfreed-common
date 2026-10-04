package com.ziggfreed.common.calendar.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.calendar.CalendarFixtures;

/** A calendar spawn file: the event it rides, the rule it writes, its precedence and the verbatim body. */
class CalendarSpawnAssetTest {

    @Test
    void everyLeafDecodesAndTheBodyKeepsItsIntegers() {
        CalendarSpawnAsset spawn = CalendarFixtures.spawn("Test_Ghouls", """
                { "Event": "Spring_Fair", "Rule": "Spring_Fair_Ghouls", "Priority": 5,
                  "Spawn": { "Environments": ["Env_Test_Forest"],
                             "NPCs": [ { "Id": "Test_Ghoul", "Weight": 10 } ] } }
                """);
        assertEquals("spring_fair", spawn.eventId());
        assertEquals("spring_fair_ghouls", spawn.ruleId());
        assertEquals(5, spawn.priority());
        assertTrue(spawn.spawnJson().contains("\"Weight\":10"), "an integer leaf stays an integer for the engine's codec");
    }

    @Test
    void theRuleDefaultsToTheFileIdAndPriorityToZero() {
        CalendarSpawnAsset spawn = CalendarFixtures.spawn("Test_Bats",
                "{ \"Event\": \"Spring_Fair\", \"Spawn\": { \"Environments\": [\"Env_Test_Cave\"] } }");
        assertEquals("test_bats", spawn.ruleId());
        assertEquals(0, spawn.priority());
    }

    @Test
    void aSpawnThatIsNotAnObjectHasNoBody() {
        assertNull(CalendarFixtures.spawn("Test_Bad", "{ \"Event\": \"Spring_Fair\", \"Spawn\": [1, 2] }").spawnJson());
        assertNull(CalendarFixtures.spawn("Test_None", "{ \"Event\": \"Spring_Fair\" }").spawnJson());
        assertNull(CalendarFixtures.spawn("Test_No_Event", "{ \"Spawn\": { } }").eventId());
    }
}
