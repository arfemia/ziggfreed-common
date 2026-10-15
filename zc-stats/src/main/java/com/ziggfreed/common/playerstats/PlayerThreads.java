package com.ziggfreed.common.playerstats;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Counting a stat for a player named only by id (a fight's participant, a round's winner), on the world thread
 * that owns their record: the one hop every listener off the shared bus takes, since {@link PlayerStats} writes on
 * that thread alone.
 */
public final class PlayerThreads {

    /** What runs on a player's world thread, handed their live store and entity. */
    @FunctionalInterface
    public interface Task {

        void run(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref);
    }

    private PlayerThreads() {
    }

    /**
     * Run {@code task} for {@code playerId} on the world thread they are on now: inline when that is the thread
     * running, else queued there and the player found again when it runs, since a tick later a held entity
     * reference may name whoever took its slot. False when they are offline or their world is gone. A player who
     * changed world before the queued task runs is skipped with one INFO line naming {@code stats} (the ones the
     * task counts) and {@code line}, never the player; a queued task that throws is one WARN line naming
     * {@code line}, never the player either.
     *
     * @param log   the log prefix the lines carry, such as {@code [encounter]}
     * @param what  what is being counted, in the log's words ({@code fight}, {@code round})
     * @param line  the stat line the task counts on, for the log
     * @param stats the stats the task counts, for the log
     */
    public static boolean run(@Nonnull UUID playerId, @Nonnull String log, @Nonnull String what,
            @Nonnull String line, @Nonnull List<String> stats, @Nonnull Task task) {
        Ref<EntityStore> ref = liveRef(playerId);
        World world = ref == null ? null : ref.getStore().getExternalData().getWorld();
        if (world == null || !world.isAlive()) {
            return false;
        }
        if (world.isInThread()) {
            task.run(ref.getStore(), ref);
            return true;
        }
        world.execute(() -> {
            try {
                Ref<EntityStore> now = liveRef(playerId);
                if (now != null && now.getStore().isInThread()) {
                    task.run(now.getStore(), now);
                } else if (now != null) {
                    SafeLog.info(log + " a player changed world before their " + what + " was counted, so "
                            + String.join(", ", stats) + " under '" + line + "' were skipped for them");
                }
            } catch (Throwable t) {
                SafeLog.warn(log + " the stat store could not count a player's " + what + " under '" + line + "'", t);
            }
        });
        return true;
    }

    /** The player's live entity, or null when they are offline or no longer in a store. */
    @Nullable
    private static Ref<EntityStore> liveRef(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(playerId);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        return ref != null && ref.isValid() ? ref : null;
    }
}
