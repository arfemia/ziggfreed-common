package com.ziggfreed.common.quest.event;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Fires the quest engine's native event POJOs on the shared engine event bus.
 *
 * <p><b>The contract is the library-wide one</b> ({@link NativeEventSeam}): guard on
 * {@code hasListener()} so a server with no listeners pays nothing at all, build the event only
 * then, dispatch synchronously on the CALLING thread, and never let a failure escape. Fire from a
 * world-thread context - a listener runs synchronously on the firing thread, so it can resolve a
 * player and then hop if it needs to. A listener blowing up, or an event bus that is not there yet,
 * must never take a quest completion down with it: the failure is logged and the quest carries on.
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
     * Where a fired quest event is published: this family's own name for the shared
     * {@link NativeEventSeam.Publisher}. Asked for every fire; {@code build} is to be called only
     * when somebody is actually listening, because it is asked on every objective tick of ordinary
     * play.
     */
    public interface Publisher extends NativeEventSeam.Publisher {
    }

    /** The shared engine bus: dispatch when it has a listener, allocate nothing when it has none. */
    public static final NativeEventSeam.Publisher ENGINE_BUS = NativeEventSeam.ENGINE_BUS;

    private static final NativeEventSeam SEAM = new NativeEventSeam("[quest]");

    private QuestEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host running the engine outside a Hytale server, and for a test observing what the
     * engine fires. Not for a mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable Publisher publisher) {
        SEAM.publishTo(publisher);
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
     * The one dispatch body every fire shares, which is the seam's. The event is BUILT lazily,
     * after the listener check, so a server nobody is listening on never allocates one - which
     * matters for the per-objective event that fires on ordinary play.
     */
    private static <E extends IEvent<Void>> void fire(@Nonnull String label, @Nonnull Class<E> type,
                                                      @Nonnull Supplier<E> build) {
        SEAM.fire(label, type, build);
    }
}
