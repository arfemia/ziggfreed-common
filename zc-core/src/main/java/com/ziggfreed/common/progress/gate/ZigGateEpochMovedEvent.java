package com.ziggfreed.common.progress.gate;

import java.util.UUID;

import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;

/**
 * A gate epoch moved ({@link GateEpochs}): one player's {@code Player} readings may have changed (their own
 * moment, a quest's completed status, a track reading, a permission change, a world change), or, with no
 * player, the server epoch moved and everyone's may have. A gate asked only at a moment of its own choosing (a
 * gear set's {@code Requires}, asked at a recompute) listens to ask again.
 *
 * <p><b>Registering on the bus is not enough to receive it.</b> A listener also registers a cheap test with
 * {@link GateEpochs#announceWhile} at setup, beside its bus registration, and a move is announced only while
 * some registered test answers true (a gear set's: "does anyone wear a gated set right now?"). Epochs move on
 * every progression moment of every player, so firing each move to a bus where nobody has anything to re-check
 * would allocate an event per moment for nothing; with no registered test, or none answering true, this never
 * fires, whatever the bus holds.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired on the thread that moved the
 * epoch (often a world thread inside a moment's dispatch, sometimes another), only once the server has booted,
 * and, while wanted, often. A listener keeps its work to a lookup and hops to the player's world thread for
 * anything more.
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
