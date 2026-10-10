package com.ziggfreed.common.stats.gearset;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * A player put on another piece of a gear set and no tier of that set came on or went off: the
 * recompute after an equip change found more of the set's members on than last time, with the same
 * tiers holding.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link GearSetEvents}
 * for the fire contract. It fires only on a live change: never on the first recompute after login
 * or a world change (a hydrate), never when the count stayed or fell, never once the count already
 * stood at the full set, and never beside a {@link ZigGearSetTierChangedEvent} for the same set,
 * whose notice already carries the count.
 *
 * <p>A listener drawing a notice reads {@link #setName()} with {@link #pieces()} of
 * {@link #fullSet()}, and {@link #nextTierLine()} for the tier still to earn; it gets the live
 * {@link PlayerRef}, so it never looks the player up again on a thread it may not be on.
 */
public final class ZigGearSetPiecesChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    private final String setId;
    private final int pieces;
    private final int fullSet;
    @Nullable private final String setTitleKey;
    @Nullable private final String nextTierTitleKey;

    public ZigGearSetPiecesChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nonnull String setId,
            int pieces, int fullSet, @Nullable String setTitleKey, @Nullable String nextTierTitleKey) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.setId = setId;
        this.pieces = pieces;
        this.fullSet = fullSet;
        this.setTitleKey = setTitleKey;
        this.nextTierTitleKey = nextTierTitleKey;
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The player's live reference; every fire resolves one before it builds the event. */
    @Nonnull
    public PlayerRef playerRef() {
        return playerRef;
    }

    /** The set id as folded: lower-cased. */
    @Nonnull
    public String setId() {
        return setId;
    }

    /**
     * How many distinct members the player has on now, anywhere, at most {@link #fullSet()}
     * ({@link GearSetDecision#shownPieces}), so a spare member never reads past the full set.
     */
    public int pieces() {
        return pieces;
    }

    /** How many pieces the full set takes ({@link GearSetDecision#fullCount}). */
    public int fullSet() {
        return fullSet;
    }

    /** The set's authored title key, or null when the file names none. */
    @Nullable
    public String setTitleKey() {
        return setTitleKey;
    }

    /**
     * The line key of the tier still to earn ({@link GearSetDecision#nextTier} over what the player
     * has on now), or null when every tier holds or that tier carries no line.
     */
    @Nullable
    public String nextTierTitleKey() {
        return nextTierTitleKey;
    }

    /** The set's name as the reader's client resolves it; the id as plain text when it has no key. */
    @Nonnull
    public Message setName() {
        return GearSetEvents.setName(setTitleKey, setId);
    }

    /** The next tier's line as the reader's client resolves it, or null when there is none to name. */
    @Nullable
    public Message nextTierLine() {
        return GearSetEvents.line(nextTierTitleKey);
    }

    /** Whether there is a next tier to name ({@link #nextTierLine()} is not null). */
    public boolean hasNextTier() {
        return nextTierTitleKey != null;
    }
}
