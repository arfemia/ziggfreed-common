package com.ziggfreed.common.track.boost;

import java.util.Collection;

import javax.annotation.Nonnull;

/**
 * The arithmetic of boosts, pure: how the boosts lifting one track add up under the server's cap, and whether one
 * more start fits its owner's limits.
 *
 * <p><b>Adding up</b> ({@link #percent}): every lift adds its percent, a server boost and a personal one alike, and
 * the sum is held at or under the cap (R669: +200% unless the owner says otherwise), which bounds only how high it
 * goes, so a negative boost still takes away. With {@code highestOnly} (the MMO's "highest" mode) only the strongest
 * server lift and the strongest personal lift count, each at least 0, added together and then capped. The result
 * is a multiplier through {@link #multiplier}: +50% is 1.5, and anything at or below -100% stops the gain.
 */
final class BoostStack {

    /** One boost lifting a track: its percent, and whose it is (which bucket {@code highestOnly} keeps). */
    public record Lift(double percent, @Nonnull BoostScope scope) {
    }

    private BoostStack() {
    }

    /** The percent {@code lifts} add up to, held at or under {@code cap} (a cap below 0 reads as 0). */
    public static double percent(@Nonnull Collection<Lift> lifts, double cap, boolean highestOnly) {
        if (lifts.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        if (highestOnly) {
            double server = 0.0;
            double personal = 0.0;
            for (Lift lift : lifts) {
                double percent = finite(lift.percent());
                if (lift.scope() == BoostScope.SERVER) {
                    server = Math.max(server, percent);
                } else {
                    personal = Math.max(personal, percent);
                }
            }
            total = server + personal;
        } else {
            for (Lift lift : lifts) {
                total += finite(lift.percent());
            }
        }
        double ceiling = Double.isFinite(cap) ? Math.max(0.0, cap) : 0.0;
        return Math.min(total, ceiling);
    }

    /** The multiplier a summed {@code percent} applies: 1 + percent / 100, never below 0. */
    public static double multiplier(double percent) {
        return Math.max(0.0, 1.0 + finite(percent) / 100.0);
    }

    /**
     * Does one more start fit, for its owner (a player for a personal boost, the server for a server one)? The
     * wait first, then the boost's own {@code Stack.Max} (0 for none), then the owner's limit.
     *
     * @param cooldownUntil  when the starter's wait for this boost ends, 0 for none
     * @param runningOfThis  how many of this boost the owner runs now
     * @param stackMax       the boost's own most at once, 0 for no limit of its own
     * @param runningOfOwner how many boosts of this scope the owner runs now
     * @param ownerLimit     the most the owner may run at once
     * @return {@link BoostOutcome#STARTED} when it fits, else why not
     */
    @Nonnull
    public static BoostOutcome fits(long nowMs, long cooldownUntil, int runningOfThis, int stackMax,
            int runningOfOwner, int ownerLimit, @Nonnull BoostScope scope) {
        if (cooldownUntil > nowMs) {
            return BoostOutcome.COOLDOWN;
        }
        if (stackMax > 0 && runningOfThis >= stackMax) {
            return BoostOutcome.STACK_FULL;
        }
        if (runningOfOwner >= ownerLimit) {
            return scope == BoostScope.SERVER ? BoostOutcome.SERVER_FULL : BoostOutcome.PERSONAL_FULL;
        }
        return BoostOutcome.STARTED;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
