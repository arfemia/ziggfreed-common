package com.ziggfreed.common.encounter.seam;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * "Which zc run is this fight part of?": a fight inside a dungeon area belongs to the run its members belong to
 * (R105), so its records are filed under that run and a payout can name it. The library's instance module fills
 * it at setup from its dungeon runs; this module never learns what a dungeon is.
 *
 * <p>Asked on the fight's world thread when it is defeated, with the world and the players who took part. Answer
 * null for a fight in no run (the open world, an area with no run), and keep it quick: it is asked inside the
 * defeat.
 */
@FunctionalInterface
public interface EncounterRunTag {

    /** Files every fight under no run: the posture with no fill. */
    EncounterRunTag NONE = (worldUuid, players) -> null;

    /**
     * The run the fight in {@code worldUuid} belongs to, read off {@code players}, or null when it is in none.
     *
     * @param players the fight's participants, the top contributor first
     */
    @Nullable
    RunTag runOf(@Nullable UUID worldUuid, @Nonnull List<UUID> players);
}
