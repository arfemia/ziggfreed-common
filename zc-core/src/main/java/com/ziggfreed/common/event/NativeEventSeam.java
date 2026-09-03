package com.ziggfreed.common.event;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.util.SafeLog;

/**
 * Where ONE FAMILY of native engine events goes, with the guarded fire body every family in this
 * library shares.
 *
 * <p><b>The contract, identical for every fire:</b> resolve the engine dispatcher for the event
 * type, guard on {@code hasListener()} so a server with no listeners pays nothing at all (the event
 * is BUILT lazily, after that check, so nobody listening means nothing allocated), then dispatch
 * synchronously on the CALLING thread. Fire from a world-thread context: a listener runs on the
 * firing thread, so it can resolve a player and then hop if it needs to.
 *
 * <p><b>The whole body of every fire is guarded.</b> A native event is an outbound courtesy: a
 * listener blowing up, or an event bus that is not there yet, must never take the thing that fired
 * it down with it. A failure is logged under the family's own prefix and the caller carries on.
 *
 * <p><b>Where the events GO is a seam</b> ({@link Publisher}), and the default is the engine bus.
 * An engine is consumer-agnostic and runs wherever it is built - a Hytale server, a headless
 * harness, a unit JVM with no bus at all - and a host in the last two cases installs a publisher of
 * its own through {@link #publishTo} to see what would have gone out. Nothing on a live server ever
 * calls {@link #publishTo}; a mod that wants to hear an event listens on the bus.
 *
 * <p>One instance per event family (quests, flairs, ...), so a test observing one family never
 * redirects another's, and each family's log lines carry its own prefix.
 */
public final class NativeEventSeam {

    /**
     * Where a fired event is published. Asked for every fire; {@code build} is to be called only
     * when somebody is actually listening, because some families fire on every tick of ordinary
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

    private final String logPrefix;
    private final AtomicReference<Publisher> publisher = new AtomicReference<>(ENGINE_BUS);

    /** @param logPrefix the family's own log prefix, e.g. {@code [quest]}, on every failure line */
    public NativeEventSeam(@Nonnull String logPrefix) {
        this.logPrefix = logPrefix;
    }

    /**
     * Route every fire of this family through {@code publisher} instead of the engine bus; null
     * restores the bus. For a host running an engine outside a Hytale server, and for a test
     * observing what a family fires.
     */
    public void publishTo(@Nullable Publisher publisher) {
        this.publisher.set(publisher != null ? publisher : ENGINE_BUS);
    }

    /**
     * Fire one event: build it only if somebody is listening, dispatch it on this thread, and
     * never let a failure escape.
     *
     * @param label what the event is called in a failure line
     */
    public <E extends IEvent<Void>> void fire(@Nonnull String label, @Nonnull Class<E> type,
                                              @Nonnull Supplier<E> build) {
        try {
            publisher.get().publish(type, build);
        } catch (Throwable t) {
            SafeLog.warn(logPrefix + " failed to fire " + label + " event: " + t.getMessage());
        }
    }
}
