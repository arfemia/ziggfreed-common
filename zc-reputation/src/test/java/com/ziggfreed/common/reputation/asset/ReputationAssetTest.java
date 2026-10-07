package com.ziggfreed.common.reputation.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.ReputationFixtures;

/** The companion file: every leaf, its defaults, case-free rank names, and leaf-by-leaf inheritance. */
class ReputationAssetTest {

    private static final String FULL = """
            { "Enabled": true,
              "Text": { "TitleKey": "test.faction.name", "FlavorKey": "test.faction.flavor" },
              "Icon": "Test_Icon",
              "Order": 3,
              "Gear": { "Stat": "Reputation_Test_Faction" },
              "Cap": 5000,
              "Ranks": { "$Comment": "names this faction gives the ranks",
                         "Friendly": { "Name": "test.faction.rank.friendly" } },
              "Kills": [ { "NPCGroups": [ "Test_Raiders", " " ], "Amount": -5 } ],
              "Beyond": { "Every": 1000,
                          "Rewards": [ { "Kind": "Item", "Params": { "Item": "Test_Cache", "Count": 1 } } ] } }
            """;

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    @Test
    void everyLeafDecodes() {
        ReputationAsset rep = ReputationFixtures.companion("Test_Faction", FULL);
        assertTrue(rep.isEnabled());
        assertEquals("test.faction.name", rep.titleKey());
        assertEquals("test.faction.flavor", rep.flavorKey());
        assertEquals("Test_Icon", rep.icon());
        assertEquals(3, rep.order());
        assertEquals("Reputation_Test_Faction", rep.gearStat());
        assertEquals(Integer.valueOf(5000), rep.cap());
        assertEquals(Map.of("Friendly", "test.faction.rank.friendly"), rep.rankNames(),
                "a $Comment inside the map is documentation, not a rank");
        assertEquals(1, rep.kills().size());
        assertEquals(List.of("Test_Raiders"), rep.kills().get(0).npcGroups(), "a blank group id is dropped");
        assertEquals(-5, rep.kills().get(0).amount(), "a kill may cost standing");
        assertEquals(1000, rep.beyondEvery());
        assertEquals(1, rep.beyondRewards().size());
        assertEquals("Item", rep.beyondRewards().get(0).kind());
    }

    @Test
    void anUnauthoredFileIsOnSortsFirstAndAddsNothing() {
        ReputationAsset rep = ReputationFixtures.companion("Bare", "{}");
        assertTrue(rep.isEnabled(), "unauthored Enabled is true");
        assertNull(rep.titleKey());
        assertNull(rep.icon());
        assertEquals(0, rep.order());
        assertNull(rep.gearStat());
        assertNull(rep.cap(), "no cap: standing climbs the whole ladder");
        assertTrue(rep.rankNames().isEmpty());
        assertTrue(rep.kills().isEmpty());
        assertEquals(0, rep.beyondEvery());
        assertTrue(rep.beyondRewards().isEmpty());
    }

    @Test
    void aRankNameIsFoundWithoutRegardToCase() {
        ReputationAsset rep = ReputationFixtures.companion("Test_Faction", FULL);
        assertEquals("test.faction.rank.friendly", rep.rankNameKey("FRIENDLY"));
        assertNull(rep.rankNameKey("Honored"), "a rank the file does not name reads the library's own word");
    }

    @Test
    void aBeyondOfZeroOrLessPaysNothing() {
        assertEquals(0, ReputationFixtures.companion("A", "{ \"Beyond\": { \"Every\": 0 } }").beyondEvery());
        assertEquals(0, ReputationFixtures.companion("B", "{ \"Beyond\": { \"Every\": -10 } }").beyondEvery());
    }

    @Test
    void aChildMergesLeafByLeafAndReplacesItsKillsWhole() {
        ReputationAsset parent = ReputationFixtures.companion("Test_Faction", FULL);
        ReputationAsset child = ReputationFixtures.companion("Test_Faction", """
                { "Cap": 9000,
                  "Ranks": { "Honored": { "Name": "test.faction.rank.honored" } },
                  "Kills": [ { "NPCGroups": [ "Test_Wolves" ], "Amount": 2 } ],
                  "Beyond": { "Every": 2000 } }
                """, parent);
        assertEquals(Integer.valueOf(9000), child.cap());
        assertEquals("test.faction.name", child.titleKey(), "the Text it did not restate is kept");
        assertEquals("Reputation_Test_Faction", child.gearStat());
        assertEquals("test.faction.rank.friendly", child.rankNameKey("Friendly"), "rank names merge by rank id");
        assertEquals("test.faction.rank.honored", child.rankNameKey("Honored"));
        assertEquals(1, child.kills().size(), "Kills replaces the parent's list whole");
        assertEquals(List.of("Test_Wolves"), child.kills().get(0).npcGroups());
        assertEquals(2000, child.beyondEvery());
        assertEquals(1, child.beyondRewards().size(), "Beyond merges leaf by leaf, so the parent's Rewards stay");
    }

    @Test
    void aRankAboveTheTopCarriesItsFloorAndAChildMayMoveIt() {
        ReputationAsset rep = ReputationFixtures.companion("Test_Festival", """
                { "Ranks": { "Friendly": { "Name": "test.festival.rank.friendly" },
                             "Test_Wayfarer": { "Name": "test.festival.rank.wayfarer", "From": 60000 } } }
                """);
        assertEquals(Map.of("Test_Wayfarer", 60_000), rep.tierFloors(), "only an entry with a From is a tier");
        assertEquals("test.festival.rank.wayfarer", rep.rankNameKey("test_wayfarer"));
        ReputationAsset child = ReputationFixtures.companion("Test_Festival", """
                { "Ranks": { "Test_Wayfarer": { "From": 70000 } } }
                """, rep);
        assertEquals(Map.of("Test_Wayfarer", 70_000), child.tierFloors());
        assertEquals("test.festival.rank.wayfarer", child.rankNameKey("Test_Wayfarer"),
                "the entry merges leaf by leaf, so the name it did not restate stays");
    }
}
