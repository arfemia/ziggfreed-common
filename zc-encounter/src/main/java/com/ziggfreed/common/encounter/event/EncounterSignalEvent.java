package com.ziggfreed.common.encounter.event;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The script sent a {@code zc:} signal the framework does not reserve for itself: a wave beat
 * ({@code zc:wave[:<label>]}) or the author's own ({@code zc:<anything else>}). Fired on the world
 * thread as the signal lands; a listener that needs the encounter entity gets its live reference and
 * is already on its thread.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus. See {@link Encounters}
 * for the fire contract.
 */
public final class EncounterSignalEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    private final String signalId;
    @Nullable private final String suffix;
    @Nullable private final UUID worldUuid;
    private final int memberCount;
    private final Ref<EntityStore> encounterRef;

    public EncounterSignalEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nonnull String signalId,
                                @Nullable String suffix, @Nullable UUID worldUuid, int memberCount,
                                @Nonnull Ref<EntityStore> encounterRef) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.signalId = signalId;
        this.suffix = suffix;
        this.worldUuid = worldUuid;
        this.memberCount = memberCount;
        this.encounterRef = encounterRef;
    }

    @Nonnull
    public UUID runId() {
        return runId;
    }

    @Nonnull
    public String encounterId() {
        return encounterId;
    }

    /** The signal id exactly as authored, prefix included. */
    @Nonnull
    public String signalId() {
        return signalId;
    }

    /** Everything after {@code zc:} (a wave label after {@code wave:}, or the author's own beat), or null. */
    @Nullable
    public String suffix() {
        return suffix;
    }

    @Nullable
    public UUID worldUuid() {
        return worldUuid;
    }

    public int memberCount() {
        return memberCount;
    }

    /** The encounter entity, live on the firing thread. */
    @Nonnull
    public Ref<EntityStore> encounterRef() {
        return encounterRef;
    }
}
