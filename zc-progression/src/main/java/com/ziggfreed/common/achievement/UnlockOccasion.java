package com.ziggfreed.common.achievement;

/**
 * Why an unlock is being attempted: because the criteria were met in this very moment, or because
 * something is re-testing a state the subject has been standing in for a while.
 *
 * <p>The two are the same decision and reach the same gates - a refusal refuses either way. What
 * they are not is the same NEWS. A one-winner race is lost once, at the moment the loser finishes;
 * every later attempt on the same achievement re-discovers that same loss, and a login, a world
 * change and an achievement screen opening all make one. Told apart here, a gate can settle the
 * decision every time and announce it only when there is something to announce.
 */
public enum UnlockOccasion {

    /** The criteria completed in this very moment: whatever is decided now is decided for the first time. */
    JUST_MET,

    /**
     * A standing state being re-tested rather than a moment: the self-heal sweep on login and
     * whenever an achievement surface opens, a meta re-checked off one of those, a scripted grant.
     */
    STANDING
}
