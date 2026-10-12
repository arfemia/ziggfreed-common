package com.ziggfreed.common.instance.metadata;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * The ONE generic "a round has begun" moment any instanced mode built on this library fires: a minigame's
 * round, a co-op session, a dungeon run. The partner of {@link InstanceRoundCompletedEvent}: the same flat
 * {@link RoundMetadata} envelope (its duration and outcome tag are not known yet, so they read 0 and absent),
 * plus the players the start names, so a listener counts "rounds started" for any mode, present or future,
 * without a compile-time edge to the mod that runs it.
 *
 * <p><b>Who the start names.</b> The players in the round as it begins. A dungeon run begins at its first
 * arrival and names that player and every player already on the way into the same area; one who walks in
 * later joins the run (and is named when it ends) but is not named here.
 *
 * <p><b>Fire it through {@link InstanceRounds#fireStarted}</b>, on the instance world thread where one is
 * at hand; dispatch is synchronous on the calling thread, and a listener that needs a {@code Store} hops
 * with {@code world.execute} itself.
 *
 * <p>The list is copied on construction and handed out unmodifiable, so a listener cannot reach back into
 * the producer's round state.
 */
public final class InstanceRoundStartedEvent implements IEvent<Void> {

    @Nonnull
    private final RoundMetadata metadata;
    @Nonnull
    private final List<UUID> participants;

    /**
     * @param metadata     the round's envelope: which mod, which mode, which preset, how many players
     * @param participants the players the start names, by UUID. Copied; no element may be {@code null}
     */
    public InstanceRoundStartedEvent(@Nonnull RoundMetadata metadata, @Nonnull List<UUID> participants) {
        this.metadata = metadata;
        this.participants = List.copyOf(participants);
    }

    /** The flat, engine-free description of the round. */
    @Nonnull
    public RoundMetadata metadata() {
        return metadata;
    }

    /** The players the start names, unmodifiable. */
    @Nonnull
    public List<UUID> participants() {
        return participants;
    }
}
