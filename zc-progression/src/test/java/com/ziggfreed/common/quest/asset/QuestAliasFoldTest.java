package com.ziggfreed.common.quest.asset;

import static com.ziggfreed.common.quest.asset.QuestAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * A kind alias registered in the ONE shared vocabulary reaches every step the quest fold builds:
 * the engine pair is the alias's, the authored pair is the file's.
 */
class QuestAliasFoldTest {

    @BeforeEach
    void freshRuntime() {
        ProgressionRuntime.resetForTests();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Test
    void theQuestFoldAppliesTheSharedVocabularysAlias() throws Exception {
        ProgressionRuntime.objectiveKinds().alias("REACH_RANK", ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod",
                target -> "Rank_" + target.toUpperCase(Locale.ROOT));

        ObjectiveDef step = decodeRoot("""
                { "Objectives": { "climb": { "Kind": "REACH_RANK", "Target": "Mining", "Amount": 30 } } }
                """, "climb_high").toDefinition(null).quest().objective("climb");

        assertEquals(ObjectiveKindRegistry.STAT_THRESHOLD, step.kind(), "the engine runs the alias's kind");
        assertEquals("Rank_MINING", step.target(), "on the rewritten target");
        assertEquals("REACH_RANK", step.authoredKind(), "and the file's own words survive for every surface");
        assertEquals("Mining", step.authoredTarget());
        assertEquals(30L, step.amount());
    }

    @Test
    void withNoAliasTheTwoPairsAreOneAndTheSame() throws Exception {
        ObjectiveDef step = decodeRoot("""
                { "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore" } } }
                """, "plain").toDefinition(null).quest().objective("collect");

        assertEquals(step.kind(), step.authoredKind());
        assertEquals(step.target(), step.authoredTarget());
    }
}
