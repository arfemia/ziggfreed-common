package com.ziggfreed.common.encounter.seam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.SpawnOptions;
import com.ziggfreed.common.encounter.run.ZigEncounterRun;

/**
 * A run's difficulty on a fight: a fight in no run, a run of normal difficulty, an unfilled seam and a failing fill all
 * name none and report the unfilled seam once; a filled one names the run's label, which the fight carries after the
 * spawn call's own and before its binding row's, and which a re-arm forgets. Untagged: nothing here builds an engine
 * item.
 */
class RunDifficultyTest {

    @AfterEach
    void reset() {
        EncounterSeams.resetForTests();
    }

    @Test
    void aFightInNoRunOrWithNoFillNamesNoDifficulty() {
        Vector3d at = new Vector3d(1, 2, 3);
        assertNull(EncounterSeams.runDifficultyAt(null, at), "the unfilled seam names none");
        assertFalse(EncounterSeams.warnOnce(EncounterSeams.latchForTests("rundifficulty"), "again"),
                "and says so once");
        EncounterSeams.fillRunDifficulty((world, where) -> null);
        assertNull(EncounterSeams.runDifficultyAt(null, at), "a fight in no run names none");
        EncounterSeams.fillRunDifficulty((world, where) -> "  ");
        assertNull(EncounterSeams.runDifficultyAt(null, at), "a run of normal difficulty names none");
        EncounterSeams.fillRunDifficulty((world, where) -> " Tier_2 ");
        assertEquals("Tier_2", EncounterSeams.runDifficultyAt(null, at), "the run's label, trimmed");
        EncounterSeams.fillRunDifficulty((world, where) -> {
            throw new IllegalStateException("broken fill");
        });
        assertNull(EncounterSeams.runDifficultyAt(null, at), "a failing fill costs only the label");
    }

    @Test
    void theSpawnCallsLabelComesFirstThenTheRunsAndARearmForgetsTheRuns() {
        ZigEncounterRun run = new ZigEncounterRun();
        assertNull(EncounterLifecycle.difficultyOf(run, null), "nothing asked, nothing named");
        run.noteRunDifficulty("Tier_3");
        assertEquals("Tier_3", EncounterLifecycle.difficultyOf(run, null), "the dungeon run's tier counts apart");
        run.rollOver();
        assertNull(EncounterLifecycle.difficultyOf(run, null), "the next run asks again");

        ZigEncounterRun spawned = ZigEncounterRun.forSpawn(SpawnOptions.defaults().withDifficulty("Hard"));
        spawned.noteRunDifficulty("Tier_3");
        assertEquals("Hard", EncounterLifecycle.difficultyOf(spawned, null), "a label the spawn call named wins");
    }
}
