package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires the quest engine's native event POJOs on the shared engine event bus.
 *
 * <p><b>The contract, identical for every fire:</b> resolve the dispatcher, guard on
 * {@code hasListener()} so a server with no listeners pays nothing at all, then dispatch
 * synchronously on the CALLING thread. Fire from a world-thread context - a listener runs
 * synchronously on the firing thread, so it can resolve a player and then hop if it needs to.
 *
 * <p>The whole body of every fire is guarded. These events are an outbound courtesy: a listener
 * blowing up, or an event bus that is not there yet, must never take a quest completion down with
 * it. A failure is logged and the quest carries on.
 *
 * <p>This is the entire cross-mod surface for quest moments. A consumer publishes by letting the
 * engine fire, and a third party listens with no compile-time dependency on either side beyond these
 * six classes.
 *
 * <p><b>Where the events GO is a seam</b> ({@link Publisher}), and the default is the engine bus.
 * The engine is consumer-agnostic and runs wherever it is built - a Hytale server, a headless
 * harness, a unit JVM with no bus at all - and a host in the last two cases installs a publisher of
 * its own to see what would have gone out. Nothing on a live server ever calls {@link #publishTo}.
 */
public final class QuestEvents {

    /**
     * Where a fired event is published. Asked for every fire; {@code build} is to be called only
     * when somebody is actually listening, because it is asked on every objective tick of ordinary
     * play.
     */
    public interface Publisher {

        <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build);
    }

    /** The shared engine bus: dispatch when it has a listener, allocate nothing when it has none. */
    public static final Publisher ENGINE_BUS = new Publisher() {
        @Override
        public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
            IEventDispatcher<E, E> dispatcher = HytaleServer.get().getEventBus().dispatchFor(type);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(build.get());
            }
        }
    };

    private static final AtomicReference<Publisher> PUBLISHER = new AtomicReference<>(ENGINE_BUS);

    private QuestEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host running the engine outside a Hytale server, and for a test observing what the
     * engine fires. Not for a mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable Publisher publisher) {
        PUBLISHER.set(publisher != null ? publisher : ENGINE_BUS);
    }

    /** A player took on a quest. */
    public static void fireAccepted(@Nonnull String questId, @Nonnull UUID playerId,
                                    @Nonnull List<String> tags) {
        fire("QuestAccepted", QuestAcceptedEvent.class,
                () -> new QuestAcceptedEvent(questId, playerId, tags));
    }

    /** One objective moved (and possibly finished). */
    public static void fireObjectiveProgressed(@Nonnull String questId, @Nonnull String objectiveId,
                                               @Nonnull UUID playerId, int current, int required,
                                               boolean justCompleted, @Nonnull List<String> tags) {
        fire("QuestObjectiveProgressed", QuestObjectiveProgressedEvent.class,
                () -> new QuestObjectiveProgressedEvent(questId, objectiveId, playerId, current,
                        required, justCompleted, tags));
    }

    /** Every objective is met; {@code parked} says whether the reward is waiting to be claimed. */
    public static void fireCompleted(@Nonnull String questId, @Nonnull UUID playerId, boolean parked,
                                     @Nonnull List<String> tags) {
        fire("QuestCompleted", QuestCompletedEvent.class,
                () -> new QuestCompletedEvent(questId, playerId, parked, tags));
    }

    /** The rewards were paid out. */
    public static void fireClaimed(@Nonnull String questId, @Nonnull UUID playerId, int granted,
                                   int queued, int failed, @Nonnull List<String> tags) {
        fire("QuestClaimed", QuestClaimedEvent.class,
                () -> new QuestClaimedEvent(questId, playerId, granted, queued, failed, tags));
    }

    /** The player gave the quest up. */
    public static void fireAbandoned(@Nonnull String questId, @Nonnull UUID playerId,
                                     @Nonnull List<String> tags) {
        fire("QuestAbandoned", QuestAbandonedEvent.class,
                () -> new QuestAbandonedEvent(questId, playerId, tags));
    }

    /** A quest was pinned to ({@code tracked}) or unpinned from the player's tracker. */
    public static void fireTracked(@Nonnull String questId, @Nonnull UUID playerId, boolean tracked,
                                   @Nonnull List<String> tags) {
        fire("QuestTracked", QuestTrackedEvent.class,
                () -> new QuestTrackedEvent(questId, playerId, tracked, tags));
    }

    /**
     * The one dispatch body every fire shares. The event is BUILT lazily, after the listener check,
     * so a server nobody is listening on never allocates one - which matters for the per-objective
     * event that fires on ordinary play.
     */
    private static <E extends IEvent<Void>> void fire(@Nonnull String label, @Nonnull Class<E> type,
                                                      @Nonnull Supplier<E> build) {
        try {
            PUBLISHER.get().publish(type, build);
        } catch (Throwable t) {
            SafeLog.warn("[quest] failed to fire " + label + " event: " + t.getMessage());
        }
    }
}
