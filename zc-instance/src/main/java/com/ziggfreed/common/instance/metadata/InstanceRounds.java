package com.ziggfreed.common.instance.metadata;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires the generic round-completion moment on the shared engine event bus.
 *
 * <p><b>The contract</b>, the same one every other native fire in this library keeps: resolve the
 * dispatcher, guard on {@code hasListener()} so a server with nobody listening pays nothing at all,
 * then dispatch SYNCHRONOUSLY on the calling thread.
 *
 * <p><b>Fire on the instance world thread.</b> A listener runs inside the fire, so it can resolve a
 * player straight away and hop with {@code world.execute} if it needs a {@code Store} on some other
 * world. Firing from off a world thread is not wrong, but it hands every listener that hop to make.
 *
 * <p>The whole body is guarded. This is an outbound courtesy: a third-party listener blowing up, or
 * an event bus that is not there yet, must never take the end of a round down with it. A failure is
 * logged and the round finishes.
 */
public final class InstanceRounds {

    private InstanceRounds() {
    }

    /**
     * Announce that a round finished. Silent no-op when nothing is listening, and never propagates a
     * failure back to the caller.
     */
    public static void fireCompleted(@Nonnull InstanceRoundCompletedEvent event) {
        try {
            IEventDispatcher<InstanceRoundCompletedEvent, InstanceRoundCompletedEvent> dispatcher =
                    HytaleServer.get().getEventBus().dispatchFor(InstanceRoundCompletedEvent.class);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(event);
            }
        } catch (Throwable t) {
            SafeLog.warn("[instance] failed to fire InstanceRoundCompleted event", t);
        }
    }
}
