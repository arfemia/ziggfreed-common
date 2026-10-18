package com.ziggfreed.common.progress.gate;

import java.util.UUID;

import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * A gate epoch moved ({@link GateEpochs}): one player's {@code Player} readings may have changed (their own
 * moment, a permission change, a world change), or, with no player, the server epoch moved and everyone's may
 * have. A gate asked only at a moment of its own choosing (a gear set's {@code Requires}, asked at a recompute)
 * listens to ask again.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired on the thread that moved the
 * epoch (often a world thread inside a moment's dispatch, sometimes another), only once the server has booted,
 * and often: every progression moment of a player fires one. A listener keeps its work to a lookup and hops to
 * the player's world thread for anything more.
 */
public final class ZigGateEpochMovedEvent implements IEvent<Void> {

    @Nullable private final UUID playerId;

    public ZigGateEpochMovedEvent(@Nullable UUID playerId) {
        this.playerId = playerId;
    }

    /** The player whose epoch moved, or null when the server epoch moved (every player). */
    @Nullable
    public UUID playerId() {
        return playerId;
    }
}
