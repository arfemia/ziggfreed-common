package com.ziggfreed.common.cast;

import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.event.NativeEventSeam;

/**
 * Where the cast family's native event ({@link ZigCastHitEvent}) goes: the shared engine event bus,
 * under the library-wide contract ({@link NativeEventSeam}).
 *
 * <p>A hit is a hot path (one fire per target per hit, a beam's every tick), so the one caller,
 * {@link HitResolver#applyHit}, asks {@link #listeningForHits} before it builds anything: nobody
 * listening costs one listener check and no allocation. A third party listens on the bus with no
 * compile-time dependency beyond the event class; a host running without a bus (a harness, a unit JVM)
 * redirects the family through {@link #publishTo}.
 */
public final class CastEvents {

    private static final NativeEventSeam SEAM = new NativeEventSeam("[hit]");

    private CastEvents() {
    }

    /**
     * Route every fire through {@code publisher} instead of the engine bus; null restores the bus.
     * For a host outside a Hytale server and for a test observing what the family fires. Not for a
     * mod: on a live server the bus is the one place a listener looks.
     */
    public static void publishTo(@Nullable NativeEventSeam.Publisher publisher) {
        SEAM.publishTo(publisher);
    }

    /** Is anybody listening for {@link ZigCastHitEvent}? Never throws. */
    static boolean listeningForHits() {
        return SEAM.listening(ZigCastHitEvent.class);
    }

    /** Announce one applied hit. Never throws. */
    static void fireHit(@Nonnull Supplier<ZigCastHitEvent> build) {
        SEAM.fire("ZigCastHit", ZigCastHitEvent.class, build);
    }
}
