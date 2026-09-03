package com.ziggfreed.common.encounter.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * The fight is on: the script signalled {@code zc:engaged}, or its subject bound and the engage grace
 * ran out with no beat authored. Fired once per run, on the world thread, before any phase.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; every collection is a
 * copy and unmodifiable. See {@link Encounters} for the fire contract.
 */
public final class EncounterEngagedEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    @Nullable private final String nameKey;
    @Nullable private final UUID worldUuid;
    @Nullable private final UUID subjectUuid;
    @Nullable private final String subjectMobId;
    private final List<UUID> members;
    private final long engagedAtMs;
    @Nullable private final String difficulty;

    public EncounterEngagedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable String nameKey,
                                 @Nullable UUID worldUuid, @Nullable UUID subjectUuid, @Nullable String subjectMobId,
                                 @Nonnull List<UUID> members, long engagedAtMs, @Nullable String difficulty) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.nameKey = nameKey;
        this.worldUuid = worldUuid;
        this.subjectUuid = subjectUuid;
        this.subjectMobId = subjectMobId;
        this.members = List.copyOf(members);
        this.engagedAtMs = engagedAtMs;
        this.difficulty = difficulty;
    }

    @Nonnull
    public UUID runId() {
        return runId;
    }

    /** The native encounter script id. */
    @Nonnull
    public String encounterId() {
        return encounterId;
    }

    /** The binding row's name key, or null when the row authored none. */
    @Nullable
    public String nameKey() {
        return nameKey;
    }

    @Nullable
    public UUID worldUuid() {
        return worldUuid;
    }

    /** The bound subject's entity uuid at the bind, or null when no subject had bound yet. */
    @Nullable
    public UUID subjectUuid() {
        return subjectUuid;
    }

    /** The subject's mob id captured at the bind, before any in-place role change. */
    @Nullable
    public String subjectMobId() {
        return subjectMobId;
    }

    /** The members at the engage, by player uuid. */
    @Nonnull
    public List<UUID> members() {
        return members;
    }

    public int memberCount() {
        return members.size();
    }

    public long engagedAtMs() {
        return engagedAtMs;
    }

    /** The run's difficulty label, from the spawn call or the binding row, or null. */
    @Nullable
    public String difficulty() {
        return difficulty;
    }
}
