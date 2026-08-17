package com.ziggfreed.common.achievement;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Who won the race for a {@link Achievement#serverFirst() server-first}, and where that answer is
 * kept.
 *
 * <p>The DECISION - exactly one subject may ever earn it, and a loser keeps their criteria met - is
 * the engine's, and lives in the gate that asks this. What is left is a table: an achievement id
 * against the first subject to reach it. Whether that table survives a restart, and where it lives
 * if it does, is a property of the server's storage rather than of the rule, so it is a seam.
 *
 * <p>A claim is a TEST-AND-SET and must be atomic: two subjects finishing in the same tick both ask,
 * and exactly one must be told yes. An implementation that reads and then writes will hand the
 * achievement to both.
 */
@FunctionalInterface
public interface FirstClaimStore {

    /**
     * Claim {@code achievementId} for {@code subjectId}, or report that somebody already holds it.
     *
     * @param subjectName the claimant's display name, for whatever record the implementation keeps;
     *                    never used to identify the claimant, since a name can change
     * @return true when this subject took the claim, false when it was already held
     */
    boolean tryClaim(@Nonnull String achievementId, @Nonnull UUID subjectId,
            @Nullable String subjectName);
}
