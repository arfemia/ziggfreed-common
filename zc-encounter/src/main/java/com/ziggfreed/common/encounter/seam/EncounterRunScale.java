package com.ziggfreed.common.encounter.seam;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

/**
 * "How much tougher does the run this fight stands in make it?": a fight in a dungeon tier's run takes that tier's
 * step on its boss and its adds. The library's instance module fills it at setup from its dungeon runs; this module
 * never learns what a dungeon or a tier is.
 *
 * <p>Asked on the fight's world thread, once per run, as its boss is first scaled. Answer null for a fight in no run,
 * and keep it quick.
 */
@FunctionalInterface
public interface EncounterRunScale {

    /** Makes no fight tougher: the posture with no fill. */
    EncounterRunScale NONE = (worldUuid, at) -> null;

    /** The step of the run the fight at {@code at} in {@code worldUuid} stands in; null for a fight in no run. */
    @Nullable
    RunScale scaleAt(@Nullable UUID worldUuid, @Nonnull Vector3d at);
}
