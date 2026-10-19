package com.ziggfreed.common.track.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * The picks a player made on a track changed through zc: a choice made or a pick bought, either undone, a respec, or
 * a prestige that remembered or cleared them. Fired once per change, on the player's own thread, after the record was
 * kept and the player's grants rebuilt (so a reader sees the new modifiers), and only while something listens. A
 * change that moved nothing fires none.
 */
public final class ZigTrackPicksChangedEvent implements IEvent<Void> {

    /** What changed the picks. */
    public enum Change {
        /** A choice was made at one slot. */
        CHOSEN,
        /** A pick bought outright was taken once more. */
        TAKEN,
        /** The last choice or take was undone. */
        UNDONE,
        /** Every pick on the track was cleared. */
        RESPEC,
        /** A prestige put the picks aside or cleared them. */
        PRESTIGE
    }

    @Nonnull private final UUID playerId;
    @Nonnull private final String trackId;
    @Nonnull private final Change change;
    @Nullable private final String pickId;
    @Nullable private final String slot;
    @Nonnull private final List<String> options;

    public ZigTrackPicksChangedEvent(@Nonnull UUID playerId, @Nonnull String trackId, @Nonnull Change change,
            @Nullable String pickId, @Nullable String slot, @Nonnull List<String> options) {
        this.playerId = playerId;
        this.trackId = trackId;
        this.change = change;
        this.pickId = pickId;
        this.slot = slot;
        this.options = List.copyOf(options);
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The track id, lower-cased. */
    @Nonnull
    public String trackId() {
        return trackId;
    }

    @Nonnull
    public Change change() {
        return change;
    }

    /** The pick it was about, lower-cased, or null for a respec or a prestige. */
    @Nullable
    public String pickId() {
        return pickId;
    }

    /** The slot ({@code <pick>/<group>/<level>}) a choice was made or undone at, or null. */
    @Nullable
    public String slot() {
        return slot;
    }

    /** The options chosen (or undone) at {@link #slot()}, lower-cased; empty otherwise. */
    @Nonnull
    public List<String> options() {
        return options;
    }
}
