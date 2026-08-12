package com.ziggfreed.common.achievement.event;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires the achievement engine's native event POJOs on the shared engine event bus.
 *
 * <p><b>The contract, identical for every fire:</b> resolve the dispatcher, guard on
 * {@code hasListener()} so a server with no listeners pays nothing at all, then dispatch
 * synchronously on the CALLING thread. Fire from a world-thread context - a listener runs
 * synchronously on the firing thread, so it can resolve a player and then hop if it needs to.
 *
 * <p>The whole body of every fire is guarded. These events are an outbound courtesy: a listener
 * blowing up, or an event bus that is not there yet, must never take an unlock down with it. A
 * failure is logged and the engine carries on.
 *
 * <p>This is the entire cross-mod surface for achievement moments. A consumer publishes by letting
 * the engine fire, and a third party listens with no compile-time dependency on either side beyond
 * these four classes.
 */
public final class AchievementEvents {

    private AchievementEvents() {
    }

    /** One criterion moved (and possibly finished). */
    public static void fireProgressed(@Nonnull String achievementId, int criterionIndex,
                                      @Nonnull UUID playerId, int current, int required,
                                      boolean justCompleted, @Nonnull List<String> tags) {
        fire("AchievementProgressed", AchievementProgressedEvent.class,
                () -> new AchievementProgressedEvent(achievementId, criterionIndex, playerId, current,
                        required, justCompleted, tags));
    }

    /** A subject earned an achievement. */
    public static void fireUnlocked(@Nonnull String achievementId, @Nonnull UUID playerId, int points,
                                    boolean awaitingClaim, @Nonnull List<String> tags) {
        fire("AchievementUnlocked", AchievementUnlockedEvent.class,
                () -> new AchievementUnlockedEvent(achievementId, playerId, points, awaitingClaim, tags));
    }

    /** The rewards were paid out. */
    public static void fireClaimed(@Nonnull String achievementId, @Nonnull UUID playerId, int granted,
                                   int queued, int failed, @Nonnull List<String> tags) {
        fire("AchievementClaimed", AchievementClaimedEvent.class,
                () -> new AchievementClaimedEvent(achievementId, playerId, granted, queued, failed, tags));
    }

    /**
     * The one dispatch body every fire shares. The event is BUILT lazily, after the listener check,
     * so a server nobody is listening on never allocates one - which matters for the per-criterion
     * event that fires on ordinary play.
     */
    private static <E extends IEvent<Void>> void fire(@Nonnull String label, @Nonnull Class<E> type,
                                                      @Nonnull Supplier<E> build) {
        try {
            IEventDispatcher<E, E> dispatcher = HytaleServer.get().getEventBus().dispatchFor(type);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(build.get());
            }
        } catch (Throwable t) {
            SafeLog.warn("[achievement] failed to fire " + label + " event: " + t.getMessage());
        }
    }
}
