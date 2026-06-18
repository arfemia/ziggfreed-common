package com.ziggfreed.common.lobby;

/**
 * The policy knobs of one {@link MatchmakingQueue}, a pure value object the consumer
 * builds (e.g. from an authored {@code RuleSet}'s min/max party knobs). All numeric
 * fields are clamped to sane minimums in the canonical constructor.
 *
 * <ul>
 *   <li>{@code minParty} (&ge;1) - players required before the countdown starts (and,
 *       when {@code allowSolo} is false, the floor that triggers the fill window).</li>
 *   <li>{@code maxParty} (&ge;{@code minParty}) - reaching this size starts the
 *       countdown immediately (skips the rest of the fill window).</li>
 *   <li>{@code fillTimeoutSeconds} (&ge;0) - how long to wait for more players (after
 *       the fill window is triggered) before counting down. 0 = count down at once.</li>
 *   <li>{@code countdownSeconds} (&ge;0) - the visible "launching in N" countdown
 *       before the launcher fires. 0 = launch immediately.</li>
 *   <li>{@code allowSolo} - when true, a lone player triggers the fill window on the
 *       first join and launches alone when it expires; when false, the fill window
 *       only triggers once {@code minParty} is present and a lone player waits.</li>
 *   <li>{@code leaderForceStart} - when true, the initiator (first joiner) may
 *       {@code forceStart} to skip the fill window.</li>
 * </ul>
 */
public record LobbyConfig(int minParty, int maxParty, int fillTimeoutSeconds, int countdownSeconds,
                          boolean allowSolo, boolean leaderForceStart) {

    public LobbyConfig {
        minParty = Math.max(1, minParty);
        maxParty = Math.max(minParty, maxParty);
        fillTimeoutSeconds = Math.max(0, fillTimeoutSeconds);
        countdownSeconds = Math.max(0, countdownSeconds);
    }
}
