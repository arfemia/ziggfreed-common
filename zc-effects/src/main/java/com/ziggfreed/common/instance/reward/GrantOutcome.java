package com.ziggfreed.common.instance.reward;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * The result of an {@link InstanceRewardGranter#grantAll} pass: how many rewards were
 * granted, how many were BLOCKED by a full inventory (and held as {@link #pending} for a
 * retry / re-claim), and how many outright failed. The results screen reflects this in
 * its reward chips (granted vs pending), mirroring hyMMO's non-throwing
 * isolate-each-grant {@code GrantOutcome} shape.
 */
public record GrantOutcome(int granted, int blocked, int failed, @Nonnull List<InstanceReward> pending) {

    public GrantOutcome {
        pending = List.copyOf(pending);
    }

    /** True if at least one reward was granted. */
    public boolean anyGranted() {
        return granted > 0;
    }

    /** True if a reward was blocked by a full inventory (the player must make space). */
    public boolean anyBlocked() {
        return blocked > 0;
    }
}
