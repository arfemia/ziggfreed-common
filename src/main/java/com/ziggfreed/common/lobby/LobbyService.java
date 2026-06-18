package com.ziggfreed.common.lobby;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.Universe;

/**
 * Owns the live {@link MatchmakingQueue}s (one per {@link QueueKey}), the single
 * global "a player is in at most one queue" reservation, and the one daemon scheduler
 * the queues' fill window + countdown run on. A consumer holds ONE {@code LobbyService}
 * and calls {@link #queue} (get-or-create) to reach a queue, supplying that queue's
 * {@link LobbyConfig} + the injected {@link RoundLauncher}/{@code alreadyEngaged}/
 * {@link QueueMessages} seams; the queue keeps no consumer/world types.
 *
 * <p>The default scheduler mirrors Kweebec's {@code RoundStateMachine}: a single
 * daemon {@code ScheduledExecutorService}. Tests inject a manual {@link DelayScheduler}
 * (the package-private constructor) to drive the state machine deterministically.
 */
public final class LobbyService {

    private final Map<QueueKey, MatchmakingQueue> queues = new ConcurrentHashMap<>();
    /** Global single-queue-per-player invariant (mirrors a round registry's playerToRound). */
    private final Map<UUID, QueueKey> queuedTo = new ConcurrentHashMap<>();

    private final DelayScheduler scheduler;
    @Nullable private final ScheduledExecutorService ownedExecutor;

    /** Production: an owned single daemon scheduled executor. */
    public LobbyService() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ziggfreed-lobby");
            t.setDaemon(true);
            return t;
        });
        this.ownedExecutor = exec;
        this.scheduler = executorScheduler(exec);
    }

    /** Test seam: a caller-supplied (manual) scheduler; no executor is owned. */
    LobbyService(@Nonnull DelayScheduler scheduler) {
        this.scheduler = scheduler;
        this.ownedExecutor = null;
    }

    /**
     * The queue for {@code key}, creating it (with these seams) if absent or CLOSED.
     * Presence is resolved via {@link #universeOnline()} at launch. Equivalent to the
     * 6-arg form with the default online predicate.
     */
    @Nonnull
    public MatchmakingQueue queue(@Nonnull QueueKey key, @Nonnull LobbyConfig config,
                                  @Nonnull RoundLauncher launcher, @Nonnull Predicate<UUID> alreadyEngaged,
                                  @Nonnull QueueMessages messages) {
        return queue(key, config, launcher, alreadyEngaged, universeOnline(), messages);
    }

    /**
     * The queue for {@code key}, creating it (with these seams) if absent or CLOSED.
     * {@code online} is the presence re-validation used at the launch snapshot.
     */
    @Nonnull
    public MatchmakingQueue queue(@Nonnull QueueKey key, @Nonnull LobbyConfig config,
                                  @Nonnull RoundLauncher launcher, @Nonnull Predicate<UUID> alreadyEngaged,
                                  @Nonnull Predicate<UUID> online, @Nonnull QueueMessages messages) {
        return queues.compute(key, (k, existing) -> {
            if (existing != null && existing.state() != QueueState.CLOSED) {
                return existing;
            }
            return new MatchmakingQueue(k, this, config, launcher, alreadyEngaged, online, messages, scheduler);
        });
    }

    /** True if {@code uuid} is sitting in any queue right now. */
    public boolean isQueued(@Nonnull UUID uuid) {
        return queuedTo.containsKey(uuid);
    }

    /** The key of the queue {@code uuid} is in, or {@code null}. */
    @Nullable
    public QueueKey queueKeyOf(@Nonnull UUID uuid) {
        return queuedTo.get(uuid);
    }

    /** Remove {@code uuid} from whatever queue it is in. Returns false if it was not queued. */
    public boolean leave(@Nonnull UUID uuid) {
        QueueKey k = queuedTo.get(uuid);
        if (k == null) {
            return false;
        }
        MatchmakingQueue q = queues.get(k);
        if (q == null) {
            queuedTo.remove(uuid, k);
            return false;
        }
        return q.leave(uuid);
    }

    /** Stop the owned scheduler (no-op for the test constructor's injected one). */
    public void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // ==================== package-private hooks the queue calls ====================

    /** Atomically reserve {@code uuid} to {@code key}; false if already reserved elsewhere. */
    boolean tryReserve(@Nonnull UUID uuid, @Nonnull QueueKey key) {
        return queuedTo.putIfAbsent(uuid, key) == null;
    }

    /** Release {@code uuid}'s reservation iff it is still held by {@code key}. */
    void release(@Nonnull UUID uuid, @Nonnull QueueKey key) {
        queuedTo.remove(uuid, key);
    }

    /** Drop {@code key} from the live map iff it still maps to {@code q} (a closed queue). */
    void removeQueue(@Nonnull QueueKey key, @Nonnull MatchmakingQueue q) {
        queues.remove(key, q);
    }

    // ==================== defaults ====================

    /** The production presence predicate: a store-free {@code Universe.getPlayer} lookup, guarded. */
    @Nonnull
    public static Predicate<UUID> universeOnline() {
        return uuid -> {
            try {
                return Universe.get().getPlayer(uuid) != null;
            } catch (Throwable t) {
                return false;
            }
        };
    }

    @Nonnull
    private static DelayScheduler executorScheduler(@Nonnull ScheduledExecutorService exec) {
        return (task, delay, unit) -> {
            final ScheduledFuture<?> f = exec.schedule(task, delay, unit);
            return () -> f.cancel(false);
        };
    }
}
