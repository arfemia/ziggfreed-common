package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

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
 * five classes.
 */
public final class QuestEvents {

    private QuestEvents() {
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

    /**
     * The one dispatch body every fire shares. The event is BUILT lazily, after the listener check,
     * so a server nobody is listening on never allocates one - which matters for the per-objective
     * event that fires on ordinary play.
     */
    private static <E extends IEvent<Void>> void fire(@Nonnull String label, @Nonnull Class<E> type,
                                                      @Nonnull java.util.function.Supplier<E> build) {
        try {
            IEventDispatcher<E, E> dispatcher = HytaleServer.get().getEventBus().dispatchFor(type);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(build.get());
            }
        } catch (Throwable t) {
            SafeLog.warn("[quest] failed to fire " + label + " event: " + t.getMessage());
        }
    }
}
