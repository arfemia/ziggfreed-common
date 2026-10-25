package com.ziggfreed.common.track;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.playerdata.PlayerRecords;
import com.ziggfreed.common.track.boost.HeldBoosts;
import com.ziggfreed.common.util.SafeLog;

/**
 * The player store: a player's climbs on their own entity ({@link TrackComponent}), which the engine saves
 * with the player. A live player is read and written on their world thread; an offline one is read from the
 * engine's own player storage (the saved player file), which a write never touches: a climb moves only while
 * its player is here. A player's own boosts ride the same record ({@link #boosts}), read and written live only.
 * Every write that lands says so ({@code PlayerRecords.changed}), so a consumer keeping the record elsewhere saves it.
 */
public final class PlayerTrackStore implements TrackStore {

    /** The one instance the library registers for {@code "player"}. */
    public static final PlayerTrackStore INSTANCE = new PlayerTrackStore();

    private PlayerTrackStore() {
    }

    @Override
    @Nullable
    public TrackRecord get(@Nonnull TrackKey key) {
        TrackComponent record = live(key);
        return record == null ? null : record.get(key);
    }

    @Override
    public boolean put(@Nonnull TrackKey key, @Nonnull TrackRecord record) {
        TrackComponent held = live(key);
        if (held == null) {
            return false;
        }
        held.put(key, record);
        PlayerRecords.changed(key.playerId(), TrackComponent.REGISTRY_ID);
        return true;
    }

    @Override
    @Nonnull
    public CompletableFuture<TrackRecord> load(@Nonnull TrackKey key) {
        UUID playerId = key.playerId();
        ComponentType<EntityStore, TrackComponent> type = TrackComponent.TYPE;
        if (playerId == null || type == null) {
            return CompletableFuture.completedFuture(TrackRecord.EMPTY);
        }
        try {
            Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
            if (ref != null) {
                World world = ref.getStore().getExternalData().getWorld();
                if (world != null && world.isInThread()) {
                    TrackRecord record = get(key);
                    return CompletableFuture.completedFuture(record == null ? TrackRecord.EMPTY : record);
                }
                if (world != null && world.isAlive()) {
                    return CompletableFuture.supplyAsync(() -> {
                        TrackRecord record = get(key);
                        return record == null ? TrackRecord.EMPTY : record;
                    }, world);
                }
            }
            Universe universe = Universe.get();
            if (universe == null) {
                return CompletableFuture.completedFuture(TrackRecord.EMPTY);
            }
            return universe.getPlayerStorage().load(playerId).handle((holder, failure) -> {
                if (failure != null) {
                    SafeLog.fine("[track] could not read the saved player " + playerId + ": " + failure.getMessage());
                    return TrackRecord.EMPTY;
                }
                return recordIn(holder, type, key);
            });
        } catch (Throwable t) {
            SafeLog.fine("[track] could not read the climb of " + playerId + ": " + t.getMessage());
            return CompletableFuture.completedFuture(TrackRecord.EMPTY);
        }
    }

    @Override
    @Nullable
    public HeldBoosts boosts(@Nonnull TrackKey.Subject subject) {
        TrackComponent record = live(subject);
        return record == null ? null : record.boosts();
    }

    @Override
    public boolean putBoosts(@Nonnull TrackKey.Subject subject, @Nonnull HeldBoosts boosts) {
        TrackComponent held = live(subject);
        if (held == null) {
            return false;
        }
        held.boosts(boosts);
        PlayerRecords.changed(subject.playerId(), TrackComponent.REGISTRY_ID);
        return true;
    }

    /** The live record of the player {@code subject} is, read on their world thread, or null. */
    @Nullable
    private static TrackComponent live(@Nonnull TrackKey.Subject subject) {
        UUID playerId = subject.playerId();
        Ref<EntityStore> ref = playerId == null ? null : PlayerAccess.liveRef(playerId);
        return ref == null ? null : live(ref.getStore(), ref);
    }

    /** The record {@code key} names in a loaded player, {@link TrackRecord#EMPTY} when it carries none. */
    @Nonnull
    private static TrackRecord recordIn(@Nullable Holder<EntityStore> holder,
            @Nonnull ComponentType<EntityStore, TrackComponent> type, @Nonnull TrackKey key) {
        TrackComponent record = holder == null ? null : holder.getComponent(type);
        return record == null ? TrackRecord.EMPTY : record.get(key);
    }

    /**
     * The live record of {@code key}'s player, read on their world thread, or null when they are not live, the
     * type never registered, or the read fails (off their thread among them).
     */
    @Nullable
    static TrackComponent live(@Nonnull TrackKey key) {
        UUID playerId = key.playerId();
        Ref<EntityStore> ref = playerId == null ? null : PlayerAccess.liveRef(playerId);
        return ref == null ? null : live(ref.getStore(), ref);
    }

    /** The record on the player at {@code ref}, read on their world thread, or null. */
    @Nullable
    static TrackComponent live(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, TrackComponent> type = TrackComponent.TYPE;
        if (type == null) {
            return null;
        }
        try {
            return store.getComponent(ref, type);
        } catch (Throwable t) {
            SafeLog.fine("[track] could not read a player's track record: " + t.getMessage());
            return null;
        }
    }
}
