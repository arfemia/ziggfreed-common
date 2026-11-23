package com.ziggfreed.common.track.event;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A face a player holds in a stance changed through zc: held in a slot, released by the player (at the stance's
 * {@code Break} price), or freed (for nothing: an admin's release, or at a settle a face no longer owned, a slot
 * closed, a kind past its cap). Fired once per face, on the player's own thread, after the record was kept and the
 * player's grants rebuilt (so a reader sees the new modifiers), and only while something listens.
 */
public final class ZigStanceChangedEvent implements IEvent<Void> {

    /** What changed. */
    public enum Change {
        /** The face was put in a slot. */
        HELD,
        /** The player released the face, paying the stance's Break price. */
        RELEASED,
        /** The face left its slot for nothing: an admin's release, or lost (unowned, its slot closed, its kind full). */
        FREED
    }

    @Nonnull private final UUID playerId;
    @Nonnull private final String stanceId;
    @Nonnull private final String slotId;
    @Nonnull private final String faceId;
    @Nonnull private final Change change;

    public ZigStanceChangedEvent(@Nonnull UUID playerId, @Nonnull String stanceId, @Nonnull String slotId,
            @Nonnull String faceId, @Nonnull Change change) {
        this.playerId = playerId;
        this.stanceId = stanceId;
        this.slotId = slotId;
        this.faceId = faceId;
        this.change = change;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The stance id, lower-cased. */
    @Nonnull
    public String stanceId() {
        return stanceId;
    }

    /** The slot the face was held in or left, lower-cased. */
    @Nonnull
    public String slotId() {
        return slotId;
    }

    /** The face's pick id, lower-cased. */
    @Nonnull
    public String faceId() {
        return faceId;
    }

    @Nonnull
    public Change change() {
        return change;
    }
}
