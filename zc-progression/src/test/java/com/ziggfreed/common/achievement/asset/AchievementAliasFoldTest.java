package com.ziggfreed.common.achievement.asset;

import static com.ziggfreed.common.achievement.asset.AchievementAssetCodecTest.decodeRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * The achievement fold shares the objective-leaf path with the quest fold, so a kind alias
 * registered once reaches a criterion exactly as it reaches a step.
 */
class AchievementAliasFoldTest {

    @BeforeEach
    void freshRuntime() {
        ProgressionRuntime.resetForTests();
    }

    @AfterEach
    void tearDown() {
        ProgressionRuntime.resetForTests();
    }

    @Test
    void theAchievementFoldAppliesTheSharedVocabularysAlias() throws Exception {
        ProgressionRuntime.objectiveKinds().alias("REACH_RANK", ObjectiveKindRegistry.STAT_THRESHOLD, "yourmod",
                target -> "Rank_" + target);

        ObjectiveDef criterion = decodeRoot("""
                { "Criteria": { "climb": { "Kind": "REACH_RANK", "Target": "Mining", "Amount": 30 } } }
                """, "climber").toDefinition().achievement().criteria().get(0);

        assertEquals(ObjectiveKindRegistry.STAT_THRESHOLD, criterion.kind());
        assertEquals("Rank_Mining", criterion.target());
        assertEquals("REACH_RANK", criterion.authoredKind());
        assertEquals("Mining", criterion.authoredTarget());
    }
}
