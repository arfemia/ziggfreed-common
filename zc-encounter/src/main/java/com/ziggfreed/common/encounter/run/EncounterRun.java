package com.ziggfreed.common.encounter.run;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A read-only snapshot of one run, for a listener or an admin listing that must not hold the live
 * component.
 *
 * @param runId         the run
 * @param encounterId   the native script id
 * @param worldUuid     the world the encounter entity is in, or null before the first tick
 * @param subjectUuid   the bound subject's uuid, or null when none has bound
 * @param subjectMobId  the subject's mob id at the bind, or null
 * @param engagedAtMs   when the fight engaged, or 0 when it has not
 * @param phase         the last signalled phase, or null
 * @param phaseIndex    how many phase beats the run has seen
 * @param waves         how many wave beats the run has seen
 * @param memberDeaths  how many members died
 * @param memberCount   how many players the run has seen as members
 * @param concluded     whether the run has been settled (defeated or wiped)
 * @param defeated      whether the settlement was a defeat
 * @param ownerKey      who owns the run, or null
 * @param difficulty    the run's difficulty label, or null
 */
public record EncounterRun(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                           @Nullable UUID subjectUuid, @Nullable String subjectMobId, long engagedAtMs,
                           @Nullable String phase, int phaseIndex, int waves, int memberDeaths, int memberCount,
                           boolean concluded, boolean defeated, @Nullable String ownerKey,
                           @Nullable String difficulty) {

    /** The snapshot of {@code run}, under the script id {@code encounterId}. */
    @Nonnull
    public static EncounterRun of(@Nonnull ZigEncounterRun run, @Nonnull String encounterId) {
        return new EncounterRun(run.runId(), encounterId, run.worldUuid(), run.subjectUuid(), run.subjectMobId(),
                run.engagedAtMs(), run.phase(), run.phaseIndex(), run.waves(), run.memberDeaths(),
                run.knownMembers().size(), run.isConcluded(), run.isDefeated(), run.ownerKey(), run.difficulty());
    }

    public boolean isEngaged() {
        return engagedAtMs > 0L;
    }

    /** The first eight characters of the run id, for a log line or a listing. */
    @Nonnull
    public String shortId() {
        return shortId(runId);
    }

    /** The first eight characters of {@code id}. */
    @Nonnull
    public static String shortId(@Nonnull UUID id) {
        return id.toString().substring(0, 8);
    }
}
