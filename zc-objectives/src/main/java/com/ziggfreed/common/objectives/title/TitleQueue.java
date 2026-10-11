package com.ziggfreed.common.objectives.title;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.title.PendingTitles;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * Pays the queue of title changes owed to a player who was not online when they were made
 * ({@link PendingTitles}, filled by {@link ZigTitles}): at every player-ready, on the player's world
 * thread, each queued grant and revoke goes through {@link TitleUnlocks} in the order it was queued,
 * so it is announced, shows itself and refreshes the record exactly as if it had been made online.
 *
 * <p>It runs one beat before {@link TitleBackfill}'s pass ({@link #READY_PRIORITY}, registered by
 * {@link TitleBootstrap}), so a queued revoke lands before the backfill reads what the player's
 * achievements owe. The queue is left alone for a player who carries no title record, so nothing
 * queued is lost to a registration that failed.
 */
public final class TitleQueue {

    /** The ready beat: one before {@link TitleBackfill}'s {@code LATE}, so the queue is paid first. */
    public static final short READY_PRIORITY = (short) (EventPriority.LATE.getValue() - 1);

    private TitleQueue() {
    }

    /** The ready handler {@link TitleBootstrap} registers: hop to the player's world thread. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> payOnWorldThread(player.getReference()));
        } catch (Throwable t) {
            SafeLog.warn("[title] the queued title changes could not be paid at login", t);
        }
    }

    /**
     * Pay {@code playerId}'s queue now if they are online, on their world thread; nothing for a player
     * who is not. Any thread. The facade calls it after queueing, for a player who came online meanwhile.
     */
    static void payIfOnline(@Nonnull UUID playerId) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(playerId);
        if (ref == null || !PendingTitles.has(playerId)) {
            return;
        }
        try {
            ref.getStore().getExternalData().getWorld().execute(() -> payOnWorldThread(ref));
        } catch (Throwable t) {
            SafeLog.fine("[title] a queued title change waits for the next login: " + t.getMessage());
        }
    }

    /**
     * World thread: pay everything queued for the player behind {@code ref}, oldest first. Anything
     * about to write a player's titles on their world thread calls it first (the facade's hop, an
     * admin verb), so a change queued earlier never lands after a later one. Never throws.
     */
    public static void payOnWorldThread(@Nullable Ref<EntityStore> ref) {
        try {
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null || TitleUnlocks.peek(store, ref) == null) {
                return;
            }
            pay(store, ref, playerRef, PendingTitles.drain(playerRef.getUuid()));
        } catch (Throwable t) {
            SafeLog.warn("[title] the queued title changes could not be paid", t);
        }
    }

    /** World thread: make each of {@code changes} through the write path, each on its own guard. */
    private static void pay(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef, @Nonnull List<PendingTitles.Change> changes) {
        for (PendingTitles.Change change : changes) {
            // Each change on its own: one that throws costs itself, never the changes after it.
            try {
                if (change.kind() == PendingTitles.Kind.GRANT) {
                    TitleUnlocks.unlock(store, ref, playerRef, change.titleId());
                } else {
                    TitleUnlocks.revoke(store, ref, playerRef, change.titleId());
                }
            } catch (Throwable t) {
                SafeLog.warn("[title] the queued " + change.kind() + " of '" + change.titleId()
                        + "' could not be made", t);
            }
        }
    }
}
