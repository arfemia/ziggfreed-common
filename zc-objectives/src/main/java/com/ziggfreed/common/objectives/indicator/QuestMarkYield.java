package com.ziggfreed.common.objectives.indicator;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * Whether a consumer still draws its own quest marks this boot, so the library's stand down and
 * nothing is drawn twice.
 *
 * <p>The library draws every quest mark itself ({@code objectives/marker}). An older consumer drew
 * its own overheads and map marks off this package's two legacy reads,
 * {@link QuestIndicators#overheadAt} and {@link QuestIndicators#mapMarksFor}; the first such call, on
 * any thread, is recorded here for the rest of the boot, and every listener is told once. What stands
 * down is what such a consumer draws: the overheads, and the map marks at characters in the viewer's
 * own world. A tracked quest's pointer through a gateway stays, since such a consumer never draws one.
 * The library recognises the consumer by the reads it calls, never by its name.
 */
public final class QuestMarkYield {

    private static final AtomicReference<String> VIA = new AtomicReference<>();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private QuestMarkYield() {
    }

    /** Has a consumer been seen drawing its own quest marks this boot? */
    public static boolean consumerDraws() {
        return VIA.get() != null;
    }

    /** The legacy read that first gave it away, or null. */
    @Nullable
    public static String via() {
        return VIA.get();
    }

    /** Be told once, the moment a consumer is first seen drawing its own marks. */
    public static void onConsumerDraws(@Nonnull Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Record that a legacy read was called; the first call wins, logs once and tells every listener. */
    static void noteConsumerDraws(@Nonnull String via) {
        if (!VIA.compareAndSet(null, via)) {
            return;
        }
        SafeLog.info("[progression] a consumer draws its own quest marks (it called " + via + "), so the"
                + " library's overheads and quest map marks stand down for this boot; a tracked quest's pointer"
                + " through a gateway stays on");
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Throwable t) {
                SafeLog.warn("[progression] a quest-mark stand-down listener failed: " + t.getMessage());
            }
        }
    }

    /** Forget the boot's record and every listener. Tests only. */
    static void resetForTests() {
        VIA.set(null);
        LISTENERS.clear();
    }
}
