package com.ziggfreed.common.encounter.seam;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.ziggfreed.common.encounter.wave.WaveRules;

/**
 * "Is this fight an endless wave run, and does its run still take waves?": a fight standing in a dungeon area whose
 * dungeon is endless runs that dungeon's schedule and ramp while the area's run lasts. The library's instance module
 * fills it at setup from its dungeon runs; this module never learns what a dungeon is.
 *
 * <p>Asked on the fight's world thread, about once a second, only for a fight whose script reads an endless reading
 * or beats a wave clear ({@code zc:cleared}). Answer null for a fight in no endless run, and keep it quick.
 */
@FunctionalInterface
public interface EncounterWaveSource {

    /** Runs no fight as an endless one: the posture with no fill. */
    EncounterWaveSource NONE = (worldUuid, at) -> null;

    /**
     * The endless rules of the run the fight at {@code at} in {@code worldUuid} belongs to, while that run still
     * takes waves; null for a fight in no endless run, or one whose run has ended.
     */
    @Nullable
    WaveRules rulesAt(@Nullable UUID worldUuid, @Nonnull Vector3d at);
}
