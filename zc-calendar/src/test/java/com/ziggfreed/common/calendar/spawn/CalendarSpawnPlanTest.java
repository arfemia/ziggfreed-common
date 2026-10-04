package com.ziggfreed.common.calendar.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.calendar.CalendarFixtures;
import com.ziggfreed.common.calendar.asset.CalendarSpawnAsset;

/** Which file owns each rule, what each rule should hold, and the retirement that replaces removal. */
class CalendarSpawnPlanTest {

    static final String GHOULS = """
            { "Event": "Hallows_Eve", "Rule": "Hallows_Eve_Ghouls",
              "Spawn": { "Environments": ["Env_Test_Forest"], "NPCs": [ { "Id": "Test_Ghoul", "Weight": 10 } ] } }
            """;

    static final String GHOULS_HARVEST = """
            { "Event": "Harvest_Moon", "Rule": "Hallows_Eve_Ghouls", "Priority": 10,
              "Spawn": { "Environments": ["Env_Test_Forest"], "NPCs": [ { "Id": "Test_Ghoul", "Weight": 30 } ] } }
            """;

    private final CalendarSpawnAsset base = CalendarFixtures.spawn("Hallows_Eve_Ghouls_Base", GHOULS);
    private final CalendarSpawnAsset boost = CalendarFixtures.spawn("Harvest_Moon_Ghouls", GHOULS_HARVEST);

    @Test
    void theHighestPriorityRunningEventOwnsASharedRule() {
        List<CalendarSpawnAsset> all = List.of(base, boost);
        assertSame(base, CalendarSpawnPlan.owners(all, Set.of("hallows_eve")).get("hallows_eve_ghouls"));
        assertSame(boost, CalendarSpawnPlan.owners(all, Set.of("hallows_eve", "harvest_moon")).get("hallows_eve_ghouls"));
        assertTrue(CalendarSpawnPlan.owners(all, Set.of()).isEmpty());
    }

    @Test
    void aTieGoesToTheFileWhoseIdSortsFirst() {
        CalendarSpawnAsset a = CalendarFixtures.spawn("A_Ghouls", GHOULS);
        CalendarSpawnAsset b = CalendarFixtures.spawn("B_Ghouls", GHOULS);
        assertSame(a, CalendarSpawnPlan.owners(List.of(b, a), Set.of("hallows_eve")).get("hallows_eve_ghouls"));
    }

    @Test
    void rulesMatchWithoutRegardToCase() {
        CalendarSpawnAsset shouting = CalendarFixtures.spawn("Shouting", GHOULS.replace("Hallows_Eve_Ghouls", "HALLOWS_EVE_GHOULS"));
        assertEquals(Set.of("hallows_eve_ghouls"),
                CalendarSpawnPlan.owners(List.of(shouting), Set.of("hallows_eve")).keySet());
    }

    @Test
    void aRuleNobodyOwnsAnyMoreIsRetiredNeverDropped() {
        Map<String, String> written = Map.of("hallows_eve_ghouls", base.spawnJson());
        Map<String, String> target = CalendarSpawnPlan.target(written, Map.of());
        assertEquals(CalendarSpawnPlan.retired(base.spawnJson()), target.get("hallows_eve_ghouls"));
        assertTrue(CalendarSpawnPlan.target(Map.of(), Map.of()).isEmpty(), "a rule never written is never written retired");
    }

    @Test
    void retiringZeroesEveryMoonPhaseKeepsTheRestAndIsIdempotent() {
        String retired = CalendarSpawnPlan.retired(base.spawnJson());
        JsonObject body = JsonParser.parseString(retired).getAsJsonObject();
        assertEquals("[0]", body.get("MoonPhaseWeightModifiers").toString());
        assertEquals(10, body.getAsJsonArray("NPCs").get(0).getAsJsonObject().get("Weight").getAsInt());
        assertTrue(retired.contains("\"Weight\":10"));
        assertEquals(retired, CalendarSpawnPlan.retired(retired));
    }

    @Test
    void onlyChangedRulesAreWritten() {
        Map<String, String> written = Map.of("hallows_eve_ghouls", base.spawnJson());
        assertTrue(CalendarSpawnPlan.writes(written, Map.of("hallows_eve_ghouls", base.spawnJson())).isEmpty());
        assertEquals(Map.of("hallows_eve_ghouls", boost.spawnJson()),
                CalendarSpawnPlan.writes(written, Map.of("hallows_eve_ghouls", boost.spawnJson())));
    }

    @Test
    void twoOwnedRulesClaimingOneRoleInOneEnvironmentAreReported() {
        CalendarSpawnAsset other = CalendarFixtures.spawn("Other_Ghouls", GHOULS.replace("Hallows_Eve_Ghouls", "Other_Rule"));
        Map<String, List<String>> shared = CalendarSpawnPlan.sharedPairs(
                CalendarSpawnPlan.owners(List.of(base, other), Set.of("hallows_eve")));
        assertEquals(Map.of("test_ghoul|env_test_forest", List.of("hallows_eve_ghouls", "other_rule")), shared);
    }

    /** The engine reports a malformed body when it reads it; the pair check only steps past what it cannot read. */
    @Test
    void anNpcWhoseIdIsNotPlainTextIsLeftOutOfThePairCheck() {
        CalendarSpawnAsset odd = CalendarFixtures.spawn("Odd_Ghouls", """
                { "Event": "Hallows_Eve", "Rule": "Odd_Rule",
                  "Spawn": { "Environments": ["Env_Test_Forest"],
                             "NPCs": [ { "Id": null }, { "Id": { "Role": "Test_Ghoul" } }, { "Id": "Test_Ghoul" } ] } }
                """);
        Map<String, List<String>> shared = CalendarSpawnPlan.sharedPairs(
                CalendarSpawnPlan.owners(List.of(base, odd), Set.of("hallows_eve")));
        assertEquals(Map.of("test_ghoul|env_test_forest", List.of("hallows_eve_ghouls", "odd_rule")), shared);
    }
}
