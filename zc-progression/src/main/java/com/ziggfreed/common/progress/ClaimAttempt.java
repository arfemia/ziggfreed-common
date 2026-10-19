package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardGrants;

/**
 * What one Collect press did, answered by the lifecycle engine that took it: what it paid, or why it paid
 * nothing. Exactly one of the two is set.
 *
 * <p>Both engines answer a collect with this ({@code attemptClaim}; their {@code tryClaim} is its {@link #paid()}),
 * so a surface tells "make room" apart from "nothing to collect" off the very check that stopped the payout,
 * never off a second probe of its own that could answer what the payout did not. A refused press pays, spends
 * and records nothing: whatever was waiting still waits.
 *
 * @param paid    the payout's outcome when the press collected, whose {@link RewardGrants.GrantOutcome#receipt()
 *                receipt} is what actually reached the subject; null when it was refused
 * @param refusal why the press was refused; null when it collected
 */
public record ClaimAttempt(@Nullable RewardGrants.GrantOutcome paid, @Nullable Refusal refusal) {

    /** Why a collect paid nothing, each the engine's own check, asked in this order. */
    public enum Refusal {
        /** Nothing waits to be collected: not finished, already collected, or not known. */
        NOT_WAITING,
        /** It waits, but only somewhere else: content bound to a place, collected away from it. */
        ELSEWHERE,
        /**
         * It waits here, but the gate says its rewards cannot reach the subject now: no room for them, in the
         * usual case. The subject collects it once they have made room.
         */
        NO_ROOM
    }

    /** Holds the one-of rule: a payout carries no refusal, and a press with neither read as nothing waiting. */
    public ClaimAttempt {
        if (paid != null) {
            refusal = null;
        } else if (refusal == null) {
            refusal = Refusal.NOT_WAITING;
        }
    }

    /** A press that collected {@code paid}. */
    @Nonnull
    public static ClaimAttempt collected(@Nonnull RewardGrants.GrantOutcome paid) {
        return new ClaimAttempt(paid, null);
    }

    /** A press refused for {@code why}, which paid nothing. */
    @Nonnull
    public static ClaimAttempt refused(@Nonnull Refusal why) {
        return new ClaimAttempt(null, why);
    }

    /**
     * Why {@code attempt} paid nothing: its refusal, or {@link Refusal#NOT_WAITING} for no attempt at all (a call
     * scope that answered nothing). Null only for an attempt that collected.
     */
    @Nullable
    public static Refusal refusalOf(@Nullable ClaimAttempt attempt) {
        return attempt == null ? Refusal.NOT_WAITING : attempt.refusal();
    }

    /** What {@code attempt} paid, or null when it was refused or there was none. */
    @Nullable
    public static RewardGrants.GrantOutcome paidOf(@Nullable ClaimAttempt attempt) {
        return attempt == null ? null : attempt.paid();
    }
}
