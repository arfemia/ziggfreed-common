package com.ziggfreed.common.encounter.seam;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

/**
 * "Which difficulty is the run this fight stands in?": a fight in a dungeon tier's run carries that tier's
 * {@code Difficulty} label when its spawn call named none, so its Claims count each tier apart
 * ({@code boss:<script>:<reward>:<difficulty>}) and its defeat is qualified by it. The library's instance module
 * fills it at setup from its dungeon runs; this module never learns what a dungeon or a tier is.
 *
 * <p>Asked on the fight's world thread, once per run, with the run's step ({@link EncounterRunScale}). Answer null
 * for a fight in no run or in a run of normal difficulty, and keep it quick.
 */
@FunctionalInterface
public interface EncounterRunDifficulty {

    /** Names no difficulty: the posture with no fill. */
    EncounterRunDifficulty NONE = (worldUuid, at) -> null;

    /**
     * The difficulty label of the run the fight at {@code at} in {@code worldUuid} stands in; null for a fight in no
     * run, or one of normal difficulty.
     */
    @Nullable
    String difficultyAt(@Nullable UUID worldUuid, @Nonnull Vector3d at);
}
