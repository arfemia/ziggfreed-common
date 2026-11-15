package com.ziggfreed.common.playerdata;

import java.util.Collection;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.inventory.PlayerAccess;

/**
 * The saved-record family's two native events, under the library-wide fire contract ({@link NativeEventSeam}): built
 * only when somebody listens, dispatched on the calling world thread, never able to take the write that caused it down.
 *
 * <p>{@link #changed} is what a record's write path calls once its write landed ({@link ZigPlayerRecordChangedEvent});
 * {@link #restored} is what a consumer keeping player data outside the engine's save calls once it put records in
 * place ({@link ZigPlayerRecordsRestoredEvent}). With nobody listening a call costs one listener check.
 */
public final class PlayerRecords {

    private static final NativeEventSeam EVENTS = new NativeEventSeam("[record]");

    private PlayerRecords() {
    }

    /** Route every fire through {@code publisher} instead of the engine bus; null restores the bus. For a host or a test. */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        EVENTS.publishTo(publisher);
    }

    /** The record saved under {@code recordId} changed for the player {@code playerId}; nothing for no player. */
    public static void changed(@Nullable UUID playerId, @Nonnull String recordId) {
        if (playerId == null || !EVENTS.listening(ZigPlayerRecordChangedEvent.class)) {
            return;
        }
        EVENTS.fire("ZigPlayerRecordChanged", ZigPlayerRecordChangedEvent.class,
                () -> new ZigPlayerRecordChangedEvent(playerId, recordId));
    }

    /**
     * The record saved under {@code recordId} changed for the player at {@code ref}, read on that entity's world thread.
     * The player is looked up only while somebody listens; an entity that is no player, or a reference no longer
     * valid, fires nothing.
     */
    public static void changed(@Nullable ComponentAccessor<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull String recordId) {
        if (store == null || ref == null || !EVENTS.listening(ZigPlayerRecordChangedEvent.class)) {
            return;
        }
        UUID playerId;
        try {
            PlayerRef player = ref.isValid() ? PlayerAccess.playerRef(store, ref) : null;
            playerId = player == null ? null : player.getUuid();
        } catch (Throwable t) {
            playerId = null;
        }
        changed(playerId, recordId);
    }

    /**
     * The records saved under {@code recordIds} were put in place for the player {@code playerId} by a store outside the
     * engine's save. Nothing for no player or no record.
     */
    public static void restored(@Nullable UUID playerId, @Nullable Collection<String> recordIds) {
        if (playerId == null || recordIds == null || recordIds.isEmpty()
                || !EVENTS.listening(ZigPlayerRecordsRestoredEvent.class)) {
            return;
        }
        EVENTS.fire("ZigPlayerRecordsRestored", ZigPlayerRecordsRestoredEvent.class,
                () -> new ZigPlayerRecordsRestoredEvent(playerId, recordIds));
    }
}
