package com.ziggfreed.common.achievement;

/**
 * Where one achievement (or one points milestone) stands for one subject.
 *
 * <p>Three states, not four: there is no "in progress". Criteria are always on, so a subject is
 * always making progress on everything they have not finished, and how far along they are lives in
 * the criterion counts rather than in a status.
 */
public enum AchievementStatus {

    /** Not earned yet. The default for anything nothing is recorded about. */
    LOCKED,

    /** Earned, with something still waiting to be collected. */
    UNLOCKED,

    /** Earned and fully paid out; the terminal state. */
    CLAIMED;

    /** Earned at all - true for both {@link #UNLOCKED} and {@link #CLAIMED}. */
    public boolean isUnlocked() {
        return this != LOCKED;
    }

    /** Fully settled: earned and nothing left to collect. */
    public boolean isClaimed() {
        return this == CLAIMED;
    }
}
