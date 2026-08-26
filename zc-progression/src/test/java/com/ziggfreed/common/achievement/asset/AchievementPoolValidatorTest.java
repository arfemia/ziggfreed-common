package com.ziggfreed.common.achievement.asset;

import static com.ziggfreed.common.achievement.asset.AchievementAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.achievement.InMemoryAchievementProgressStore;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The load-time audit: what it reports, and above all at which SEVERITY. The severity split is the
 * load-bearing part - an unknown id is a warning because its owner may register later or may be a
 * mod this server does not install, while something that can NEVER work is an error.
 */
class AchievementPoolValidatorTest {

    private static final ObjectiveKindRegistry KINDS = new ObjectiveKindRegistry();
    private static final RewardKindRegistry REWARDS = new RewardKindRegistry();
    private static final AchievementProgressStore STORE = new InMemoryAchievementProgressStore();

    static {
        KINDS.register(null, ObjectiveKind.of("BREAK_BLOCK"));
        KINDS.register(null, new ObjectiveKind("NEVER_FIRED", false, false));
        REWARDS.register("test:pay", (spec, subject) -> {
        });
    }

    private static AchievementPool pool(Map<String, String> filesById) throws Exception {
        Map<String, AchievementDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : filesById.entrySet()) {
            definitions.put(file.getKey(), decodeRoot(file.getValue(), file.getKey()).toDefinition());
        }
        return new AchievementPool(definitions);
    }

    private static List<Finding> validate(AchievementPool pool) {
        return AchievementPoolValidator.validate(pool, KINDS, REWARDS, STORE, new GateKindRegistry());
    }

    private static Finding only(List<Finding> findings, String code) {
        List<Finding> matches = findings.stream().filter(f -> f.code().equals(code)).toList();
        assertEquals(1, matches.size(), () -> "expected exactly one " + code + " in " + findings);
        return matches.get(0);
    }

    private static boolean hasCode(List<Finding> findings, String code) {
        return findings.stream().anyMatch(f -> f.code().equals(code));
    }

    @Test
    void wellFormedContentReportsNothing() throws Exception {
        List<Finding> findings = validate(pool(Map.of("prospector", """
                { "Criteria": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 } },
                  "Rewards": { "Auto": [ { "Kind": "test:pay" } ] } }
                """)));
        assertTrue(findings.isEmpty(), () -> "unexpected findings: " + findings);
    }

    @Test
    void anUnknownKindWarnsWhileOneNothingEverFiresIsAnError() throws Exception {
        List<Finding> findings = validate(pool(Map.of(
                "unknown_kind", """
                        { "Criteria": { "step": { "Kind": "yourmod:not_registered", "Amount": 1 } } }
                        """,
                "dead_kind", """
                        { "Criteria": { "step": { "Kind": "NEVER_FIRED", "Amount": 1 } } }
                        """)));

        assertEquals(Severity.WARNING, only(findings, "UNKNOWN_KIND").severity(),
                "the mod that owns a kind may register later, or may not be installed here");
        assertEquals(Severity.ERROR, only(findings, "UNPRODUCIBLE_KIND").severity(),
                "a registered kind nothing ever fires can never progress, whoever is installed");
        assertEquals("achievement", only(findings, "UNKNOWN_KIND").domain());
    }

    @Test
    void aCriterionWithNoKindOrNoPositiveAmountIsReported() throws Exception {
        List<Finding> findings = validate(pool(Map.of(
                "no_kind", """
                        { "Criteria": { "step": { "Target": "Copper_Ore", "Amount": 1 } } }
                        """,
                "free_lunch", """
                        { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 0 } } }
                        """)));

        assertEquals(Severity.ERROR, only(findings, "MISSING_KIND").severity());
        assertEquals(Severity.WARNING, only(findings, "NON_POSITIVE_AMOUNT").severity());
    }

    @Test
    void somethingNothingCanEverEarnIsReported() throws Exception {
        List<Finding> findings = validate(pool(Map.of("empty", "{ }")));
        assertEquals(Severity.WARNING, only(findings, "NO_CRITERIA").severity());
    }

    @Test
    void aCapstoneIsCheckedAgainstThePoolAndAgainstItself() throws Exception {
        List<Finding> findings = validate(pool(Map.of(
                "capstone", """
                        { "MetaChildren": [ "missing_one" ] }
                        """,
                "ouroboros", """
                        { "MetaChildren": [ "ouroboros" ] }
                        """)));

        assertEquals(Severity.WARNING, only(findings, "UNKNOWN_META_CHILD").severity());
        assertEquals(Severity.ERROR, only(findings, "META_SELF_REFERENCE").severity(),
                "a capstone waiting on itself can never be satisfied, so it is not a maybe");
    }

    @Test
    void authoringBothCriteriaAndChildrenSaysTheCriteriaAreDeadWeight() throws Exception {
        List<Finding> findings = validate(pool(Map.of("both", """
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "MetaChildren": [ "both_child" ] }
                """)));
        assertTrue(hasCode(findings, "CRITERIA_AND_META"));
    }

    @Test
    void aRewardKindNothingRegisteredWarnsForEitherRewardList() throws Exception {
        List<Finding> findings = validate(pool(Map.of("prospector", """
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "Rewards": { "Auto":  [ { "Kind": "yourmod:absent" } ],
                               "Claim": [ { "Kind": "yourmod:also_absent" } ] } }
                """)));

        List<Finding> unknown = findings.stream()
                .filter(f -> f.code().equals("UNKNOWN_REWARD_KIND")).toList();
        assertEquals(2, unknown.size(), "both reward lists are audited");
        assertTrue(unknown.stream().allMatch(f -> f.severity() == Severity.WARNING));
    }

    @Test
    void anIdTheProgressFormatCannotStoreIsAnError() throws Exception {
        List<Finding> findings = validate(pool(Map.of("bad#id", """
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """)));
        assertEquals(Severity.ERROR, only(findings, "RESERVED_ID").severity());
        assertFalse(findings.isEmpty());
    }

    @Test
    void aBlankFactorEntryGatesNothingAndSaysSo() throws Exception {
        List<Finding> findings = validate(pool(Map.of("gated", """
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "Requires": { "Factors": [ { } ] } }
                """)));
        assertEquals(Severity.WARNING, only(findings, "BLANK_REQUIREMENT").severity());
    }

    @Test
    void aThresholdCriterionWithNoChannelHasNothingToMeasure() throws Exception {
        List<Finding> findings = validate(pool(Map.of(
                "no_channel", """
                        { "Criteria": { "step": { "Kind": "STAT_THRESHOLD", "Amount": 10 } } }
                        """,
                "with_channel", """
                        { "Criteria": { "step": { "Kind": "STAT_THRESHOLD", "Target": "Deep_Delving",
                                                  "Amount": 10 } } }
                        """)));

        Finding finding = only(findings, "STAT_THRESHOLD_WITHOUT_TARGET");
        assertEquals(Severity.WARNING, finding.severity(),
                "the criterion is meaningless without a channel, but a consumer may still fire it");
        assertEquals("no_channel", finding.sourceId(),
                "the one naming its channel is well formed and must not be reported");
    }

    @Test
    void anUnregisteredCustomGateKindWarnsRatherThanFailing() throws Exception {
        List<Finding> findings = validate(pool(Map.of("gated", """
                { "Criteria": { "step": { "Kind": "BREAK_BLOCK", "Amount": 1 } },
                  "Requires": { "Custom": { "yourmod:reputation": { "Min": "500" } } } }
                """)));
        assertEquals(Severity.WARNING, only(findings, "UNKNOWN_GATE_KIND").severity());
    }
}
