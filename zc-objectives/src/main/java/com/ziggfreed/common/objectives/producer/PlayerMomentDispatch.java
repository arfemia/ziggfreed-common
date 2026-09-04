package com.ziggfreed.common.objectives.producer;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.util.SafeLog;

/**
 * The engine half every EVENT-BUS producer shares: a moment about a player named only by uuid,
 * fed to {@link ProgressDispatch#fire} on the world thread that player is actually on.
 *
 * <p>An ECS producer arrives already holding the acting entity; a bus listener (a finished round,
 * a boss beat) is told about a group of players and has to find each one. The world is resolved
 * PER PLAYER from that player's own live {@code Ref}'s own {@code Store} ({@code ref.getStore()} ->
 * {@code getExternalData()} -> {@code getWorld()}), the same engine-stable chain
 * {@code cast.WorldEvictors} reads, rather than {@code PlayerRef.getWorldUuid()} - a field a world
 * transfer writes only from {@code updatePosition}, so it can still name the world a player just
 * left for a tick or more after they have actually moved. When that world is the thread already
 * running (the normal case) the dispatch happens inline; otherwise it hops with
 * {@code world.execute}, because {@link ProgressDispatch} and the engines under it are world-thread
 * only. A player who has gone offline, or whose world is gone, is skipped at fine level: their
 * moment genuinely happened somewhere this server can no longer credit, and warning about it would
 * warn on every disconnect. A transfer that lands squarely between the hop and the run is caught at
 * the top of {@link #fireFor}, which re-checks the store is on its own thread before dispatching.
 *
 * <p>The player is re-resolved on the world thread rather than captured: on the hop path a tick or
 * more has passed, and a stale {@code Ref} would write this player's progress onto whatever now
 * occupies that slot.
 */
final class PlayerMomentDispatch {

    private PlayerMomentDispatch() {
    }

    /**
     * Feed one moment for {@code playerId}, wherever they are right now.
     *
     * @param label the producer's own word for its log lines ({@code instance-round}, {@code encounter})
     */
    static void fire(@Nonnull String label, @Nonnull UUID playerId, @Nonnull String kindId,
            @Nonnull String target, @Nullable String qualifier, long amount, @Nullable MomentPayload payload) {
        World world = worldOf(playerId);
        if (world == null) {
            SafeLog.fine("[progression] " + label + " " + kindId + " skipped for " + playerId
                    + " - no live world for that player");
            return;
        }
        if (world.isInThread()) {
            fireFor(label, playerId, kindId, target, qualifier, amount, payload);
            return;
        }
        world.execute(() -> fireFor(label, playerId, kindId, target, qualifier, amount, payload));
    }

    /** The world the player is on right now, or null when they are offline or their world has gone. */
    @Nullable
    private static World worldOf(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        PlayerRef playerRef = universe.getPlayer(playerId);
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        World world = ref == null ? null : ref.getStore().getExternalData().getWorld();
        return world != null && world.isAlive() ? world : null;
    }

    /** The dispatch itself, always on the owning world thread. */
    private static void fireFor(@Nonnull String label, @Nonnull UUID playerId, @Nonnull String kindId,
            @Nonnull String target, @Nullable String qualifier, long amount, @Nullable MomentPayload payload) {
        Universe universe = Universe.get();
        PlayerRef playerRef = universe == null ? null : universe.getPlayer(playerId);
        if (playerRef == null) {
            SafeLog.fine("[progression] " + label + " " + kindId + " skipped for " + playerId
                    + " - player left before it could be credited");
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            SafeLog.fine("[progression] " + label + " " + kindId + " skipped for " + playerId
                    + " - their entity is no longer in this store");
            return;
        }
        if (!ref.getStore().isInThread()) {
            // A world transfer landed between the hop and this run: the store worldOf resolved is no
            // longer the one running right now, so writing to it would credit the wrong world's
            // thread. Skip clean; the player's own next moment resolves fresh.
            SafeLog.fine("[progression] " + label + " " + kindId + " skipped for " + playerId
                    + " - a world transfer landed before this could dispatch");
            return;
        }
        // No command buffer: this is not an ECS system, so there is none to carry.
        ProgressDispatch.fire(ref.getStore(), ref, null, kindId, target, qualifier, amount, payload);
    }
}
