package com.ziggfreed.common.objectives.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The audit over every {@code Indicator} block: a situation pointed at a state no look file
 * describes is named, once per block that names it, as a WARNING in the quest domain; a block
 * naming only states with looks, or naming no state, is silent.
 */
class QuestIndicatorValidatorTest {

    private static final Set<String> LOOKS = Set.of("Quest_Available", "Hand_Me_That");

    private static QuestPool poolOf(String... idAndJson) {
        Map<String, QuestDefinition> definitions = new LinkedHashMap<>();
        for (int i = 0; i < idAndJson.length; i += 2) {
            try {
                AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, idAndJson[i], null);
                QuestAsset asset = QuestAsset.CODEC.decodeAndInheritJsonAsset(
                        RawJsonReader.fromJsonString(idAndJson[i + 1]), null, new AssetExtraInfo<>(data));
                definitions.put(idAndJson[i], asset.toDefinition(null));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return new QuestPool(definitions);
    }

    private static List<Finding> unknownStates(List<Finding> findings) {
        return findings.stream().filter(f -> QuestIndicatorValidator.UNKNOWN_STATE.equals(f.code())).toList();
    }

    @Test
    void aStateNoLookDescribesIsAWarningInTheQuestDomainNamingTheQuest() {
        QuestPool pool = poolOf("q_bad", """
                { "Indicator": { "Available": { "State": "Nobody_Shipped_This" } },
                  "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore" } } }
                """);
        List<Finding> findings = unknownStates(QuestIndicatorValidator.validate(pool, null, LOOKS::contains));
        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals(QuestPoolValidator.DOMAIN, finding.domain());
        assertEquals("q_bad", finding.sourceId());
        assertTrue(finding.message().contains("Nobody_Shipped_This"));
        assertTrue(finding.message().contains("Available"));
    }

    @Test
    void aStepsBlockAndTheGlobalWordAreAuditedToo() {
        QuestPool pool = poolOf("q_step", """
                { "Objectives": { "tell": { "Kind": "TURN_IN", "Target": "", "TurnInNpcId": "guide",
                                            "Indicator": { "TurnIn": { "State": "Step_Nobody_Shipped" } } } } }
                """);
        QuestIndicatorSpec global = QuestIndicatorSpec.of(null,
                QuestIndicatorSpec.Situation.of(null, "Global_Nobody_Shipped", null, null), null, null, null);
        List<Finding> findings = unknownStates(QuestIndicatorValidator.validate(pool, global, LOOKS::contains));
        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("step 'tell'")));
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("Default.json")));
    }

    @Test
    void statesWithLooksAndUnnamedStatesAreSilent() {
        QuestPool pool = poolOf("q_fine", """
                { "Indicator": { "Available": { "State": "Quest_Available", "Map": { "Enabled": false } },
                                 "InProgress": { "Overhead": { "Enabled": false } } },
                  "Objectives": { "tell": { "Kind": "TURN_IN", "Target": "", "TurnInNpcId": "guide",
                                            "Indicator": { "TurnIn": { "State": "Hand_Me_That" } } } } }
                """, "q_plain", """
                { "Objectives": { "mine": { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore" } } }
                """);
        assertTrue(unknownStates(QuestIndicatorValidator.validate(pool, QuestIndicatorSpec.EMPTY, LOOKS::contains))
                .isEmpty());
    }
}
