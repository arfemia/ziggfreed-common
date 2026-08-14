package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.quest.InMemoryQuestProgressStore;
import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The audit that catches the quests which load perfectly and can never be finished: a step nothing
 * fires, a reward nothing pays, a hand-in with nothing to hand in, an id the progress format cannot
 * store.
 *
 * <p>Severity carries the argument. A kind NOBODY registered may simply belong to a mod that is not
 * installed, which is a warning; a kind that IS registered as unproducible is an error, because then
 * nothing is ever going to fire it. Getting that backwards would either cry wolf on every optional
 * dependency or stay silent on genuinely dead content.
 */
class QuestPoolValidatorTest {

    private static final ObjectiveKindRegistry KINDS = new ObjectiveKindRegistry();
    private static final RewardKindRegistry REWARDS = new RewardKindRegistry();
    private static final QuestProgressStore STORE = new InMemoryQuestProgressStore();

    static {
        KINDS.register("yourmod:unfired", "yourmod", false, false);
        REWARDS.register("yourmod:currency", (spec, subject) -> { });
    }

    private static QuestPool poolOf(String... idAndJson) {
        Map<String, QuestDefinition> definitions = new LinkedHashMap<>();
        for (int i = 0; i < idAndJson.length; i += 2) {
            try {
                definitions.put(idAndJson[i],
                        decodeRoot(idAndJson[i + 1], idAndJson[i]).toDefinition(null));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return new QuestPool(definitions);
    }

    private static List<Finding> validate(QuestPool pool) {
        return QuestPoolValidator.validate(pool, KINDS, REWARDS, STORE, new GateKindRegistry());
    }

    private static List<String> codes(List<Finding> issues) {
        return issues.stream().map(Finding::code).toList();
    }

    @Test
    void aWellFormedQuestReportsNothing() {
        assertTrue(validate(poolOf("gather_copper", """
                { "Text": { "TitleKey": "quest.gather.title" },
                  "Npc": { "ViewId": "guide", "TurnInId": "giver" },
                  "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10 },
                                  "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10 } },
                  "Rewards": [ { "Kind": "yourmod:currency", "Params": { "Amount": "50" } } ] }
                """)).isEmpty());
    }

    @Test
    void anUnknownKindWarnsWhileAnUnproducibleOneIsAnError() {
        List<Finding> issues = validate(poolOf("q", """
                { "Objectives": { "a": { "Kind": "yourmod:never_registered", "Target": "x" },
                                  "b": { "Kind": "yourmod:unfired", "Target": "x" } } }
                """));

        assertEquals(List.of("UNKNOWN_KIND", "UNPRODUCIBLE_KIND"), codes(issues));
        assertEquals(Severity.WARNING, issues.get(0).severity(),
                "the mod that fires it may simply not be installed yet");
        assertEquals(Severity.ERROR, issues.get(1).severity(),
                "a kind whose own owner says nothing fires it is dead content");
    }

    @Test
    void aRewardNothingPaysOutIsWorthSaying() {
        assertEquals(List.of("UNKNOWN_REWARD_KIND"), codes(validate(poolOf("q", """
                { "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } },
                  "Rewards": [ { "Kind": "yourmod:unhandled" } ] }
                """))));
    }

    @Test
    void anIdTheProgressFormatCannotStoreIsAnError() {
        List<Finding> issues = validate(poolOf("bad:id", """
                { "Objectives": { "a|b": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """));

        assertEquals(List.of("RESERVED_ID", "RESERVED_ID"), codes(issues));
        assertTrue(issues.stream().allMatch(i -> i.severity() == Severity.ERROR));
    }

    /**
     * A hand-in with a blank Target is the REPORT-BACK shape the engine documents and supports (go
     * and tell them you are done, nothing to hand over), so it must not be reported: an author
     * following the engine's own offer would be told to break it. Only a hand-in place on a step
     * that can never consult one is worth saying.
     */
    @Test
    void theHandInChecksAreAboutWhetherAHandInCanHappenAtAll() {
        assertEquals(List.of(), codes(validate(poolOf("q", """
                { "Objectives": { "hand_in": { "Kind": "TURN_IN" } } }
                """))));

        assertEquals(List.of("TURN_IN_ON_OTHER_KIND"), codes(validate(poolOf("q", """
                { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Ore",
                                               "TurnInNpcId": "guide" } } }
                """))));
    }

    /** The ladder checks: an id named twice, and a rung that cannot be placed on a climb. */
    @Test
    void theLadderChecksCatchAMembershipThatSaysNothing() {
        assertEquals(List.of("DUPLICATE_CHAIN"), codes(validate(poolOf("q", """
                { "Listing": { "Chains": [ { "Id": "mining", "Tier": 1 },
                                           { "Id": "mining", "Tier": 2 } ] },
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """))));

        assertEquals(List.of("NON_POSITIVE_CHAIN_TIER"), codes(validate(poolOf("q", """
                { "Listing": { "Chains": [ { "Id": "mining" } ] },
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """))));

        assertEquals(List.of(), codes(validate(poolOf("q", """
                { "Listing": { "Chains": [ { "Id": "mining", "Tier": 1 },
                                           { "Id": "gathering", "Tier": 3 } ] },
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """))), "belonging to two ladders at once is the point, not a mistake");
    }

    @Test
    void aQuestWithNoStepsIsFinishedTheInstantItIsTaken() {
        assertEquals(List.of("NO_OBJECTIVES"), codes(validate(poolOf("q", "{ }"))));
    }

    @Test
    void aStepAskingForNothingIsAlreadyDone() {
        assertEquals(List.of("NON_POSITIVE_AMOUNT"), codes(validate(poolOf("q", """
                { "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x", "Amount": 0 } } }
                """))));
    }

    @Test
    void aStepWithNoKindCanNeverProgress() {
        List<Finding> issues = validate(poolOf("q", """
                { "Objectives": { "a": { "Target": "x" } } }
                """));
        assertEquals(List.of("MISSING_KIND"), codes(issues));
        assertEquals(Severity.ERROR, issues.get(0).severity());
    }

    @Test
    void aRequirementNamingSomethingThisPoolDoesNotHaveIsReported() {
        List<Finding> issues = validate(poolOf("q", """
                { "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } },
                  "Requires": { "Quests": ["intro_1"],
                                "Factors": [ { "Min": 5 } ],
                                "Custom": { "yourmod:absent": { } } } }
                """));

        assertTrue(codes(issues).contains("UNKNOWN_PREREQUISITE"));
        assertTrue(codes(issues).contains("BLANK_REQUIREMENT"));
        assertTrue(codes(issues).contains("UNKNOWN_GATE_KIND"));
        assertTrue(issues.stream().allMatch(i -> i.severity() == Severity.WARNING),
                "every one of these may be another mod's business, so none of them stops a load");
    }

    @Test
    void aPrerequisiteThisPoolDoesHaveIsSilent() {
        assertTrue(codes(validate(poolOf(
                "intro_1", "{ \"Objectives\": { \"a\": { \"Kind\": \"BREAK_BLOCK\", \"Target\": \"x\" } } }",
                "q", """
                        { "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } },
                          "Requires": { "Quests": ["intro_1"] } }
                        """))).isEmpty());
    }

    @Test
    void aResetTargetNobodyAuthoredIsReported() {
        assertEquals(List.of("UNKNOWN_RESET_TARGET"), codes(validate(poolOf("q", """
                { "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } },
                  "Repeat": { "ResetsOnComplete": ["daily_1"] } }
                """))));
    }

    @Test
    void aThresholdStepWithNoChannelHasNothingToMeasure() {
        List<Finding> issues = validate(poolOf("q", """
                { "Objectives": { "reach": { "Kind": "STAT_THRESHOLD", "Amount": 10 } } }
                """));

        assertEquals(List.of("STAT_THRESHOLD_WITHOUT_TARGET"), codes(issues));
        assertEquals(Severity.WARNING, issues.get(0).severity(),
                "the step is meaningless without a channel, but a consumer may still fire it by hand");
    }

    @Test
    void aThresholdStepNamingItsChannelIsFine() {
        assertTrue(validate(poolOf("q", """
                { "Objectives": { "reach": { "Kind": "STAT_THRESHOLD", "Target": "Deep_Delving",
                                             "Amount": 10 } } }
                """)).isEmpty());
    }

    /**
     * The collection site, both ways of naming one nobody can be. A giver-bound quest with no giver
     * is provable from the file alone; an id nothing declares needs somebody who knows which
     * characters exist, and with nobody to ask the check stays quiet rather than guessing.
     */
    @Test
    void aCollectionSiteNobodyCanBeIsReported() {
        List<Finding> issues = validate(poolOf("q", """
                { "TurnInAt": true,
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """));

        assertEquals(List.of("TURN_IN_AT_NO_GIVER"), codes(issues));
        assertEquals(Severity.WARNING, issues.get(0).severity());

        assertTrue(validate(poolOf("q", """
                { "TurnInAt": true, "Npc": { "ViewId": "guide" },
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """)).isEmpty(), "a giver-bound quest that names its giver is fine");
    }

    @Test
    void aCollectionSiteNothingAnswersToIsAWarningOnlyWhenSomebodyCanSaySo() {
        QuestPool pool = poolOf("q", """
                { "TurnInAt": "quartermaster",
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """);

        assertTrue(validate(pool).isEmpty(),
                "with no probe the audit cannot know who exists, so it claims nothing");

        List<Finding> issues = QuestPoolValidator.validate(pool, KINDS, REWARDS, STORE,
                new GateKindRegistry(), npcId -> false);
        assertEquals(List.of("UNKNOWN_TURN_IN_AT"), codes(issues));
        assertEquals(Severity.WARNING, issues.get(0).severity(),
                "the character may belong to a mod this server has not installed");

        assertTrue(QuestPoolValidator.validate(pool, KINDS, REWARDS, STORE, new GateKindRegistry(),
                npcId -> npcId.equals("quartermaster")).isEmpty());
    }

    @Test
    void theAcceptedAtSiteNamesNobodySoThereIsNothingToLookUp() {
        assertTrue(QuestPoolValidator.validate(poolOf("q", """
                { "TurnInAt": "@accept",
                  "Objectives": { "a": { "Kind": "BREAK_BLOCK", "Target": "x" } } }
                """), KINDS, REWARDS, STORE, new GateKindRegistry(), npcId -> false).isEmpty());
    }

    @Test
    void anUnknownVocabularyIsSkippedRatherThanReportedAsAllUnknown() {
        List<Finding> issues = QuestPoolValidator.validate(poolOf("q", """
                { "Objectives": { "a": { "Kind": "yourmod:whatever", "Target": "x" } },
                  "Rewards": [ { "Kind": "yourmod:whatever" } ] }
                """), null, null, null, null);

        assertFalse(codes(issues).contains("UNKNOWN_KIND"),
                "a caller with no vocabularies yet knows nothing, so it must claim nothing");
        assertTrue(issues.isEmpty());
    }
}
