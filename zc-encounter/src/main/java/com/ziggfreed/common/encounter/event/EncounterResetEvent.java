package com.ziggfreed.common.encounter.event;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * A run ended and its ledger is gone. ALWAYS the last event of a run: after a defeat or a wipe, or in
 * place of either when the encounter was removed, reloaded, timed out or its world unloaded. When the
 * same encounter entity carries on, {@code nextRunId} names the run that replaces this one.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus. See {@link Encounters}
 * for the fire contract.
 */
public final class EncounterResetEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    @Nullable private final UUID nextRunId;
    private final ResetReason reason;

    public EncounterResetEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID nextRunId,
                               @Nonnull ResetReason reason) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.nextRunId = nextRunId;
        this.reason = reason;
    }

    @Nonnull
    public UUID runId() {
        return runId;
    }

    @Nonnull
    public String encounterId() {
        return encounterId;
    }

    /** The run that replaces this one on the same encounter entity, or null when none does. */
    @Nullable
    public UUID nextRunId() {
        return nextRunId;
    }

    @Nonnull
    public ResetReason reason() {
        return reason;
    }
}
