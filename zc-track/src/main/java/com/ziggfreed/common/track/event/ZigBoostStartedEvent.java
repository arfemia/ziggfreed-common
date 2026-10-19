package com.ziggfreed.common.track.event;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * A boost started through zc ({@code boost.Boosts.start}): a boost item, a reward, a command or a mod's own page.
 * Fired once per start, on the starter's own thread, after the start was kept and the gain plans and tiles moved,
 * so a listener reading the player's boosts sees it. A start that was refused fires none, and a boost running out
 * fires nothing.
 */
public final class ZigBoostStartedEvent implements IEvent<Void> {

    private final String boostId;
    private final String scope;
    private final UUID playerId;
    private final double percent;
    private final long startedAtMs;
    private final long endsAtMs;
    private final String by;

    public ZigBoostStartedEvent(@Nonnull String boostId, @Nonnull String scope, @Nonnull UUID playerId,
            double percent, long startedAtMs, long endsAtMs, @Nonnull String by) {
        this.boostId = boostId;
        this.scope = scope;
        this.playerId = playerId;
        this.percent = percent;
        this.startedAtMs = startedAtMs;
        this.endsAtMs = endsAtMs;
        this.by = by;
    }

    /** The boost's id, as its file spells it. */
    @Nonnull
    public String boostId() {
        return boostId;
    }

    /** Whose it is: {@code Personal} (the starter's own) or {@code Server} (every player's). */
    @Nonnull
    public String scope() {
        return scope;
    }

    /** Is it a server boost, lifting every player? */
    public boolean isServer() {
        return "Server".equalsIgnoreCase(scope);
    }

    /** The player who started it. */
    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The percent this start lifts by. */
    public double percent() {
        return percent;
    }

    /** When it started, in epoch milliseconds. */
    public long startedAtMs() {
        return startedAtMs;
    }

    /** When it ends, in epoch milliseconds. */
    public long endsAtMs() {
        return endsAtMs;
    }

    /** Who or what started it: a player's name, a reward's source, {@code admin}. */
    @Nonnull
    public String by() {
        return by;
    }
}
