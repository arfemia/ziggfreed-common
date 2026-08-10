package com.ziggfreed.common.lobby;

/**
 * The lifecycle of one {@link MatchmakingQueue}. Distinct from (and upstream of) a
 * consumer's own round/instance lifecycle: the queue hands a snapshotted party to the
 * injected {@code RoundLauncher} and is done.
 *
 * <ul>
 *   <li>{@code OPEN} - accepting joins; a fill window may be running once the trigger
 *       size is met.</li>
 *   <li>{@code COUNTDOWN} - the visible "launching in N" countdown is ticking; late
 *       joiners are still accepted and included at launch.</li>
 *   <li>{@code LAUNCHING} - the party has been snapshotted and the launcher invoked;
 *       no further joins.</li>
 *   <li>{@code CLOSED} - terminal; the queue has been removed from its
 *       {@link LobbyService} and a fresh one is created on the next join.</li>
 * </ul>
 */
public enum QueueState {
    OPEN,
    COUNTDOWN,
    LAUNCHING,
    CLOSED
}
