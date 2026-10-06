package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The reputation audit: a broken structure is an ERROR (a ladder below two ranks, a native group with no
 * NPCGroups key, a kill row with no groups); an id nothing loads is a WARNING; a sound setup says nothing;
 * a switched-off companion is skipped; the boot log names only the errors.
 */
class ReputationValidatorTest {

    private static final Predicate<String> NPC_GROUPS = Set.of("Test_Jack_Group", "Test_Raiders")::contains;
    private static final Predicate<String> STATS = Set.of("Reputation_Test")::contains;
    private static final Predicate<String> ITEMS = Set.of("Test_Icon")::contains;
    private static final List<ReputationNative.Group> GROUPS =
            List.of(new ReputationNative.Group("Test_Old_Jack", 0, List.of("Test_Jack_Group")));

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
        ReputationValidator.resetForTests();
    }

    private static List<Finding> audit(List<ReputationAsset> companions, List<ReputationNative.Group> groups,
            List<ReputationLadder.Rank> ranks) {
        return ReputationValidator.audit(companions, groups, ranks, NPC_GROUPS, STATS, ITEMS);
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    @Test
    void aSoundSetupSaysNothing() {
        ReputationAsset jack = ReputationFixtures.companion("Test_Old_Jack", """
                { "Icon": "Test_Icon", "Gear": { "Stat": "Reputation_Test" }, "Cap": 21000,
                  "Ranks": { "Friendly": { "Name": "test.jack.rank.regular" } },
                  "Kills": [ { "NPCGroups": [ "Test_Raiders" ], "Amount": 2 } ] }
                """);
        assertEquals(List.of(), audit(List.of(jack), GROUPS, ReputationFixtures.LADDER));
    }

    @Test
    void fewerThanTwoRanksIsAnError() {
        List<Finding> findings = audit(List.of(), GROUPS, List.of(ReputationFixtures.LADDER.get(0)));
        assertEquals(List.of(ReputationValidator.TOO_FEW_RANKS), codes(findings));
        assertEquals(Severity.ERROR, findings.get(0).severity());
        assertEquals(ReputationValidator.DOMAIN, findings.get(0).domain());
    }

    @Test
    void aNativeGroupWithNoNpcGroupsKeyIsAnErrorAndAnUnknownGroupAWarning() {
        List<Finding> findings = audit(List.of(), List.of(
                new ReputationNative.Group("Test_Bare", 0, null),
                new ReputationNative.Group("Test_Odd", 0, List.of("Test_Missing"))), ReputationFixtures.LADDER);
        assertEquals(List.of(ReputationValidator.GROUP_WITHOUT_NPC_GROUPS, ReputationValidator.UNKNOWN_NPC_GROUP),
                codes(findings));
        assertEquals(Severity.ERROR, findings.get(0).severity());
        assertEquals(Severity.WARNING, findings.get(1).severity());
    }

    @Test
    void aCompanionsUnknownIdsAreWarningsAndAKillRowWithNoGroupsIsAnError() {
        ReputationAsset nobody = ReputationFixtures.companion("Test_Nobody", """
                { "Icon": "Nope", "Gear": { "Stat": "Nope" }, "Cap": -40000,
                  "Ranks": { "Champion": { "Name": "x" } },
                  "Kills": [ { "Amount": 2 }, { "NPCGroups": [ "Test_Missing" ], "Amount": 1 } ] }
                """);
        List<Finding> findings = audit(List.of(nobody), GROUPS, ReputationFixtures.LADDER);
        assertEquals(List.of(ReputationValidator.NO_NATIVE_GROUP, ReputationValidator.UNKNOWN_GEAR_STAT,
                ReputationValidator.UNKNOWN_ICON, ReputationValidator.UNKNOWN_RANK, ReputationValidator.CAP_BELOW_LADDER,
                ReputationValidator.KILL_WITHOUT_GROUPS, ReputationValidator.UNKNOWN_KILL_GROUP), codes(findings));
        for (Finding finding : findings) {
            assertEquals(finding.code().equals(ReputationValidator.KILL_WITHOUT_GROUPS) ? Severity.ERROR : Severity.WARNING,
                    finding.severity(), finding.code());
        }
    }

    @Test
    void aSwitchedOffCompanionIsSkipped() {
        ReputationAsset off = ReputationFixtures.companion("Test_Nobody", "{ \"Enabled\": false, \"Icon\": \"Nope\" }");
        assertEquals(List.of(), audit(List.of(off), GROUPS, ReputationFixtures.LADDER));
    }

    @Test
    void theBootLogNamesOnlyTheErrors() {
        List<Finding> findings = audit(List.of(), List.of(
                new ReputationNative.Group("Test_Bare", 0, null),
                new ReputationNative.Group("Test_Odd", 0, List.of("Test_Missing"))), ReputationFixtures.LADDER);
        assertEquals(1, ReputationValidator.logErrors(findings));
    }

    @Test
    void theEngineWalkNeverThrowsInAUnitJvm() {
        assertTrue(ReputationValidator.audit().isEmpty(), "no engine reputation plugin here, so nothing to audit");
    }
}
