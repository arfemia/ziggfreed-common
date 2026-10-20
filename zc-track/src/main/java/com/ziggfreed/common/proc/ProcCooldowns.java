package com.ziggfreed.common.proc;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The procs' waits: when each proc may run again, for each player (by their entity UUID, so a world change keeps a
 * wait) and, for a proc that waits per target, for each target (by its ref, dropped the moment the target leaves its
 * store, {@link ProcSystems.Left}). Transient and single-process: nothing is saved, and a player's waits go when they
 * disconnect. The engine keeps no per-player cooldown service a mod can reach, so this is the library's one.
 */
public final class ProcCooldowns {

    /** One wait: a proc, and the target it is kept for (null: the player's own). */
    private record Key(@Nonnull String proc, @Nullable Ref<EntityStore> target) {
    }

    /** How many waits a player may hold before the expired ones are swept. */
    private static final int SWEEP_AT = 64;

    private static final Map<UUID, Map<Key, Long>> BY_HOLDER = new ConcurrentHashMap<>();

    private ProcCooldowns() {
    }

    /** May the proc {@code proc} run for {@code holder} (on {@code target}, for a per-target wait) at {@code nanos}? */
    static boolean ready(@Nonnull UUID holder, @Nonnull String proc, @Nullable Ref<EntityStore> target, long nanos) {
        Map<Key, Long> waits = BY_HOLDER.get(holder);
        Long until = waits == null ? null : waits.get(new Key(proc, target));
        return until == null || nanos >= until;
    }

    /** Start the wait the proc runs into: {@code seconds}, cut by {@code cutPoints} held to {@code cutCeiling}. */
    static void stamp(@Nonnull UUID holder, @Nonnull String proc, @Nullable Ref<EntityStore> target, long nanos,
            double seconds, double cutPoints, double cutCeiling) {
        long until = readyAt(nanos, seconds, cutPoints, cutCeiling);
        if (until <= nanos) {
            return;
        }
        Map<Key, Long> waits = BY_HOLDER.computeIfAbsent(holder, h -> new ConcurrentHashMap<>());
        if (waits.size() >= SWEEP_AT) {
            waits.values().removeIf(at -> at <= nanos);
        }
        waits.put(new Key(proc, target), until);
    }

    /**
     * When a wait of {@code seconds} started at {@code nanos} ends, cut by {@code cutPoints} whole percent points held
     * to [0, {@code cutCeiling}] (the ceiling itself held to [0, 100]). {@code nanos} itself for no wait. Pure.
     */
    static long readyAt(long nanos, double seconds, double cutPoints, double cutCeiling) {
        if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
            return nanos;
        }
        double ceiling = Double.isFinite(cutCeiling) ? Math.max(0.0, Math.min(100.0, cutCeiling)) : 0.0;
        double cut = Double.isFinite(cutPoints) ? Math.max(0.0, Math.min(cutPoints, ceiling)) : 0.0;
        double wait = seconds * (1.0 - cut / 100.0);
        return wait > 0.0 ? nanos + (long) (wait * 1_000_000_000L) : nanos;
    }

    /** Forget every wait kept for {@code target} (it left its store). */
    static void forgetTarget(@Nonnull Ref<EntityStore> target) {
        for (Map<Key, Long> waits : BY_HOLDER.values()) {
            Iterator<Key> keys = waits.keySet().iterator();
            while (keys.hasNext()) {
                if (keys.next().target() == target) {
                    keys.remove();
                }
            }
        }
    }

    /** Forget a departing player's waits. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef ref = event.getPlayerRef();
            UUID id = ref == null ? null : ref.getUuid();
            if (id != null) {
                BY_HOLDER.remove(id);
            }
        } catch (Throwable t) {
            SafeLog.fine("[proc] could not forget a departing player's waits: " + t.getMessage());
        }
    }

    /** Forget everything (a test). */
    static void clear() {
        BY_HOLDER.clear();
    }
}
