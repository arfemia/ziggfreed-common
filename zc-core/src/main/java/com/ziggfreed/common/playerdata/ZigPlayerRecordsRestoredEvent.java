package com.ziggfreed.common.playerdata;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * A store outside the engine's own save just put some of a player's saved records in place: a consumer's database
 * loading the player as they enter a world, over whatever the engine read from this server's own player file. It names
 * the records it replaced by their save keys ({@link ZigPlayerRecordChangedEvent#recordId}).
 *
 * <p>The engine's own save still holds this server's copy of everything else, so a module that keeps a second copy of
 * a restored record beside it in the engine's save (the reputation module's copy of each standing in the engine's own
 * reputation value) or in memory since the player connected (the shown titles) treats that copy as behind and takes
 * the restored record's word. Fired by the consumer that restored the records, on the player's own world thread,
 * before anything else reads them that entry; the library itself never fires it, so a server whose players live in
 * the engine's save alone never sees one.
 */
public final class ZigPlayerRecordsRestoredEvent implements IEvent<Void> {

    @Nonnull private final UUID playerId;
    @Nonnull private final Set<String> recordIds;

    public ZigPlayerRecordsRestoredEvent(@Nonnull UUID playerId, @Nonnull Collection<String> recordIds) {
        this.playerId = playerId;
        Set<String> named = new TreeSet<>();
        for (String recordId : recordIds) {
            if (recordId != null && !recordId.isBlank()) {
                named.add(recordId);
            }
        }
        this.recordIds = Set.copyOf(named);
    }

    /** The player whose records were put in place. */
    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The save keys of every record put in place. */
    @Nonnull
    public Set<String> recordIds() {
        return recordIds;
    }

    /** Was the record saved under {@code recordId} among those put in place? */
    public boolean restored(@Nullable String recordId) {
        return recordId != null && recordIds.contains(recordId);
    }
}
