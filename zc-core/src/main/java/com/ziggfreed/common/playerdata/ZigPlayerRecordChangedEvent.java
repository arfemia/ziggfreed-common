package com.ziggfreed.common.playerdata;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * One of a player's saved records changed: a write the library made onto a per-player component the engine saves
 * with the player. It names the player and the record by its save key (the component's registry id, such as
 * {@code ZiggfreedCommon:Track}), never what changed inside it, so a listener that keeps its own copy of player data
 * (a consumer's database) marks that record for its next save off this one event and needs no word from each module.
 *
 * <p>Fired from each record's one write path, after the write landed, on the player's own world thread, and only while
 * something listens ({@link PlayerRecords#changed}). The records that fire it: the climbs and boosts
 * ({@code ZiggfreedCommon:Track}), the wallets and purchase counts ({@code ZiggfreedCommon:Commerce}), the stat tallies
 * ({@code ZiggfreedCommon:Stats}), the reputation record ({@code ZiggfreedCommon:Reputation}), the Almanac record
 * ({@code ZiggfreedCommon:Almanac}) and the calendar attendance ({@code ZiggfreedCommon:CalendarAttendance}). The
 * records that already announce each change in their own words keep those: the progress record through the progression
 * store's dirty and flush fan-out, the flair set through {@code ZigFlairChangedEvent}, the titles through
 * {@code ZigTitleChangedEvent} and the player's own settings through {@code ZigPlayerSettingChangedEvent}. The one
 * exception: a title write that changed only the saved Show none choice (nothing shown changed, so no title event)
 * fires this event for {@code ZiggfreedCommon:Titles}.
 *
 * <p>A hot record (the climbs on every gain, the stat tallies on every counted moment) fires on every write, so a
 * listener does no more than mark the record and leaves the write to its own schedule.
 */
public final class ZigPlayerRecordChangedEvent implements IEvent<Void> {

    @Nonnull private final UUID playerId;
    @Nonnull private final String recordId;

    public ZigPlayerRecordChangedEvent(@Nonnull UUID playerId, @Nonnull String recordId) {
        this.playerId = playerId;
        this.recordId = recordId;
    }

    /** The player whose record changed. */
    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The record's save key: its component's registry id, exactly as the engine saves it. */
    @Nonnull
    public String recordId() {
        return recordId;
    }
}
