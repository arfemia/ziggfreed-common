package com.ziggfreed.common.feedback.moment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

/**
 * The two decisions a scoped banner makes, both pure so they can be pinned without a server: whether
 * ONE viewer is shown it ({@link #admits}), and whether the banner may show at all right now
 * ({@link Throttle}).
 *
 * <p>A participant is admitted wherever they are when the moment asks for its participants
 * ({@code ToParticipants}); everybody else is inside or outside the world and the radius the leaves
 * draw. With no leaf authored every viewer is admitted, which is the every-online fan-out a
 * server-first claim wants. A viewer whose position cannot be read is outside any radius, and a
 * viewer in another world is outside a world-scoped banner and outside any radius at all.
 */
final class BroadcastScope {

    private BroadcastScope() {
    }

    /**
     * Is this viewer shown the banner?
     *
     * @param participant    whether the moment lists the viewer among its participants
     * @param sameWorld      whether the viewer stands in the subject's own world
     * @param distanceBlocks the viewer's distance from the subject, or {@code NaN} when unknown
     */
    static boolean admits(@Nonnull FeedbackMomentAsset.Broadcast spec, boolean participant, boolean sameWorld,
            double distanceBlocks) {
        if (participant && spec.toParticipants()) {
            return true;
        }
        if (spec.sameWorldOnly() && !sameWorld) {
            return false;
        }
        Double radius = spec.getRadiusBlocks();
        if (radius != null) {
            return sameWorld && !Double.isNaN(distanceBlocks) && distanceBlocks <= radius;
        }
        return true;
    }

    /**
     * "Not again for a while": one banner per key per window. Keyed by the caller (the moment, what
     * it is about, and the world when the banner is world-scoped), so two fights announce separately
     * while one fight's per-member fires collapse into one banner. Bounded by sweeping expired keys
     * once the table grows past a few hundred, so a long-running server keeps only what is still
     * holding a banner back.
     */
    static final class Throttle {

        private static final int SWEEP_ABOVE = 256;

        private final Map<String, Long> expiresAtMs = new ConcurrentHashMap<>();

        /**
         * May the banner under {@code key} show now? A non-positive {@code minSeconds} always says
         * yes and records nothing; otherwise yes only when the previous showing's window has
         * passed, and this showing opens the next window.
         */
        boolean allow(@Nonnull String key, int minSeconds, long nowMs) {
            if (minSeconds <= 0) {
                return true;
            }
            Long until = expiresAtMs.get(key);
            if (until != null && nowMs < until) {
                return false;
            }
            expiresAtMs.put(key, nowMs + minSeconds * 1000L);
            if (expiresAtMs.size() > SWEEP_ABOVE) {
                expiresAtMs.entrySet().removeIf(entry -> entry.getValue() <= nowMs);
            }
            return true;
        }

        /** How many keys are still holding a banner back or awaiting a sweep; for the test. */
        int size() {
            return expiresAtMs.size();
        }
    }
}
