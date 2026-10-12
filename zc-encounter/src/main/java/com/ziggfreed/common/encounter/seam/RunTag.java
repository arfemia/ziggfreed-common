package com.ziggfreed.common.encounter.seam;

import javax.annotation.Nonnull;

/**
 * The zc run a fight was fought in, as {@link EncounterRunTag} answers it: what the run is OF and which one it
 * is. A fight inside a dungeon area belongs to the run its members belong to (R105), so its records and its
 * Claims can name that run without this module knowing what a dungeon is.
 *
 * @param source what the run is of, {@code "<site>:<id>"} the way a Claim's source reads ({@code dungeon:Crypt});
 *               never blank, and carrying no {@code @} or {@code |}, since a fight's record key carries it
 * @param run    which run of it, unique across restarts (a dungeon run's id)
 */
public record RunTag(@Nonnull String source, @Nonnull String run) {

    public RunTag {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a run tag needs a source");
        }
        if (run == null || run.isBlank()) {
            throw new IllegalArgumentException("a run tag needs a run");
        }
        source = source.trim();
        run = run.trim();
    }
}
