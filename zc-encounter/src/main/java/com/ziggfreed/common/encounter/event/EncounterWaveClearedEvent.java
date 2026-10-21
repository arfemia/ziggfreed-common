package com.ziggfreed.common.encounter.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.event.IEvent;

/**
 * The script beat a wave ({@code zc:cleared}): the wave standing in the fight has fallen. An endless wave run counts
 * its waves by it, so {@link #wave()} is the run's own count of cleared waves, this one included, and
 * {@link #boss()} says whether the run's schedule made it a boss wave. Fired on the world thread as the beat lands.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus. See {@link Encounters} for the fire
 * contract.
 */
public final class EncounterWaveClearedEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    @Nullable private final UUID worldUuid;
    private final int wave;
    private final boolean boss;
    private final Vector3d position;
    private final List<UUID> members;

    public EncounterWaveClearedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
            int wave, boolean boss, @Nonnull Vector3d position, @Nonnull List<UUID> members) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.worldUuid = worldUuid;
        this.wave = wave;
        this.boss = boss;
        this.position = new Vector3d(position);
        this.members = List.copyOf(members);
    }

    @Nonnull
    public UUID runId() {
        return runId;
    }

    @Nonnull
    public String encounterId() {
        return encounterId;
    }

    @Nullable
    public UUID worldUuid() {
        return worldUuid;
    }

    /** The waves the fight has cleared, this one included: 1 for the first. */
    public int wave() {
        return wave;
    }

    /** Whether the run's endless schedule made this a boss wave; false for a fight in no endless run. */
    public boolean boss() {
        return boss;
    }

    /** Where the fight's entity stands (a copy). */
    @Nonnull
    public Vector3d position() {
        return new Vector3d(position);
    }

    /** The fight's live roster as the wave fell. */
    @Nonnull
    public List<UUID> members() {
        return members;
    }
}
