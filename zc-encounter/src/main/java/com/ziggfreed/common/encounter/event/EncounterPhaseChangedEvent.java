package com.ziggfreed.common.encounter.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * The script signalled {@code zc:phase:<State>}. Fired on the world thread once per phase beat; the
 * encounter tick reconciles the subject's health scale right after it.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; every collection is a
 * copy and unmodifiable. See {@link Encounters} for the fire contract.
 */
public final class EncounterPhaseChangedEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    @Nullable private final String fromPhase;
    private final String toPhase;
    private final int phaseIndex;
    private final List<UUID> members;
    private final long elapsedMs;

    public EncounterPhaseChangedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable String fromPhase,
                                      @Nonnull String toPhase, int phaseIndex, @Nonnull List<UUID> members,
                                      long elapsedMs) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.fromPhase = fromPhase;
        this.toPhase = toPhase;
        this.phaseIndex = phaseIndex;
        this.members = List.copyOf(members);
        this.elapsedMs = elapsedMs;
    }

    @Nonnull
    public UUID runId() {
        return runId;
    }

    @Nonnull
    public String encounterId() {
        return encounterId;
    }

    /** The phase the run was in, or null for the first phase beat of the run. */
    @Nullable
    public String fromPhase() {
        return fromPhase;
    }

    /** The script's own state name, exactly as signalled. */
    @Nonnull
    public String toPhase() {
        return toPhase;
    }

    /** How many phase beats this run has seen, this one included. */
    public int phaseIndex() {
        return phaseIndex;
    }

    @Nonnull
    public List<UUID> members() {
        return members;
    }

    /** Milliseconds since the run engaged, or since the run started when it never engaged. */
    public long elapsedMs() {
        return elapsedMs;
    }
}
